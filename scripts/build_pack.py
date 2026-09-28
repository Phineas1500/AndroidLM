#!/usr/bin/env python3
"""The Ethereum and cryptography pack: its sources as a parquet file with the columns
build_corpus.py reads (page_id, title, text, infoboxes), a pageviews TSV that stands in for how
central each document is (build_corpus.py's --pageviews; the search's popularity prior), and a
TSV naming each document's source, address and licence.

Sources (each a local checkout or download, see --src):
  EIPs/, ERCs/          github.com/ethereum/EIPs and /ERCs (CC0-1.0): one document per EIP or
                        ERC; the preamble becomes its key facts, with the network upgrade that
                        included it (from the hardfork meta EIPs) and when that upgrade went live
  consensus-specs/      github.com/ethereum/consensus-specs (CC0-1.0): one document per spec file
  ethereum-org-website/ github.com/ethereum/ethereum-org-website (MIT): the English pages of
                        public/content, without tutorials, videos, policies and site pages
  upgrading-ethereum-book/  Ben Edgington's "Upgrading Ethereum" (eth2book.info, CC BY-SA 4.0):
                        one document per chapter
  nist/                 NIST FIPS 203/204/205, SP 800-208, IR 8413, 8545, 8547 as PDFs (US
                        Government works): the prose, without the algorithms' pseudocode
  nist-web/             NIST's post-quantum project and news pages as saved HTML (US Government)

Text is markdown as in the Wikipedia corpus ("# Title", then "## Section" headings): links keep
their text, images and site components go, tables become one "- column: value; ..." line per
row (build_corpus.py leaves table lines out of the index), and code blocks are indented so that
a "# comment" inside one is not read as a heading.

Usage: build_pack.py --src DIR --out pack.parquet --views views.tsv --sources sources.tsv
       build_corpus.py --out ethereum.db --pageviews views.tsv --full-top 100000 --min-chars 300 pack.parquet
       finish_pack.py ethereum.db sources.tsv "September 2026" DIR/ethereum-org-website/LICENSE
"""
import argparse
import datetime
import html
import json
import os
import re
import subprocess
from html.parser import HTMLParser

import pyarrow as pa
import pyarrow.parquet as pq

ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--views", required=True)
ap.add_argument("--sources", required=True)
args = ap.parse_args()

# ---- markdown -------------------------------------------------------------------------------

FENCE = re.compile(r"^\s*(```|~~~)")
TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
TITLE_ATTR = re.compile(r"\btitle=\"([^\"]+)\"")


def front_matter(text):
    """(fields, body) for a document that starts with a `---` block of `key: value` lines."""
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end < 0:
        return {}, text
    fields = {}
    for line in text[3:end].splitlines():
        m = re.match(r"^([A-Za-z][\w-]*):\s*(.*)$", line)
        if m:
            fields[m.group(1).lower()] = m.group(2).strip().strip("\"'")
    return fields, text[end + 4:].lstrip("\n")


