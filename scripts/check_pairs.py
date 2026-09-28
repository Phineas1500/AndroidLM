#!/usr/bin/env python3
"""Blind pairs for grading two forms of the source check on the same drafts: for each question
the draft and the two checks as A and B in a random order (fixed seed), with the question set's
expected facts, and a key file saying which form is which.

Usage: check_pairs.py questions.jsonl first.jsonl second.jsonl pairs.json key.json
       (first/second: check_ab2.py outputs, or an answers file whose "check" is the first form)
"""
import json
import random
import sys

qfile, first, second, out, keyfile = sys.argv[1:6]
# (the red-team question files call the expected facts "check")
expect = {r["id"]: r.get("expect") or r.get("check") for r in map(json.loads, filter(str.strip, open(qfile)))}
a = {json.loads(l)["id"]: json.loads(l) for l in open(first) if l.strip()}
b = {json.loads(l)["id"]: json.loads(l) for l in open(second) if l.strip()}
rng = random.Random(20260928)
pairs, key = [], {}
for qid in sorted(set(a) & set(b)):
    x, y = a[qid], b[qid]
    assert x["draft"] == y["draft"], qid
    swap = rng.random() < 0.5
    A, B = (y, x) if swap else (x, y)
    pairs.append({"id": qid, "q": x["q"], "expect": expect.get(qid), "draft": x["draft"],
                  "A": A["check"], "B": B["check"]})
    key[qid] = {"A": "second" if swap else "first", "B": "first" if swap else "second"}
json.dump(pairs, open(out, "w"), ensure_ascii=False, indent=1)
json.dump(key, open(keyfile, "w"), indent=1)
print(len(pairs), "pairs")
