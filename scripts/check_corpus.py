#!/usr/bin/env python3
"""Sanity-check a corpus database: table sizes, chunk boundaries, and a few BM25 queries.

Usage: check_corpus.py wiki.db ["query" ...]
"""
import re
import sqlite3
import sys
import time

STOP = set("a an the of in on at to for and or is are was were be by with from as that this "
           "which what who how why did does do it its their his her".split())
DEFAULT_QUERIES = [
    "who commanded the crusaders in the 1114 expedition against Majorca",
    "causes of the 1755 earthquake",
]

db = sqlite3.connect(sys.argv[1])
queries = sys.argv[2:] or DEFAULT_QUERIES

sizes = dict(db.execute("select name, sum(pgsize) from dbstat group by name"))
fts_mb = sum(v for k, v in sizes.items() if k.startswith("fts")) / 1e6
print(f"blocks {sizes.get('blocks', 0)/1e6:.1f} MB | articles {sizes.get('articles', 0)/1e6:.1f} MB | "
      f"chunks {sizes.get('chunks', 0)/1e6:.1f} MB | fts {fts_mb:.1f} MB")

n_art, n_chunk = db.execute("select (select count(*) from articles), (select count(*) from chunks)").fetchone()
overlap = db.execute(
    "select count(*) from chunks a join chunks b on b.id = a.id + 1 and b.article_id = a.article_id "
    "where b.start < a.end").fetchone()[0]
mx, avg = db.execute("select max(end - start), avg(end - start) from chunks").fetchone()
print(f"{n_art} articles, {n_chunk} chunks | overlapping neighbours: {overlap} | chunk chars max {mx}, avg {avg:.0f}")

for text in queries:
    terms = [t for t in re.findall(r"[A-Za-z0-9]+", text.lower()) if t not in STOP]
    t0 = time.time()
    rows = db.execute(
        "select rowid, bm25(fts, 8.0, 3.0, 1.0) s from fts where fts match ? order by s limit 5",
        (" OR ".join(terms),)).fetchall()
    ms = (time.time() - t0) * 1000
    hits = []
    for rid, score in rows:
        aid, start, end = db.execute("select article_id, start, end from chunks where id = ?", (rid,)).fetchone()
        title = db.execute("select title from articles where id = ?", (aid,)).fetchone()[0]
        hits.append(f"{title}[{start}:{end}] {score:.1f}")
    print(f"{ms:.0f} ms | {text}\n    " + "\n    ".join(hits))
