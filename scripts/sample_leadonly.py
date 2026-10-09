#!/usr/bin/env python3
"""Sample articles the lead-only tier of the old wiki.db cut short (under 186 monthly views), with
their lead and three body passages from the full-text build, as raw material for questions whose
answers sit past the lead. Usage: sample_leadonly.py old.db full.db N > leadonly_sample.jsonl"""
import json
import random
import sys

from rag import Corpus

old, new = Corpus(sys.argv[1]), Corpus(sys.argv[2])
n = int(sys.argv[3])
random.seed(11)
lo, hi = new.db.execute("select min(id), max(id) from articles").fetchone()
out = 0
while out < n:
    row = new.db.execute(
        "select id from articles where id >= ? and views between 20 and 180 and len > 9000 limit 1",
        (random.randint(lo, hi),)).fetchone()
    if not row:
        continue
    title, views, text = new.article(row[0])
    if title.startswith(("List of", "Timeline of", "Lists of")) or text.count("\n## ") < 3:
        continue
    oid = old.resolve_title(title)
    if oid is None:
        continue
    otitle, _, otext = old.article(oid)
    if otitle != title or len(otext) > 3000:
        continue
    chunks = new.db.execute("select start, end from chunks where article_id=? order by id", (row[0],)).fetchall()
    body = [c for c in chunks if c[0] >= len(otext) and c[1] - c[0] > 400
            and not text[c[0]:c[1]].lstrip().startswith(("|", "Key facts"))]
    if len(body) < 4:
        continue
    picks = sorted(random.sample(body, 3))
    print(json.dumps({"title": title, "views": views, "old_chars": len(otext), "new_chars": len(text),
                      "lead": otext[:900], "passages": [
        {"section": new.section_of(text, s), "text": text[s:e][:900]} for s, e in picks]}, ensure_ascii=False))
    out += 1
