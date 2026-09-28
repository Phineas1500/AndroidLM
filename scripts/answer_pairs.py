#!/usr/bin/env python3
"""Blind pairs for grading two sets of answers to the same questions (two models, or two prompts):
for each question both answers as A and B in a random order (fixed seed), with the question set's
expected facts, and a key file saying which set is which.

Usage: answer_pairs.py questions.jsonl first.jsonl second.jsonl pairs.json key.json
       (first/second: records with "id" and "draft", e.g. rag.py answers or engine_drafts.py output)
"""
import json
import random
import sys

qfile, first, second, out, keyfile = sys.argv[1:6]
# (the red-team question files call the expected facts "check")
qs = {r["id"]: r for r in map(json.loads, filter(str.strip, open(qfile)))}
a = {r["id"]: r for r in map(json.loads, filter(str.strip, open(first)))}
b = {r["id"]: r for r in map(json.loads, filter(str.strip, open(second)))}
rng = random.Random(20260928)
pairs, key = [], {}
for qid in sorted(set(a) & set(b)):
    swap = rng.random() < 0.5
    A, B = (b[qid], a[qid]) if swap else (a[qid], b[qid])
    q = qs[qid]
    pairs.append({"id": qid, "q": q["q"], "expect": q.get("expect") or q.get("check"), "A": A["draft"], "B": B["draft"]})
    key[qid] = {"A": "second" if swap else "first", "B": "first" if swap else "second"}
json.dump(pairs, open(out, "w"), ensure_ascii=False, indent=1)
json.dump(key, open(keyfile, "w"), indent=1)
print(len(pairs), "pairs")
