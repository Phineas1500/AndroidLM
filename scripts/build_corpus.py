#!/usr/bin/env python3
"""Build the offline corpus database from FineWiki parquet shards.

Output is one SQLite file:
  meta(key, value)                          build parameters
  blocks(id, zdata)                         zstd-compressed runs of article text (~--block-kb each)
  articles(id, title, views, block_id, off, len)   id = Wikipedia page_id; off/len in bytes in the block
  chunks(id, article_id, start, end)        character offsets into the article text
  fts(title, section, body)                 contentless FTS5 index, rowid = chunks.id

Tiering (the full corpus is ~40GB of text, too much for the 50GB app budget): articles ranked
in the top --full-top by monthly pageviews keep their whole text; all others keep only the
lead section. Table-heavy chunks stay in the stored text but are left out of the index, since
rows of numbers bloat the index and rank badly under BM25.

Article text is "<infobox facts>\n\n<markdown body>"; chunks never cross a heading and are
packed from paragraphs up to --chunk-chars. The section path of a chunk is recovered at query
time by scanning headings before `start`, so it is not stored. FTS5 needs detail=full for
bm25() to rank (column/none score every row 0).

Usage: build_corpus.py --out wiki.db --pageviews pageviews_en.tsv --full-top 1000000 shard.parquet...
"""
import argparse
import json
import re
import sqlite3
import sys
import time

import pyarrow.parquet as pq
import zstandard as zstd

ap = argparse.ArgumentParser()
ap.add_argument("shards", nargs="+")
ap.add_argument("--out", required=True)
ap.add_argument("--pageviews", help="TSV of page_id<TAB>views; without it every article is kept in full")
ap.add_argument("--full-top", type=int, default=1_000_000, help="articles kept in full, by pageview rank")
ap.add_argument("--lead-chars", type=int, default=2500)
ap.add_argument("--limit", type=int, default=0, help="stop after N articles (0 = all)")
ap.add_argument("--chunk-chars", type=int, default=1000)
ap.add_argument("--max-chunk-chars", type=int, default=1500)
ap.add_argument("--block-kb", type=int, default=256)
ap.add_argument("--zlevel", type=int, default=12)
ap.add_argument("--min-chars", type=int, default=200, help="skip articles shorter than this")
args = ap.parse_args()

HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
CUT = re.compile(r"\n|(?<=[.!?])\s+")
COLUMNS = ["page_id", "title", "text", "infoboxes"]


def infobox_text(raw):
    """Flatten FineWiki's structured infoboxes into one 'Key facts' paragraph."""
    if not raw:
        return ""
    try:
        boxes = json.loads(raw)
    except ValueError:
        return ""
    facts = []
    for box in boxes:
        for k, v in (box.get("data") or {}).items():
            if isinstance(v, str) and k and v and len(v) < 200:
                facts.append(f"{k}: {v}")
    text = "; ".join(facts)
    return ("Key facts: " + text[:800]) if text else ""


def lead_of(text):
    """Text before the first level-2 heading, capped at a paragraph boundary."""
    cut = text.find("\n## ")
    lead = text if cut < 0 else text[:cut]
    if len(lead) > args.lead_chars:
        end = lead.rfind("\n\n", 0, args.lead_chars)
        lead = lead[:end if end > 0 else args.lead_chars]
    return lead


def split_long(par, limit):
    """Split an oversized paragraph (usually a table or list) into (start, end) spans,
    cutting after a line or sentence end, and hard-splitting only when there is none."""
    spans, s, last = [], 0, 0
    for c in [m.end() for m in CUT.finditer(par)] + [len(par)]:
        if c - s > limit and last > s:
            spans.append((s, last))
            s = last
        while c - s > limit * 1.5:
            spans.append((s, s + limit))
            s += limit
        last = c
    if s < len(par):
        spans.append((s, len(par)))
    return spans


def chunk_article(text):
    """Return [(start, end, section_path)] over `text`; chunks never cross a heading."""
    path, out = [], []
    pos = 0
    cur_start = cur_end = None

    def flush():
        nonlocal cur_start, cur_end
        if cur_start is not None and text[cur_start:cur_end].strip():
            out.append((cur_start, cur_end, " > ".join(path[1:])))
        cur_start = cur_end = None

    for par in text.split("\n\n"):
        start, end = pos, pos + len(par)
        pos = end + 2
        if not par.strip():
            continue
        first_line = par.split("\n", 1)[0]
        m = HEADING.match(first_line)
        if m:
            flush()
            level = len(m.group(1))
            del path[level - 1:]
            path.extend([""] * (level - 1 - len(path)))
            path.append(m.group(2).strip())
            body_off = len(first_line) + 1
            if len(par) <= body_off:
                continue
            start += body_off  # heading line itself is carried by the section path
            par = par[body_off:]
        if len(par) > args.max_chunk_chars:
            flush()
            for s, e in split_long(par, args.chunk_chars):
                out.append((start + s, start + e, " > ".join(path[1:])))
            continue
        if cur_start is not None and end - cur_start > args.chunk_chars:
            flush()
        if cur_start is None:
            cur_start = start
        cur_end = end
    flush()
    return out


