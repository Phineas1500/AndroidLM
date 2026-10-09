#!/usr/bin/env python3
"""Compare what two corpus builds retrieve for the same questions, with the plans already saved
in answer records (rag.py output: "q" and "plan_titles"), so no model is needed. Prints which
questions get different passages, and the search times on each.

Usage: retrieval_diff.py old.db new.db voyage.db answers.jsonl [answers.jsonl ...] > diff.jsonl
RETRIEVAL_DIFF_BODY_MIN_VIEWS=N sets the new build's Corpus.body_min_views.
"""
import json
import os
import statistics
import sys
import time

from rag import Corpus


def passages(corpus, voyage, q, titles):
    t0 = time.time()
    hits = corpus.retrieve(q, titles, k=6, voyage=voyage)
    ms = (time.time() - t0) * 1000
    return [(h["title"], h["section"], h["via"], h["text"][:120]) for h in hits], ms


def main():
    old, new, voyage = Corpus(sys.argv[1]), Corpus(sys.argv[2]), Corpus(sys.argv[3])
    new.body_min_views = int(os.environ.get("RETRIEVAL_DIFF_BODY_MIN_VIEWS", "0"))
    seen, same, times_old, times_new = set(), 0, [], []
    for path in sys.argv[4:]:
        for r in map(json.loads, filter(str.strip, open(path))):
            q = r["q"]
            if q in seen or not r.get("plan_titles"):
                continue
            seen.add(q)
            a, ta = passages(old, voyage, q, r["plan_titles"])
            b, tb = passages(new, voyage, q, r["plan_titles"])
            times_old.append(ta)
            times_new.append(tb)
            same += a == b
            print(json.dumps({"id": r.get("id"), "q": q, "route": r.get("route"), "same": a == b,
                              "old_ms": round(ta), "new_ms": round(tb),
                              "old": [f"{t} — {s} ({v})" for t, s, v, _ in a],
                              "new": [f"{t} — {s} ({v})" for t, s, v, _ in b]}, ensure_ascii=False), flush=True)
    n = len(seen)
    print(f"{n} questions: {same} retrieve the same passages, {n - same} differ; search median "
          f"{statistics.median(times_old):.0f} ms old, {statistics.median(times_new):.0f} ms new; max "
          f"{max(times_old):.0f} / {max(times_new):.0f} ms", file=sys.stderr)


if __name__ == "__main__":
    main()
