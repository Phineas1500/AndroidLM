#!/usr/bin/env python3
"""Re-run only the source check of earlier answer-first runs in the follow-up-turn form, reusing
their saved drafts and plan titles, so the two check forms can be compared on identical drafts
and sources. Retrieval is recomputed; records whose recomputed sources differ are flagged.

Usage: check_ab.py --db wiki.db --voyage-db voyage.db --out out.jsonl answers.jsonl[:id_prefix] ...
"""
import argparse
import json
import os
import time

import rag

ap = argparse.ArgumentParser()
ap.add_argument("inputs", nargs="+", help="answers.jsonl, optionally :prefix to keep only ids starting with it")
ap.add_argument("--db", required=True)
ap.add_argument("--voyage-db")
ap.add_argument("--url", default="http://127.0.0.1:8091")
ap.add_argument("--out", required=True)
args = ap.parse_args()

wiki = rag.Corpus(args.db)
voyage = rag.Corpus(args.voyage_db) if args.voyage_db else None
done = set()
if os.path.exists(args.out):
    done = {json.loads(line)["id"] for line in open(args.out) if line.strip()}

records = []
for spec in args.inputs:
    path, _, prefix = spec.partition(":")
    for line in open(path):
        r = json.loads(line)
        if r.get("route") == "verify" and r.get("check") is not None and r["id"].startswith(prefix):
            records.append(r)

for r in records:
    if r["id"] in done:
        continue
    hits = wiki.retrieve(r["q"], r["plan_titles"], k=6, voyage=voyage)
    context, used = rag.build_context(hits, 4000)
    sources = [f"{h['title']} — {h['section']} ({h['via']})" for h in used]
    messages = [{"role": "system", "content": rag.CLOSED_SYSTEM}, {"role": "user", "content": r["q"]},
                {"role": "assistant", "content": r["draft"]},
                {"role": "user", "content": rag.check_followup_user(context)}]
    t0 = time.time()
    res = rag.chat_messages(args.url, messages, 260)
    rec = {"id": r["id"], "cat": r.get("cat"), "q": r["q"], "draft": r["draft"], "sources": sources,
           "sources_match": sources == r["sources"], "old_check": r["check"],
           "new_check": rag.clean_check(res["text"]), "new_finish": res["finish"],
           "new_prompt_tokens": res["prompt_tokens"], "new_gen_tokens": res["gen_tokens"],
           "old_prompt_tokens": r.get("prompt_tokens"), "wall_s": round(time.time() - t0, 1)}
    with open(args.out, "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f'{r["id"]}: sources_match={rec["sources_match"]} prompt {rec["old_prompt_tokens"]} -> '
          f'{rec["new_prompt_tokens"]} tok, {rec["wall_s"]} s', flush=True)
print("CHECK_AB_DONE", flush=True)