def is_table(chunk):
    lines = chunk.split("\n")
    return sum(1 for line in lines if line.startswith("|")) * 2 > len(lines)


def rows(shards):
    n = 0
    for shard in shards:
        f = pq.ParquetFile(shard)
        for g in range(f.metadata.num_row_groups):
            for r in f.read_row_group(g, columns=COLUMNS).to_pylist():
                if len(r["text"] or "") < args.min_chars:
                    continue
                yield r
                n += 1
                if args.limit and n >= args.limit:
                    return


views = {}
full_ids = None
if args.pageviews:
    for line in open(args.pageviews):
        pid, v = line.split("\t")
        views[int(pid)] = int(v)
    ranked = sorted(views, key=views.get, reverse=True)
    full_ids = set(ranked[:args.full_top])
    del ranked
    print(f"pageviews for {len(views)} pages; {len(full_ids)} kept in full", flush=True)

t0 = time.time()
cctx = zstd.ZstdCompressor(level=args.zlevel)
db = sqlite3.connect(args.out)
db.executescript("""
PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA page_size=8192; PRAGMA cache_size=-400000;
CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB);
CREATE TABLE blocks(id INTEGER PRIMARY KEY, zdata BLOB NOT NULL);
CREATE TABLE articles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, views INTEGER NOT NULL,
                      block_id INTEGER NOT NULL, off INTEGER NOT NULL, len INTEGER NOT NULL);
CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL);
CREATE VIRTUAL TABLE fts USING fts5(title, section, body, content='', detail=full,
    tokenize='porter unicode61 remove_diacritics 2');
""")
db.execute("INSERT INTO meta VALUES('params', ?)", (json.dumps(vars(args)),))

block_id, block = 1, bytearray()


def flush_block():
    global block_id, block
    if block:
        db.execute("INSERT INTO blocks VALUES(?,?)", (block_id, cctx.compress(bytes(block))))
        block_id += 1
        block = bytearray()


n_art = n_full = n_chunk = n_indexed = raw_bytes = 0
seen = set()
for r in rows(args.shards):
    pid = r["page_id"]
    if pid in seen:
        continue
    seen.add(pid)
    full = full_ids is None or pid in full_ids
    body = r["text"] if full else lead_of(r["text"])
    box = infobox_text(r["infoboxes"])
    text = f"{box}\n\n{body}" if box else body
    chunks = chunk_article(text)
    if not chunks:
        continue
    data = text.encode()
    db.execute("INSERT INTO articles VALUES(?,?,?,?,?,?)",
               (pid, r["title"], views.get(pid, 0), block_id, len(block), len(data)))
    block += data
    if len(block) >= args.block_kb * 1024:
        flush_block()
    for start, end, section in chunks:
        n_chunk += 1
        db.execute("INSERT INTO chunks VALUES(?,?,?,?)", (n_chunk, pid, start, end))
        piece = text[start:end]
        if not is_table(piece):
            n_indexed += 1
            db.execute("INSERT INTO fts(rowid, title, section, body) VALUES(?,?,?,?)",
                       (n_chunk, r["title"], section, piece))
    n_art += 1
    n_full += full
    raw_bytes += len(data)
    if n_art % 50000 == 0:
        db.commit()
        rate = n_art / (time.time() - t0)
        print(f"{n_art} articles ({n_full} full), {n_chunk} chunks ({n_indexed} indexed), "
              f"{raw_bytes/1e9:.2f} GB text, {rate:.0f} art/s", flush=True)

flush_block()
db.commit()
print("optimizing fts...", flush=True)
db.execute("INSERT INTO fts(fts) VALUES('optimize')")
db.execute("CREATE INDEX chunks_article ON chunks(article_id)")
db.execute("CREATE INDEX articles_title ON articles(title COLLATE NOCASE)")
db.commit()
db.close()
print(f"done: {n_art} articles ({n_full} full), {n_chunk} chunks ({n_indexed} indexed), "
      f"{raw_bytes/1e9:.2f} GB text, {time.time()-t0:.0f}s", file=sys.stderr)
