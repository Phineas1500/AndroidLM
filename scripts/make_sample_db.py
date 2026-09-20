#!/usr/bin/env python3
"""Cut a small corpus database out of a full one, for tests of the Kotlin port.

Keeps the named articles plus N random ones, with the same schema, the same chunk ids and
offsets, a rebuilt FTS index, and the redirects that point at kept articles.

Usage: make_sample_db.py full.db sample.db titles.txt N_RANDOM
"""
import random
import sqlite3
import sys

import zstandard as zstd

from rag import Corpus

full_path, out_path, titles_path, n_random = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
src = Corpus(full_path)
ids = []
for title in (t.strip() for t in open(titles_path) if t.strip()):
    aid = src.resolve_title(title, fuzzy=False)
    if aid is None:
        print("not found:", title)
    elif aid not in ids:
        ids.append(aid)
random.seed(11)
lo, hi = src.db.execute("select min(id), max(id) from articles").fetchone()
while len(ids) < n_random + len(open(titles_path).read().split("\n")):
    row = src.db.execute("select id from articles where id >= ? limit 1", (random.randint(lo, hi),)).fetchone()
    if row and row[0] not in ids:
        ids.append(row[0])

out = sqlite3.connect(out_path)
out.executescript("""
PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA page_size=8192;
CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB);
CREATE TABLE blocks(id INTEGER PRIMARY KEY, zdata BLOB NOT NULL);
CREATE TABLE articles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, views INTEGER NOT NULL,
                      block_id INTEGER NOT NULL, off INTEGER NOT NULL, len INTEGER NOT NULL);
CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL);
CREATE VIRTUAL TABLE fts USING fts5(title, section, body, content='', detail=full,
    tokenize='porter unicode61 remove_diacritics 2');
CREATE TABLE redirects(title TEXT COLLATE NOCASE PRIMARY KEY, article_id INTEGER NOT NULL) WITHOUT ROWID;
""")
cctx = zstd.ZstdCompressor(level=12)
block_id, block = 1, bytearray()
for aid in sorted(ids):
    title, views, text = src.article(aid)
    data = text.encode()
    out.execute("INSERT INTO articles VALUES(?,?,?,?,?,?)", (aid, title, views, block_id, len(block), len(data)))
    block += data
    if len(block) >= 256 * 1024:
        out.execute("INSERT INTO blocks VALUES(?,?)", (block_id, cctx.compress(bytes(block))))
        block_id, block = block_id + 1, bytearray()
    for cid, start, end in src.db.execute("select id, start, end from chunks where article_id=? order by id", (aid,)):
        out.execute("INSERT INTO chunks VALUES(?,?,?,?)", (cid, aid, start, end))
        piece = text[start:end]
        lines = piece.split("\n")
        if sum(1 for line in lines if line.startswith("|")) * 2 > len(lines):
            continue  # table chunks are stored but not indexed, as in build_corpus.py
        out.execute("INSERT INTO fts(rowid, title, section, body) VALUES(?,?,?,?)",
                    (cid, title, src.section_of(text, start), piece))
if block:
    out.execute("INSERT INTO blocks VALUES(?,?)", (block_id, cctx.compress(bytes(block))))
if src.has_redirects:
    # redirects is keyed by title, so look the kept articles up in one scan, not one per article
    kept = set(ids)
    for rt, aid in src.db.execute("select title, article_id from redirects"):
        if aid in kept:
            out.execute("INSERT OR IGNORE INTO redirects VALUES(?,?)", (rt, aid))
out.execute("INSERT INTO fts(fts) VALUES('optimize')")
out.execute("CREATE INDEX chunks_article ON chunks(article_id)")
out.execute("CREATE INDEX articles_title ON articles(title COLLATE NOCASE)")
out.commit()
print(f"kept {len(ids)} articles")
