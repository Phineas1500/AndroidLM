#!/usr/bin/env python3
"""Blind sets for grading several sets of answers to the same questions side by side (the app
before and after a change, and a reference), so that one grader's strictness applies to all of
them: for each question the answers under the letters A, B, C... in a random order (fixed seed),
with the question set's expected facts, and a key saying which letter is which system.
`set_grades.py` unblinds the grades.

Usage: answer_sets.py questions.jsonl sets.json key.json NAME=answers.jsonl [NAME=answers.jsonl ...]
       (answers: records with "id" and "draft"; only the questions every set answers are kept)
"""
import json
import random
import string
import sys

qfile, out, keyfile = sys.argv[1:4]
systems = [a.split("=", 1) for a in sys.argv[4:]]
qs = {r["id"]: r for r in map(json.loads, filter(str.strip, open(qfile)))}
answers = {name: {r["id"]: r["draft"] for r in map(json.loads, filter(str.strip, open(path)))} for name, path in systems}
ids = sorted(set.intersection(*(set(a) for a in answers.values())))
rng = random.Random(20260929)
sets, key = [], {}
for qid in ids:
    names = [n for n, _ in systems]
    rng.shuffle(names)
    letters = string.ascii_uppercase[:len(names)]
    q = qs[qid]
    sets.append({"id": qid, "q": q["q"], "expect": q.get("expect") or q.get("check"),
                 "answers": {L: answers[n][qid] for L, n in zip(letters, names)}})
    key[qid] = dict(zip(letters, names))
json.dump(sets, open(out, "w"), ensure_ascii=False, indent=1)
json.dump(key, open(keyfile, "w"), indent=1)
print(len(sets), "sets of", len(systems))
