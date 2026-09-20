#!/usr/bin/env python3
"""Time retrieval alone over the eval questions. Usage: search_bench.py wiki.db questions.jsonl"""
import json
import statistics
import sys
import time

from rag import Corpus

corpus = Corpus(sys.argv[1])
times = []
for line in open(sys.argv[2]):
    q = json.loads(line)
    t0 = time.time()
    stems = corpus.stems(q["q"])
    terms = corpus.query_terms(stems)
    hits = corpus.bm25(stems)
    ms = (time.time() - t0) * 1000
    times.append(ms)
    print(f'{q["id"]} {ms:6.0f} ms  terms={terms}  top={[h["title"] for h in hits[:3]]}')
print(f"median {statistics.median(times):.0f} ms, max {max(times):.0f} ms")
