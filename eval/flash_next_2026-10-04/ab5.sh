#!/usr/bin/env bash
# Flash-Next closed-book answers to the 12 red-team questions of 2026-09-28 (draft_reqs.jsonl: the
# app's closed-book prompt, greedy, up to 600 tokens) with 10, 8 and 6 experts per token, fn4.
source ~/androidlm-tools/env.sh
until grep -q INAPP1_DONE scratchpad/fn/inapp1.log 2>/dev/null; do sleep 20; done
CFG=${CFG:-"BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800"}
for k in 10 8 6; do
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell "$CFG EXTRA='--n-expert-used $k' sh /data/local/tmp/bench_fn2/fn_run.sh draft_k$k draft_reqs.jsonl 0" | head -2
  adb pull /data/local/tmp/bench_fn2/draft_k$k.out scratchpad/fn/ >/dev/null
done
echo AB5_DONE