def cells(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [inline(c.strip()) for c in re.split(r"(?<!\\)\|", s)]


def table_lines(rows):
    """A pipe table as one line per row: "- head: value; head: value" (empty cells left out)."""
    head = cells(rows[0])
    out = []
    for r in rows[2:] if len(rows) > 1 and TABLE_SEP.match(rows[1]) else rows[1:]:
        vals = cells(r)
        pairs = [f"{h}: {v}" if h else v for h, v in zip(head, vals) if v]
        if pairs:
            out.append("- " + "; ".join(pairs))
    return out


def inline(s):
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", s)                  # images
    s = re.sub(r"\[([^\]]*)\]\((?:[^()]|\([^)]*\))*\)", r"\1", s)  # links keep their text
    s = re.sub(r"\[([^\]]+)\]\[[^\]]*\]", r"\1", s)              # reference links
    s = re.sub(r"<(https?://[^>]+)>", "", s)                     # autolinks
    s = re.sub(r"</?[A-Za-z][^<>]*?/?>", lambda m: tag_text(m.group(0)), s)
    s = html.unescape(s)
    return re.sub(r"[ \t]+", " ", s).strip()


def tag_text(tag):
    """A site component or HTML tag goes; a component's title="..." stays as a bold line."""
    m = TITLE_ATTR.search(tag)
    return f"**{html.unescape(m.group(1))}** " if m and not tag.startswith("</") else ""


def clean_md(body, shift=0):
    """Markdown body -> the corpus's markdown (see the module comment). [shift] raises every
    heading by that many levels ("### A" with shift 1 is "## A")."""
    body = re.sub(r"<!--.*?-->", "", body, flags=re.S)
    out, lines, i = [], body.split("\n"), 0
    while i < len(lines):
        line = lines[i]
        if FENCE.match(line):
            fence = FENCE.match(line).group(1)
            lang = line.strip()[3:].strip().lower()
            code = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith(fence):
                code.append(lines[i])
                i += 1
            i += 1
            if lang in ("mermaid", "svg", "html"):
                continue
            # indented, and with no blank line inside, so it stays one paragraph and no "# " line
            # in it starts a section
            code = [("    " + c.rstrip()) if c.strip() else "    " for c in code]
            while code and not code[-1].strip():
                code.pop()
            if code:
                out.extend(["", *code, ""])
            continue
        if line.lstrip().startswith("|") and i + 1 < len(lines) and TABLE_SEP.match(lines[i + 1]):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append(lines[i])
                i += 1
            out.extend(["", *table_lines(rows), ""])
            continue
        m = re.match(r"^(#{1,6})\s+(.*?)\s*$", line)
        if m:
            level = max(2, len(m.group(1)) - shift)  # level 1 is the document's own title
            text = re.sub(r"\s*\{#[^}]*\}\s*$", "", m.group(2))
            text = inline(text).rstrip("#").strip()
            if text:
                out.extend(["", "#" * min(level, 6) + " " + text, ""])
            i += 1
            continue
        stripped = line.strip()
        if re.fullmatch(r"</?[A-Za-z][^<>]*>", stripped) and not TITLE_ATTR.search(stripped):
            i += 1  # a component on its own line
            continue
        out.append(inline(line) if stripped else "")
        i += 1
    text = unwrap("\n".join(out))
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


BLOCK_START = re.compile(r"^(\s*([-*+]|\d{1,3}[.)])\s|#{1,6}\s|    |\*\*|>)")


def unwrap(text):
    """Joins a hard-wrapped line to the line before it (many of the sources wrap at 80 columns),
    unless it starts a list item, heading, code line or quote, or the line before is code."""
    out = []
    for line in text.split("\n"):
        prev = out[-1] if out else ""
        if line.strip() and prev.strip() and not BLOCK_START.match(line) and not prev.startswith("    "):
            out[-1] = prev.rstrip() + " " + line.strip()
        else:
            out.append(line)
    return "\n".join(out)


def drop_sections(text, names):
    """Remove the level-2 sections with these headings (and everything under them)."""
    parts = re.split(r"(?m)^(?=## )", text)
    keep = [p for p in parts if not any(p.startswith(f"## {n}\n") or p.strip() == f"## {n}" for n in names)]
    return "".join(keep).strip()


def document(title, lead, body):
    return f"# {title}\n\n{lead}\n\n{body}".strip() if lead else f"# {title}\n\n{body}".strip()


# ---- the collected documents ------------------------------------------------------------------

docs = []  # (title, text, facts dict or None, weight, source, url, licence)


def add(title, text, facts, weight, source, url, licence):
    if len(text) >= 300:
        docs.append((title, text, facts, weight, source, url, licence))


# ---- EIPs and ERCs ----------------------------------------------------------------------------

STATUS_WEIGHT = {"final": 1000, "living": 1000, "last call": 500, "review": 300, "draft": 200,
                 "stagnant": 30, "withdrawn": 10, "moved": 0}
MONTHS = "January February March April May June July August September October November December".split()


def day_text(d):
    return f"{MONTHS[d.month - 1]} {d.day}, {d.year}"


def fork_dates():
    """Upgrade name -> the date it went live on mainnet, from ethereum.org's forks page tables
    ("| Prague | 2018 | IV | May 7, 2025 |", "| Electra | May 7, 2025 |")."""
    path = os.path.join(args.src, "ethereum-org-website/public/content/ethereum-forks/index.md")
    dates = {}
    for line in open(path):
        cols = [c.strip().strip("*_") for c in line.strip().strip("|").split("|")]
        if len(cols) < 2:
            continue
        name = re.sub(r"\[([^\]]*)\]\([^)]*\)+", r"\1", cols[0]).strip("*_ ")
        m = re.fullmatch(r"([A-Z][a-z]{2}) (\d{1,2}), (\d{4})", cols[-1])
        if m and name:
            dates[name.lower()] = datetime.datetime.strptime(cols[-1], "%b %d, %Y").date()
    return dates


def eip_files():
    """(number, kind, path, fields, body) for every EIP and ERC; an ERC in both repositories comes
    from the ERCs one (the EIPs repository keeps a "Moved" stub)."""
    found = {}
    for repo, folder, kind in (("EIPs", "EIPS", "EIP"), ("ERCs", "ERCS", "ERC")):
        d = os.path.join(args.src, repo, folder)
        for name in sorted(os.listdir(d)):
            m = re.fullmatch(r"(?:eip|erc)-(\d+)\.md", name)
            if not m:
                continue
            fields, body = front_matter(open(os.path.join(d, name), encoding="utf-8").read())
            n = int(m.group(1))
            if fields.get("status", "").lower() == "moved" and n in found:
                continue
            if kind == "ERC" or fields.get("category", "").upper() == "ERC":
                kind_n = "ERC"
            else:
                kind_n = "EIP"
            found[n] = (n, kind_n, repo, fields, body)
    return [found[n] for n in sorted(found)]


def eip_forks(files, dates):
    """EIP number -> (upgrade name, stage, date live or None), stage "live", "scheduled",
    "considered" or "proposed". An EIP linked under an upgrade on
    ethereum.org's forks page (a history of upgrades that went live) is live; otherwise the
    hardfork meta EIPs' `requires` lists and included-EIP links say which upgrade it is for, and
    that upgrade is live when its meta EIP is Final."""
    out = {}
    path = os.path.join(args.src, "ethereum-org-website/public/content/ethereum-forks/index.md")
    for sec in re.split(r"(?m)^### ", open(path, encoding="utf-8").read())[1:]:
        head = re.sub(r"\s*\{#[^}]*\}", "", sec.split("\n", 1)[0]).strip()
        m = re.search(r'\("([^"]+)"\)', head)  # Prague-Electra ("Pectra")
        name = m.group(1) if m else head
        live = None
        for word in re.findall(r"[A-Z][a-zé]+", head):
            live = live or dates.get(word.lower())
        for e in re.findall(r"eips\.ethereum\.org/EIPS/eip-(\d+)", sec):
            out.setdefault(int(e), (name, "live", live))
    for n, _, _, f, body in files:
        title = f.get("title", "")
        m = re.match(r"(?i)hardfork meta\s*[-:]\s*(.+)$", title)
        if not m or "backfill" in title.lower():
            continue
        name = m.group(1).strip()
        live = dates.get(name.lower())
        for half in re.findall(r"[A-Z][a-zé]+", f.get("description", "")):
            live = live or dates.get(half.lower())
        final = f.get("status", "").lower() == "final"
        # the included EIPs by the subsection that lists them ("EIPs Scheduled for Inclusion",
        # "Considered for Inclusion", "Declined for Inclusion", ...)
        stage = "included"
        found = {}
        for line in body.split("## Rationale")[0].split("\n"):
            h = re.match(r"^###\s+(.*)", line)
            if h:
                low = h.group(1).lower()
                stage = ("declined" if "declined" in low else "considered" if "considered" in low
                         else "proposed" if "proposed" in low else "activation" if "activation" in low else "included")
                continue
            if stage in ("declined", "activation"):
                continue
            for e in re.findall(r"\(\./eip-(\d+)\.md\)", line):
                found.setdefault(int(e), stage)
        if final:
            for e in re.findall(r"\d+", f.get("requires", "")):
                found.setdefault(int(e), "included")
        for e, st in found.items():
            if e == n:
                continue
            if final:
                out.setdefault(e, (name, "live", live))
            else:
                out.setdefault(e, (name, "scheduled" if st == "included" else st, None))
    return out


dates = fork_dates()
files = eip_files()
forks = eip_forks(files, dates)
n_eips = 0
for n, kind, repo, f, body in files:
    status = f.get("status", "")
    if status.lower() == "moved":
        continue
    title = f"{kind}-{n}: {f.get('title', '').strip()}"
    facts = {"Number": f"{kind}-{n}", "Status": status}
    for key, label in (("type", "Type"), ("category", "Category"), ("created", "Created"),
                       ("description", "Description")):
        if f.get(key):
            facts[label] = f[key]
    if f.get("requires"):
        facts["Requires"] = ", ".join(f"EIP-{x}" for x in re.findall(r"\d+", f["requires"]))
    if n in forks and f.get("type", "").lower() != "meta":
        name, stage, live = forks[n]
        if stage == "live":
            facts["Network upgrade"] = f"{name}, live on Ethereum mainnet" + (f" since {day_text(live)}" if live else "")
        else:
            facts["Network upgrade"] = f"{stage} for the {name} upgrade (not yet live)"
    text = document(title, "", drop_sections(clean_md(body), ["Copyright"]))
    folder = "EIPS" if repo == "EIPs" else "ERCS"
    add(title, text, facts, STATUS_WEIGHT.get(status.lower(), 100), repo,
        f"https://github.com/ethereum/{repo}/blob/master/{folder}/{kind.lower() if repo == 'ERCs' else 'eip'}-{n}.md",
        "CC0-1.0")
    n_eips += 1
print("EIPs and ERCs:", n_eips)

# ---- consensus specs ----------------------------------------------------------------------------

n_specs = 0
spec_root = os.path.join(args.src, "consensus-specs/specs")
for dirpath, _, names in sorted(os.walk(spec_root)):
    for name in sorted(names):
        if not name.endswith(".md"):
            continue
        path = os.path.join(dirpath, name)
        raw = open(path, encoding="utf-8").read()
        m = re.search(r"(?m)^#\s+(.+)$", raw)
        head = m.group(1).strip() if m else name[:-3]
        head = re.sub(r"\s+--\s+", " — ", head)
        rel = os.path.relpath(path, spec_root)
        body = raw[m.end():] if m else raw
        # the generated table of contents
        body = re.sub(r"(?s)<!-- mdformat-toc start.*?<!-- mdformat-toc end -->", "", body)
        body = re.sub(r"(?s)^\s*(- \[[^\n]*\n\s*)+", "", body)
        title = f"Consensus specs: {head}"
        feature = rel.startswith("_features")
        add(title, document(title, "", clean_md(body)), None, 100 if feature else 300, "consensus-specs",
            f"https://github.com/ethereum/consensus-specs/blob/master/specs/{rel}", "CC0-1.0")
        n_specs += 1
print("consensus specs:", n_specs)

# ---- ethereum.org -------------------------------------------------------------------------------

SKIP = {"developers/tutorials", "contributing", "videos", "cookie-policy", "privacy-policy", "terms-of-use",
        "stories", "translations", "community", "foundation", "about", "developers/local-environment"}
n_org = 0
org_root = os.path.join(args.src, "ethereum-org-website/public/content")
for dirpath, dirnames, names in sorted(os.walk(org_root)):
    rel_dir = os.path.relpath(dirpath, org_root)
    if any(rel_dir == s or rel_dir.startswith(s + "/") for s in SKIP):
        dirnames[:] = []
        continue
    for name in sorted(names):
        if not name.endswith(".md"):
            continue
        fields, body = front_matter(open(os.path.join(dirpath, name), encoding="utf-8").read())
        if fields.get("lang", "en") != "en" or not fields.get("title"):
            continue
        title = "ethereum.org: " + fields["title"]
        lead = inline(fields.get("description", ""))
        page = "" if rel_dir == "." else rel_dir
        weight = 1000 if page.startswith(("developers/docs", "roadmap", "staking", "ethereum-forks", "glossary")) else 500
        add(title, document(title, lead, clean_md(body)), None, weight, "ethereum.org",
            f"https://ethereum.org/{page}/".replace("//", "/").replace("https:/", "https://"), "MIT")
        n_org += 1
print("ethereum.org pages:", n_org)

# ---- Upgrading Ethereum (eth2book) ---------------------------------------------------------------

book = open(os.path.join(args.src, "upgrading-ethereum-book/src/book.md"), encoding="utf-8").read()
book = re.sub(r"<!--.*?-->", "", book, flags=re.S)
# chapters: level-2 headings outside code blocks, under level-1 parts
chapters, part, cur, in_code = [], "", None, False
for line in book.split("\n"):
    if FENCE.match(line):
        in_code = not in_code
    if not in_code and re.match(r"^# ", line):
        part = line[2:].strip()
        cur = None
        continue
    if not in_code and re.match(r"^## ", line):
        cur = [part, line[3:].strip(), []]
        chapters.append(cur)
        continue
    if cur is not None:
        cur[2].append(line)
names = [c[1] for c in chapters]
n_book = 0
for part, chapter, lines in chapters:
    label = chapter if names.count(chapter) == 1 else f"{chapter} ({part.split(':')[0]})"
    title = f"Upgrading Ethereum: {label}"
    add(title, document(title, "", clean_md("\n".join(lines), shift=1)), None, 700,
        "Upgrading Ethereum (eth2book.info) by Ben Edgington", "https://eth2book.info/", "CC-BY-SA-4.0")
    n_book += 1
print("Upgrading Ethereum chapters:", n_book)

# ---- NIST PDFs ------------------------------------------------------------------------------------

NIST_DOCS = {
    "NIST.FIPS.203": ("FIPS 203: Module-Lattice-Based Key-Encapsulation Mechanism Standard (ML-KEM)", "August 13, 2024"),
    "NIST.FIPS.204": ("FIPS 204: Module-Lattice-Based Digital Signature Standard (ML-DSA)", "August 13, 2024"),
    "NIST.FIPS.205": ("FIPS 205: Stateless Hash-Based Digital Signature Standard (SLH-DSA)", "August 13, 2024"),
    "NIST.SP.800-208": ("NIST SP 800-208: Recommendation for Stateful Hash-Based Signature Schemes (LMS, XMSS)", "October 2020"),
    "NIST.IR.8413-upd1": ("NIST IR 8413: Status Report on the Third Round of the NIST Post-Quantum Cryptography Standardization Process", "July 2022, updated September 2022"),
    "NIST.IR.8545": ("NIST IR 8545: Status Report on the Fourth Round of the NIST Post-Quantum Cryptography Standardization Process", "March 2025"),
    "NIST.IR.8547.ipd": ("NIST IR 8547 (draft): Transition to Post-Quantum Cryptography Standards", "November 2024 (initial public draft)"),
}
HEAD_NUM = re.compile(r"^\s*((?:\d+\.)*\d+|[A-Z]\.\d+(?:\.\d+)*|Appendix [A-Z])\.?\s{1,10}([A-Z][^.]{2,90}?)\s*$")
WORD = re.compile(r"[A-Za-z]{3,}")
LINE_NO = re.compile(r"^\s*\d{1,4}\s{2,}")


def prose_share(par):
    toks = par.split()
    return sum(1 for t in toks if WORD.fullmatch(t.strip(".,;:()\"'’“”"))) / max(1, len(toks))


def pdf_text(pdf):
    """A NIST PDF's prose as markdown: numbered headings become sections; the table of contents,
    page headers, page numbers and paragraphs that are mostly symbols (pseudocode, formulas,
    parameter tables) are left out."""
    raw = subprocess.run(["pdftotext", "-layout", "-q", pdf, "-"], capture_output=True, text=True).stdout
    pages = raw.split("\f")
    # a line that recurs on many pages is a running header or footer
    counts = {}
    for p in pages:
        for line in {l.strip() for l in p.split("\n") if l.strip()}:
            counts[line] = counts.get(line, 0) + 1
    running = {l for l, c in counts.items() if c >= max(3, len(pages) // 4)}
    out, par = [], []

    def end_par():
        if par:
            text = re.sub(r"\s+", " ", " ".join(par)).strip()
            text = re.sub(r"(\w)- (\w)", r"\1-\2", text)
            if len(text) > 60 and prose_share(text) >= 0.6:
                out.append(text)
            par.clear()

    started = False
    for p in pages:
        # a draft numbers its lines in the margin ("137   2.1. Cryptographic Standards")
        body = [l for l in p.split("\n") if l.strip()]
        if body and sum(1 for l in body if LINE_NO.match(l)) > len(body) / 2:
            p = "\n".join(LINE_NO.sub("", l) for l in p.split("\n"))
        # some PDFs set the text block far from the left edge: measure indents from the page's
        # usual left margin (the most common indent of its longer lines)
        indents = [len(l) - len(l.lstrip()) for l in p.split("\n") if len(l.strip()) > 40]
        margin = max(set(indents), key=indents.count) if indents else 0
        for line in p.split("\n"):
            line = line[min(margin, len(line) - len(line.lstrip())):]
            s = line.strip()
            if not s or s in running or re.fullmatch(r"[ivxlc\d]+", s) or ". . ." in s or "…….." in s:
                if not s:
                    end_par()
                continue
            m = HEAD_NUM.match(line)
            if m and (re.search(r"\s{3,}\d+$", s) or len(m.group(2).split()) > 10):
                m = None  # a table of contents line ("Introduction      1"), or a sentence
            if m and len(line) - len(line.lstrip()) < 8 and not s.endswith((",", ";")):
                depth = m.group(1).count(".") + 2 if not m.group(1).startswith("Appendix") else 2
                end_par()
                out.append("#" * min(depth, 5) + " " + m.group(2).strip())
                started = True
                continue
            if not started:
                continue  # the front matter before section 1: title pages and the announcement
            if len(line) - len(line.lstrip()) > 20 and len(s) < 60:
                end_par()  # a caption, formula or cell set apart from the text
                continue
            par.append(s)
            if s.endswith((".", ":")) and len(s) < 75:
                end_par()
        end_par()
    # drop headings left with nothing under them
    text, keep = [], []
    for i, block in enumerate(out):
        if block.startswith("#"):
            nxt = out[i + 1] if i + 1 < len(out) else "#"
            if nxt.startswith("#") and nxt.count("#") <= block.split(" ")[0].count("#"):
                continue
        keep.append(block)
    return "\n\n".join(keep)


n_nist = 0
for stem, (title, published) in NIST_DOCS.items():
    pdf = os.path.join(args.src, "nist", stem + ".pdf")
    if not os.path.exists(pdf):
        print("missing", pdf)
        continue
    body = pdf_text(pdf)
    add(title, document(title, f"{title.split(':')[0]}, published by NIST, {published}.", body),
        {"Publisher": "National Institute of Standards and Technology (NIST)", "Published": published},
        1000, "NIST", f"https://doi.org/10.6028/{stem}", "US Government work (public domain in the US)")
    n_nist += 1
print("NIST documents:", n_nist)

# ---- NIST web pages -------------------------------------------------------------------------------


class Page(HTMLParser):
    """The main text of a NIST page: headings, paragraphs and list items inside <main> (or the
    whole body when there is none), without navigation, scripts and footers."""
    SKIP = {"script", "style", "nav", "footer", "header", "noscript", "svg", "form", "button", "aside"}
    BLOCK = {"p", "li", "h1", "h2", "h3", "h4", "h5", "td", "th", "dt", "dd", "blockquote"}

    def __init__(self):
        super().__init__()
        self.skip = 0
        self.main = 0
        self.saw_main = False
        self.blocks = []
        self.cur = None
        self.tag = None
        self.title = ""
        self.in_title = False

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        if tag == "main" or dict(attrs).get("role") == "main":
            self.main += 1
            self.saw_main = True
        if tag == "title":
            self.in_title = True
        if tag in self.BLOCK and not self.skip:
            self.flush()
            self.cur, self.tag = [], tag

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        if tag == "main" and self.main:
            self.main -= 1
        if tag == "title":
            self.in_title = False
        if tag in self.BLOCK:
            self.flush()

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        if self.cur is not None and not self.skip:
            self.cur.append(data)

    def flush(self):
        if self.cur is not None:
            text = re.sub(r"\s+", " ", "".join(self.cur)).strip()
            if text:
                self.blocks.append((self.tag, text, self.main > 0))
        self.cur = None


WEB_TITLES = {"csrc-pqc-project": "Post-Quantum Cryptography project", "nist-pqc-faq": "Post-Quantum Cryptography FAQs",
              "csrc-pqc-dig-sig": "Post-Quantum Cryptography: Additional Digital Signature Schemes",
              "csrc-sp-800-208": "SP 800-208, Recommendation for Stateful Hash-Based Signature Schemes"}
WEB_BOILERPLATE = ("you are being redirected", "Official websites use .gov", "Secure .gov websites use HTTPS",
                   "Project Links", "Share sensitive information only on official")
n_web = 0
web = os.path.join(args.src, "nist-web")
for name in sorted(os.listdir(web)) if os.path.isdir(web) else []:
    if not name.endswith(".html"):
        continue
    p = Page()
    p.feed(open(os.path.join(web, name), encoding="utf-8", errors="replace").read())
    blocks = [b for b in p.blocks if b[2]] if p.saw_main else p.blocks
    page_title = WEB_TITLES.get(name[:-5]) or re.sub(r"\s*\|.*$", "", html.unescape(p.title)).strip()
    lines = []
    for tag, text, _ in blocks:
        if tag in ("h1",) or any(b in text for b in WEB_BOILERPLATE):
            continue
        if tag in ("h2", "h3", "h4", "h5"):
            lines += ["", "#" * (int(tag[1])) + " " + text, ""]
        elif tag == "li":
            lines.append("- " + text)
        elif len(text) > 40:
            lines += ["", text, ""]
    body = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    title = "NIST: " + page_title
    add(title, document(title, "", body), {"Publisher": "National Institute of Standards and Technology (NIST)"},
        1000, "NIST", "", "US Government work (public domain in the US)")
    n_web += 1
print("NIST web pages:", n_web)

# ---- output -----------------------------------------------------------------------------------------

# unique titles: a repeated one gets its source added
seen = {}
for d in docs:
    seen[d[0]] = seen.get(d[0], 0) + 1
rows = {"page_id": [], "title": [], "text": [], "infoboxes": []}
with open(args.views, "w") as vf, open(args.sources, "w") as sf:
    sf.write("page_id\ttitle\tsource\turl\tlicence\n")
    for i, (title, text, facts, weight, source, url, licence) in enumerate(docs, 1):
        if seen[title] > 1:
            title = f"{title} ({source}, {i})"
            text = re.sub(r"^# .*", f"# {title}", text, count=1)
        rows["page_id"].append(i)
        rows["title"].append(title)
        rows["text"].append(text)
        box = [{"data": facts}] if facts else []
        rows["infoboxes"].append(json.dumps(box, ensure_ascii=False) if box else "")
        vf.write(f"{i}\t{weight}\n")
        sf.write(f"{i}\t{title}\t{source}\t{url}\t{licence}\n")
pq.write_table(pa.table(rows), args.out)
print(f"{len(docs)} documents, {sum(len(t) for t in rows['text']) / 1e6:.1f} MB of text")
