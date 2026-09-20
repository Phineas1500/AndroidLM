#!/usr/bin/env python3
"""Add Wikipedia's redirects to the corpus database so that a title the model proposes
("Rent control") resolves to the real article ("Rent regulation"). FineWiki drops redirects.

Inputs (from https://dumps.wikimedia.org/enwiki/latest/):
  enwiki-latest-redirect.sql.gz                          rd_from page_id -> target title
  enwiki-latest-pages-articles-multistream-index.txt.bz2  page_id -> title for every page

Everything is joined inside a scratch SQLite file, so memory stays small.
Output: table redirects(title COLLATE NOCASE PRIMARY KEY, article_id) in the corpus database.

Usage: build_redirects.py wiki.db redirect.sql.gz index.txt.bz2 [scratch.db]
"""
import bz2
import gzip
import os
import re
import sqlite3
import sys

wiki_path, redirect_path, index_path = sys.argv[1:4]
scratch_path = sys.argv[4] if len(sys.argv) > 4 else wiki_path + ".redirects.tmp"

# rows look like (10,0,'Computer_accessibility','',''), and newer dumps put one per line;
# only the first three fields are needed (the last two may also be NULL)
ROW = re.compile(r"\((\d+),(-?\d+),'((?:[^'\\]|\\.)*)',")

if os.path.exists(scratch_path):
    os.remove(scratch_path)
db = sqlite3.connect(scratch_path)
db.executescript("""
PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA cache_size=-300000;
CREATE TABLE rd(rd_from INTEGER PRIMARY KEY, target TEXT NOT NULL);
CREATE TABLE idx(page_id INTEGER PRIMARY KEY, title TEXT NOT NULL);
""")

n = 0
with gzip.open(redirect_path, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        if not line.startswith(("INSERT INTO", "(")):
            continue
        batch = []
        for m in ROW.finditer(line):
            if m.group(2) != "0":  # main namespace targets only
                continue
            target = re.sub(r"\\(.)", r"\1", m.group(3)).replace("_", " ")
            batch.append((int(m.group(1)), target))
        db.executemany("INSERT OR IGNORE INTO rd VALUES(?,?)", batch)
        n += len(batch)
db.commit()
print(f"redirect rows: {n}", flush=True)

# only redirect pages need a title from the index
wanted = {r[0] for r in db.execute("SELECT rd_from FROM rd")}
batch, n = [], 0
with bz2.open(index_path, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        _, pid, title = line.rstrip("\n").split(":", 2)
        pid = int(pid)
        if pid in wanted:
            batch.append((pid, title))
            if len(batch) >= 100000:
                db.executemany("INSERT OR IGNORE INTO idx VALUES(?,?)", batch)
                n += len(batch)
                batch = []
db.executemany("INSERT OR IGNORE INTO idx VALUES(?,?)", batch)
n += len(batch)
db.commit()
del wanted
print(f"redirect titles found: {n}", flush=True)

db.execute("ATTACH DATABASE ? AS wiki", (wiki_path,))
db.executescript("""
DROP TABLE IF EXISTS wiki.redirects;
CREATE TABLE wiki.redirects(title TEXT COLLATE NOCASE PRIMARY KEY, article_id INTEGER NOT NULL) WITHOUT ROWID;
INSERT OR IGNORE INTO wiki.redirects
    SELECT idx.title, a.id FROM rd
    JOIN idx ON idx.page_id = rd.rd_from
    JOIN wiki.articles a ON a.title = rd.target COLLATE NOCASE;

-- The redirect dump is newer than the corpus, so an article renamed in between shows up as
-- "old title -> new title" where only the OLD title is in the corpus ("Rent regulation" ->
-- "Rent control"). Map the new title, and everything else pointing at it, to that article.
CREATE TABLE moved AS
    SELECT rd.target AS new_title, a.id AS article_id FROM rd
    JOIN idx ON idx.page_id = rd.rd_from
    JOIN wiki.articles a ON a.title = idx.title COLLATE NOCASE;
CREATE INDEX moved_title ON moved(new_title);
INSERT OR IGNORE INTO wiki.redirects SELECT new_title, article_id FROM moved
    WHERE NOT EXISTS (SELECT 1 FROM wiki.articles a WHERE a.title = moved.new_title COLLATE NOCASE);
INSERT OR IGNORE INTO wiki.redirects
    SELECT idx.title, moved.article_id FROM rd
    JOIN moved ON moved.new_title = rd.target
    JOIN idx ON idx.page_id = rd.rd_from;
""")
db.commit()
count = db.execute("SELECT count(*) FROM wiki.redirects").fetchone()[0]
print(f"redirects stored: {count}", flush=True)
db.close()
os.remove(scratch_path)
