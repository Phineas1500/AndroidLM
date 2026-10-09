#!/usr/bin/env python3
"""For saved answer records: does the prompt context (sources-first: build_context; answer-first:
the check's check_context over the saved draft) differ between corpus builds? Usage:
context_diff.py old.db new.db voyage.db records.jsonl [gate_views]"""
import json
import sys

from rag import Corpus, build_context, check_context

old, new, voyage = Corpus(sys.argv[1]), Corpus(sys.argv[2]), Corpus(sys.argv[3])
gate = Corpus(sys.argv[2])
gate.body_min_views = int(sys.argv[5]) if len(sys.argv) > 5 else 186
for r in map(json.loads, filter(str.strip, open(sys.argv[4]))):
    out = {}
    for name, c in (("old", old), ("new", new), ("gate", gate)):
        hits = c.retrieve(r["q"], r["plan_titles"], k=6, voyage=voyage)
        if r.get("route") == "verify" and r.get("draft"):
            ctx, used = check_context(hits, r["draft"], r["q"], 2000)
        else:
            ctx, used = build_context(hits, 4000)
        out[name] = (ctx, [h["title"] + " — " + h["section"] for h in used])
    print(r["id"], r.get("route"), "new==old" if out["new"][0] == out["old"][0] else "NEW DIFFERS",
          "gate==old" if out["gate"][0] == out["old"][0] else "GATE DIFFERS")
    for name in ("old", "new", "gate"):
        print("   ", name, out[name][1])
