#!/usr/bin/env python3
"""Unblind check_pairs.py grades with their key: per form (first, second), how often the check
helped / was neutral / hurt, the errors it introduced, the draft errors it missed, and which
form the grader preferred, overall and per question kind (the id's prefix).

Usage: check_grades.py grades.json key.json [first-name second-name]
"""
import collections
import json
import sys

grades, key = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
names = {"first": sys.argv[3] if len(sys.argv) > 3 else "first", "second": sys.argv[4] if len(sys.argv) > 4 else "second"}
eff = {f: collections.Counter() for f in names}
errors = {f: 0 for f in names}
missed = {f: 0 for f in names}
prefer = collections.Counter()
by_kind = collections.defaultdict(collections.Counter)
listed = {f: [] for f in names}
for qid, g in grades.items():
    k = key[qid]
    for side in "AB":
        form = k[side]
        eff[form][g[side + "_effect"]] += 1
        errors[form] += len(g[side + "_errors"])
        missed[form] += bool(g[side + "_missed"])
        listed[form] += [f"{qid}: {e}" for e in g[side + "_errors"]]
    p = g["prefer"]
    won = "tie" if p == "tie" else k[p]
    prefer[won] += 1
    by_kind[qid.split("-")[0]][won] += 1
n = len(grades)
print(f"{n} questions")
for f, name in names.items():
    e = eff[f]
    print(f"{name:>10}: helped {e['helped']}, neutral {e['neutral']}, hurt {e['hurt']}; errors {errors[f]}; "
          f"draft errors missed {missed[f]}; preferred {prefer[f]}")
print(f"{'tie':>10}: {prefer['tie']}")
print("by kind (first / second / tie):")
for kind, c in sorted(by_kind.items()):
    print(f"  {kind:8} {c['first']} / {c['second']} / {c['tie']}")
for f, name in names.items():
    print(f"errors in {name}:")
    for e in listed[f]:
        print("  -", e)
