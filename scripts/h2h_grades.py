#!/usr/bin/env python3
"""Unblinds a three-way side-by-side (androidlm, commonplace, web): per system its mean score, its
total as a share of the web reference's, its errors and how often it was best; AndroidLM against
Commonplace question by question (with a sign test), by category and by grader.

usage: h2h_grades.py key.json grades.json questions.jsonl"""
import json, sys, math, collections, statistics as st
kfile, gfile, qfile = sys.argv[1:4]
key = json.load(open(kfile))
cat = {}
for l in open(qfile):
    r = json.loads(l); cat[r["id"]] = r.get("category") or r.get("cat")
grades = json.load(open(gfile))
n = gfile.rsplit("/", 1)[-1].removeprefix("grades_sets_").removesuffix(".json")
missing = sorted(set(key) - set(grades)); print("missing:", missing)
sys_ = ["androidlm", "commonplace", "web"]
sc = {s: {} for s in sys_}; err = {s: {} for s in sys_}; best = collections.Counter()
for qid, g in grades.items():
    for L, s in key[qid].items():
        sc[s][qid] = g["scores"][L]; err[s][qid] = g["errors"].get(L, [])
    b = g["best"]; best[key[qid].get(b, "tie")] += 1
ids = sorted(grades)
tot = {s: sum(sc[s].values()) for s in sys_}
print(f"{n}: {len(ids)} questions")
for s in sys_:
    v = list(sc[s].values())
    print(f"  {s:12s} mean {st.mean(v):.2f}  share of web {tot[s]/tot['web']:.0%}  errors {sum(len(e) for e in err[s].values())} (in {sum(1 for e in err[s].values() if e)} answers)  best {best[s]}")
print("  best=tie", best["tie"])
w = sum(sc['androidlm'][i] > sc['commonplace'][i] for i in ids); l = sum(sc['androidlm'][i] < sc['commonplace'][i] for i in ids); t = len(ids) - w - l
p = sum(math.comb(w + l, k) for k in range(min(w, l) + 1)) * 2 / 2 ** (w + l) if w + l else 1
print(f"  AndroidLM vs Commonplace per question: {w} wins, {l} losses, {t} ties (two-sided sign test p={min(p,1):.3g})")
bycat = collections.defaultdict(list)
for i in ids: bycat[cat.get(i)].append(i)
for c, qs in sorted(bycat.items()):
    a = st.mean(sc['androidlm'][i] for i in qs); b = st.mean(sc['commonplace'][i] for i in qs); r = st.mean(sc['web'][i] for i in qs)
    print(f"    {str(c):18s} n={len(qs):2d}  androidlm {a:.1f}  commonplace {b:.1f}  web {r:.1f}")
for k in (1, 2, 3):
    qs = [i for i in ids if grades[i]["grader"] == k]
    print(f"    grader {k}: " + "  ".join(f"{s} {st.mean(sc[s][i] for i in qs):.1f}" for s in sys_))
