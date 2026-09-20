#!/usr/bin/env python3
"""Run the eval questions against a llama-server endpoint and save answers + timings.

Usage: run_eval.py --questions eval/questions_v0.jsonl --out eval/answers_TAG.jsonl
                   [--url http://127.0.0.1:8091] [--max-tokens 450] [--system FILE]
Resumable: questions whose id is already in --out are skipped.
"""
import argparse
import json
import os
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--questions", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--url", default="http://127.0.0.1:8091")
ap.add_argument("--max-tokens", type=int, default=450)
ap.add_argument("--system", default=None, help="file with a system prompt")
args = ap.parse_args()

system = open(args.system).read().strip() if args.system else (
    "You are an offline research assistant. Answer accurately and concisely. "
    "If you are not sure of a fact, say so rather than guessing."
)

done = set()
if os.path.exists(args.out):
    with open(args.out) as f:
        done = {json.loads(line)["id"] for line in f if line.strip()}

with open(args.questions) as f:
    questions = [json.loads(line) for line in f if line.strip()]

for q in questions:
    if q["id"] in done:
        continue
    body = {
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": q["q"]},
        ],
        "max_tokens": args.max_tokens,
        # Qwen's recommended non-thinking sampling, fixed seed for comparability
        "temperature": 0.7, "top_p": 0.8, "top_k": 20, "presence_penalty": 1.5,
        "seed": 1234,
    }
    req = urllib.request.Request(
        args.url + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        resp = json.load(r)
    wall = time.time() - t0
    choice = resp["choices"][0]
    timings = resp.get("timings", {})
    rec = {
        "id": q["id"], "cat": q["cat"], "q": q["q"],
        "answer": choice["message"]["content"],
        "finish": choice.get("finish_reason"),
        "wall_s": round(wall, 1),
        "prompt_tps": round(timings.get("prompt_per_second", 0), 2),
        "gen_tps": round(timings.get("predicted_per_second", 0), 2),
        "gen_tokens": timings.get("predicted_n"),
    }
    with open(args.out, "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f'{q["id"]}: {rec["gen_tokens"]} tok, {rec["gen_tps"]} tok/s, {rec["finish"]}', flush=True)
