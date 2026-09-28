#!/usr/bin/env python3
"""Unblind answer_sets.py grades ({id: {"scores": {letter: 0-10}, "errors": {letter: [...]},
"best": letter or "tie"}}): per system its mean score, its total as a share of the reference
system's, its errors, and how often it was best.

Usage: set_grades.py key.json grades.json REFERENCE_NAME
"""
import collections
import json
import statistics as st
import sys

key, grades, ref = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3]
scores, errors, best = collections.defaultdict(list), collections.Counter(), collections.Counter()
for qid, g in grades.items():
    k = key[qid]
    for letter, name in k.items():
        scores[name].append(g["scores"][letter])
        errors[name] += len(g["errors"].get(letter, []))
    best[k.get(g.get("best"), "tie")] += 1
ref_total = sum(scores[ref])
print(f"{len(grades)} questions")
print("| System | Mean /10 | Share of the reference | Errors | Best |")
print("|---|---|---|---|---|")
for name in sorted(scores, key=lambda n: (n == ref, n)):
    print(f"| {name} | {st.mean(scores[name]):.2f} | {sum(scores[name]) / ref_total:.0%} | {errors[name]} | {best[name]} |")
print(f"| tie | | | | {best['tie']} |")
