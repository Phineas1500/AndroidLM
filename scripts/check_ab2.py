#!/usr/bin/env python3
"""Re-run only the source check (the follow-up-turn form the app uses) of earlier answer-first
runs, on their saved drafts, with the check's context built one of three ways, so the forms can
be compared on identical drafts and retrieved passages:

  base      rag.build_context(hits, 4000): what the app reads today
  passages  rag.check_context(hits, draft, question, BUDGET): the passages that share most with
            the draft and the question first, within BUDGET characters
  excerpts  the same with each passage cut to its sentences that do

Usage: check_ab2.py --db wiki.db --voyage-db voyage.db --variant excerpts [--budget 2000]
                    --out out.jsonl [--ids a,b] [--skip-prefix poi,place] answers.jsonl
"""
import argparse
import json
import os
import time

import rag

ap = argparse.ArgumentParser()
ap.add_argument("answers")
ap.add_argument("--db", required=True)
ap.add_argument("--voyage-db")
ap.add_argument("--url", default="http://127.0.0.1:8091")
ap.add_argument("--variant", choices=["base", "passages", "excerpts"], required=True)
ap.add_argument("--budget", type=int, default=2000)
ap.add_argument("--ids", help="only these ids (comma-separated)")
ap.add_argument("--skip-prefix", default="", help="leave out ids starting with these (comma-separated)")
ap.add_argument("--out", required=True)
args = ap.parse_args()

wiki = rag.Corpus(args.db)
voyage = rag.Corpus(args.voyage_db) if args.voyage_db else None
done = set()
if os.path.exists(args.out):
    done = {json.loads(line)["id"] for line in open(args.out) if line.strip()}
only = set(args.ids.split(",")) if args.ids else None
skip = tuple(p for p in args.skip_prefix.split(",") if p)

for line in open(args.answers):
    r = json.loads(line)
    if r.get("route") != "verify" or r.get("check") is None or r["id"] in done:
        continue
    if (only and r["id"] not in only) or (skip and r["id"].startswith(skip)):
        continue
    hits = wiki.retrieve(r["q"], r["plan_titles"], k=6, voyage=voyage)
    if args.variant == "base":
        context, used = rag.build_context(hits, 4000)
    else:
        context, used = rag.check_context(hits, r["draft"], r["q"], args.budget, excerpt=args.variant == "excerpts")
    base_sources = [f"{h['title']} — {h['section']} ({h['via']})" for h in rag.build_context(hits, 4000)[1]]
    messages = [{"role": "system", "content": rag.CLOSED_SYSTEM}, {"role": "user", "content": r["q"]},
                {"role": "assistant", "content": r["draft"]},
                {"role": "user", "content": rag.check_followup_user(context)}]
    t0 = time.time()
    res = rag.chat_messages(args.url, messages, 260)
    rec = {"id": r["id"], "cat": r.get("cat"), "q": r["q"], "variant": args.variant, "budget": args.budget,
           "draft": r["draft"], "context_chars": len(context),
           "sources": [f"{h['title']} — {h['section']} ({h['via']})" for h in used],
           "sources_match_run": base_sources == r["sources"], "run_check": r["check"],
           "check": rag.clean_check(res["text"]), "finish": res["finish"],
           "prompt_tokens": res["prompt_tokens"], "gen_tokens": res["gen_tokens"], "wall_s": round(time.time() - t0, 1)}
    with open(args.out, "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f'{r["id"]} {args.variant}: {rec["context_chars"]} chars, prompt {rec["prompt_tokens"]} tok, '
          f'{rec["gen_tokens"]} gen, {rec["wall_s"]} s', flush=True)
print("CHECK_AB2_DONE", flush=True)
