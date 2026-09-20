#!/usr/bin/env python3
"""Convert the English Wikivoyage XML dump into a parquet file with the columns build_corpus.py
reads (page_id, title, text, infoboxes), plus a redirects TSV (from_title<TAB>to_title).

Wikivoyage keeps its most useful content (sights, restaurants, hotels) inside listing
templates such as {{see|name=...|address=...|hours=...|price=...|content=...}}; those are
rendered as list items. Other templates, files, references and tables are dropped, headings
become markdown headings, and links and emphasis are reduced to their text.

Usage: wikivoyage_to_parquet.py enwikivoyage-latest-pages-articles.xml.bz2 out.parquet redirects.tsv
"""
import bz2
import re
import sys
import xml.etree.ElementTree as ET

import pyarrow as pa
import pyarrow.parquet as pq

LISTINGS = {"see", "do", "buy", "eat", "drink", "sleep", "listing", "go", "marker"}
LISTING_FIELDS = ["alt", "address", "directions", "hours", "price", "content"]


def split_top(s, sep="|"):
    """Split on `sep` outside nested {{ }} and [[ ]]."""
    parts, depth, cur, i = [], 0, [], 0
    while i < len(s):
        two = s[i:i + 2]
        if two in ("{{", "[["):
            depth += 1; cur.append(two); i += 2
        elif two in ("}}", "]]"):
            depth -= 1; cur.append(two); i += 2
        elif s[i] == sep and depth == 0:
            parts.append("".join(cur)); cur = []; i += 1
        else:
            cur.append(s[i]); i += 1
    parts.append("".join(cur))
    return parts


def render_template(body):
    parts = split_top(body)
    name = parts[0].strip().lower()
    if name not in LISTINGS:
        return ""
    fields = {}
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            fields[k.strip().lower()] = v.strip()
    title = fields.get("name", "")
    if not title or name == "marker":
        return ""
    bits = [clean_inline(fields[f]) for f in LISTING_FIELDS if fields.get(f)]
    return f"- {clean_inline(title)}: " + ". ".join(b for b in bits if b)


def strip_templates(text):
    """Replace every top-level {{...}} with its rendering (listings) or nothing."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("{{", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("{{", j):
                    depth += 1; j += 2
                elif text.startswith("}}", j):
                    depth -= 1; j += 2
                else:
                    j += 1
            out.append(render_template(text[i + 2:j - 2]))
            i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def clean_inline(s):
    s = strip_templates(s) if "{{" in s else s
    s = re.sub(r"\[\[(?:File|Image|Category):[^\]]*\]\]", "", s, flags=re.I)
    s = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", s)
    s = re.sub(r"\[?https?://\S+\]?", "", s)
    s = re.sub(r"'{2,}", "", s)
    s = re.sub(r"<[^>]+>", "", s)
    return re.sub(r"[ \t]+", " ", s).strip()


def clean_page(wikitext):
    t = re.sub(r"<!--.*?-->", "", wikitext, flags=re.S)
    t = re.sub(r"<ref[^>]*?/>|<ref[^>]*>.*?</ref>", "", t, flags=re.S)
    t = re.sub(r"<gallery.*?</gallery>", "", t, flags=re.S)
    t = re.sub(r"^\{\|.*?^\|\}", "", t, flags=re.S | re.M)  # tables
    t = strip_templates(t)
    lines = []
    for line in t.split("\n"):
        m = re.match(r"^(={2,6})\s*(.*?)\s*=+\s*$", line)
        if m:
            lines.append("")
            lines.append("#" * len(m.group(1)) + " " + clean_inline(m.group(2)))
            lines.append("")
            continue
        line = re.sub(r"^[*#]+\s*", "- ", line)
        line = re.sub(r"^[:;]+\s*", "", line)
        lines.append(clean_inline(line))
    text = "\n".join(lines)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def local(tag):
    return tag.rsplit("}", 1)[-1]


ids, titles, texts = [], [], []
n_redirect = 0
with bz2.open(sys.argv[1], "rb") as f, open(sys.argv[3], "w") as red:
    page = {}
    for event, el in ET.iterparse(f, events=("end",)):
        tag = local(el.tag)
        if tag == "title":
            page["title"] = el.text or ""
        elif tag == "ns":
            page["ns"] = el.text
        elif tag == "id" and "id" not in page:
            page["id"] = int(el.text)
        elif tag == "redirect":
            page["redirect"] = el.get("title")
        elif tag == "text":
            page["text"] = el.text or ""
        elif tag == "page":
            if page.get("ns") == "0":
                if page.get("redirect"):
                    red.write(f'{page["title"]}\t{page["redirect"]}\n')
                    n_redirect += 1
                else:
                    text = clean_page(page.get("text", ""))
                    if len(text) >= 200:
                        ids.append(page["id"])
                        titles.append(page["title"])
                        texts.append(f'# {page["title"]}\n\n{text}')
            page = {}
            el.clear()

table = pa.table({"page_id": ids, "title": titles, "text": texts,
                  "infoboxes": pa.array([None] * len(ids), type=pa.string())})
pq.write_table(table, sys.argv[2], row_group_size=2000)
print(f"articles: {len(ids)}, redirects: {n_redirect}, text: {sum(map(len, texts))/1e6:.0f} MB")
