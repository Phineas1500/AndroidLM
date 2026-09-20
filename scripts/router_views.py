#!/usr/bin/env python3
"""For each answered question, the monthly pageviews of the articles its planned titles resolve to.
Usage: router_views.py wiki.db answers.jsonl > views.jsonl"""
import json
import sys

from rag import Corpus

corpus = Corpus(sys.argv[1])
for line in open(sys.argv[2]):
    r = json.loads(line)
    views = []
    for t in r.get("plan_titles") or []:
        aid = corpus.resolve_title(t)
        views.append(corpus.db.execute("select views from articles where id=?", (aid,)).fetchone()[0] if aid else None)
    print(json.dumps({"id": r["id"], "titles": r.get("plan_titles"), "views": views}))
