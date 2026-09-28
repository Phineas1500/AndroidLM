#!/usr/bin/env python3
"""Answers written by the engine in a session run (one BMOE_DONE line per request) as check_pairs.py
records: id, q, draft (the answer), and the engine's counts.

Usage: engine_drafts.py session.out ids.json questions.jsonl out.jsonl
  ids.json: the question ids in request order (request i+1 answers ids[i])
"""
import json
import sys

out_file, ids_file, qfile, dest = sys.argv[1:5]
ids = json.load(open(ids_file))
qs = {r["id"]: r for r in map(json.loads, filter(str.strip, open(qfile)))}
n = 0
with open(dest, "w") as f:
    for line in open(out_file, errors="replace"):
        if not line.startswith("BMOE_DONE "):
            continue
        d = json.loads(line[len("BMOE_DONE "):])
        qid = ids[int(d["id"]) - 1]
        rec = {"id": qid, "q": qs[qid]["q"], "draft": d.get("text", "").strip(), "n_gen": d.get("tokens"),
               "tok_s": d.get("tok_s"), "n_prompt": d.get("n_prompt"), "prefill_s": d.get("prefill_s"),
               "check": d.get("text", "").strip()}
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        n += 1
print(n, "answers")
