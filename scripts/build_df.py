#!/usr/bin/env python3
"""Build wiki_df.db: how many chunks of wiki.db contain each common stem.

The search ranks a question's words by that count (fts5vocab 'row' `doc`), which the full-text
index can only give by reading the word's whole posting list: seconds per question on a phone for
words in millions of chunks. This file holds the same counts for every stem found in at least
MIN_DOC chunks, so the search looks them up instead; rarer stems are not stored and are still
counted from the index, where their lists are short. The counts are the index's own, so the search
returns exactly what it would without this file.

meta records what it was built from: `n_indexed` (max chunk id) and `check`, a few stems with small
counts that a reader can recount from the index cheaply to confirm the file belongs to its wiki.db.

usage: build_df.py <wiki.db> <wiki_df.db> [min_doc]
"""
import json
import os
import sqlite3
import sys
import time

src, dest = sys.argv[1:3]
MIN_DOC = int(sys.argv[3]) if len(sys.argv) > 3 else 256

if os.path.exists(dest):
    sys.exit(f"{dest} exists; remove it first")
con = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
con.execute("CREATE VIRTUAL TABLE temp.v USING fts5vocab(main, fts, row)")
n_indexed = con.execute("select max(id) from chunks").fetchone()[0]

out = sqlite3.connect(dest)
out.executescript("""
    PRAGMA journal_mode=OFF;
    PRAGMA synchronous=OFF;
    CREATE TABLE df(term TEXT PRIMARY KEY, doc INTEGER NOT NULL) WITHOUT ROWID;
    CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
""")
t0 = time.time()
batch, kept, seen, smallest = [], 0, 0, []
for term, doc in con.execute("select term, doc from temp.v"):  # term order, one pass over the index
    seen += 1
    if doc < MIN_DOC:
        continue
    batch.append((term, doc))
    smallest.append((doc, term))
    if len(smallest) > 5000:
        smallest = sorted(smallest)[:3]
    if len(batch) >= 50000:
        out.executemany("insert into df values(?, ?)", batch)
        kept += len(batch)
        batch.clear()
        print(f"{seen:,} stems read, {kept:,} kept, {time.time() - t0:.0f} s", flush=True)
out.executemany("insert into df values(?, ?)", batch)
kept += len(batch)
check = [[t, d] for d, t in sorted(smallest)[:3]]
meta = {"n_indexed": n_indexed, "min_doc": MIN_DOC, "stems": kept, "check": json.dumps(check),
        "source_bytes": os.path.getsize(src)}
out.executemany("insert into meta values(?, ?)", [(k, str(v)) for k, v in meta.items()])
out.commit()
out.execute("VACUUM")
out.close()
print(f"done: {seen:,} stems in the index, {kept:,} with doc >= {MIN_DOC}; check {check}; "
      f"{os.path.getsize(dest) / 1e6:.1f} MB in {time.time() - t0:.0f} s")
