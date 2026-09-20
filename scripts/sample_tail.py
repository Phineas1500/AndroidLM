#!/usr/bin/env python3
"""Sample mid-tail articles (moderate pageviews, full text kept) with two passages each, as raw
material for writing long-tail eval questions with gold answers.
Usage: sample_tail.py wiki.db N > tail_sample.jsonl"""
import json
import random
import sys

from rag import Corpus

corpus = Corpus(sys.argv[1])
n = int(sys.argv[2])
random.seed(7)
lo, hi = corpus.db.execute("select min(id), max(id) from articles").fetchone()
out = 0
while out < n:
    row = corpus.db.execute(
        "select id from articles where id >= ? and views between 400 and 4000 and len > 6000 limit 1",
        (random.randint(lo, hi),)).fetchone()
    if not row:
        continue
    title, views, text = corpus.article(row[0])
    if title.startswith(("List of", "Timeline of")) or "\n## " not in text:
        continue
    chunks = corpus.db.execute("select start, end from chunks where article_id=? order by id", (row[0],)).fetchall()
    body = [c for c in chunks if c[1] - c[0] > 500 and not text[c[0]:c[1]].lstrip().startswith(("|", "Key facts"))]
    if len(body) < 3:
        continue
    picks = [body[0], random.choice(body[1:])]
    print(json.dumps({"title": title, "views": views, "passages": [
        {"section": corpus.section_of(text, s), "text": text[s:e]} for s, e in picks]}, ensure_ascii=False))
    out += 1
