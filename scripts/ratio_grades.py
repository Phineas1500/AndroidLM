#!/usr/bin/env python3
"""Unblind answer_pairs.py grades (A/B scores 0-10) into "how good is the app next to the
reference": per category and overall, the app's total score as a share of the reference's, how often
each was preferred, and the errors each made; with the app's timings when given.

Usage: ratio_grades.py key.json app.jsonl grades.json [grades.json ...]
  key.json: answer_pairs.py key, "first" = the app, "second" = the reference
  app.jsonl: the app's answer records (for first_answer_ms / done_ms), or - to skip timings
"""
import collections
import json
import statistics as st
import sys

key = json.load(open(sys.argv[1]))
app = {} if sys.argv[2] == "-" else {r["id"]: r for r in map(json.loads, filter(str.strip, open(sys.argv[2])))}
grades = {}
for g in sys.argv[3:]:
    grades.update(json.load(open(g)))

rows = collections.defaultdict(lambda: {"app": [], "ref": [], "pref": collections.Counter(), "app_err": 0, "ref_err": 0,
                                        "first": [], "done": []})
for qid, g in grades.items():
    k = key[qid]
    side = {k["A"]: "A", k["B"]: "B"}
    for cat in (qid.split("-")[0], "all"):
        r = rows[cat]
        r["app"].append(g[side["first"] + "_score"])
        r["ref"].append(g[side["second"] + "_score"])
        r["app_err"] += len(g[side["first"] + "_errors"])
        r["ref_err"] += len(g[side["second"] + "_errors"])
        p = g["prefer"]
        r["pref"]["tie" if p == "tie" else ("app" if k[p] == "first" else "reference")] += 1
        a = app.get(qid, {})
        if a.get("first_answer_ms"):
            r["first"].append(a["first_answer_ms"] / 1000)
        if a.get("done_ms"):
            r["done"].append(a["done_ms"] / 1000)

print("| Category | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference / tie | Errors app / reference | First words (median) | Done (median) |")
print("|---|---|---|---|---|---|---|---|---|")
for cat in sorted(rows, key=lambda c: (c == "all", c)):
    r = rows[cat]
    n = len(r["app"])
    share = sum(r["app"]) / sum(r["ref"]) if sum(r["ref"]) else float("nan")
    f = f"{st.median(r['first']):.0f} s" if r["first"] else "-"
    d = f"{st.median(r['done']):.0f} s" if r["done"] else "-"
    print(f"| {cat} | {n} | {st.mean(r['app']):.2f} | {st.mean(r['ref']):.2f} | {share:.0%} | "
          f"{r['pref']['app']} / {r['pref']['reference']} / {r['pref']['tie']} | {r['app_err']} / {r['ref_err']} | {f} | {d} |")
