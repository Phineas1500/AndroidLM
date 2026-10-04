#!/usr/bin/env bash
# Flash-Next: the single-token Q2_0 kernel (fn4) against fn3 writing 64 tokens; memory headroom
# of a bigger cache with the smaller compute buffer; the long prompt at ubatch 512; and the speed
# with fewer experts per token (8, 6).
source ~/androidlm-tools/env.sh
until grep -q AB3_DONE scratchpad/fn/ab3.log; do sleep 20; done
adb push scratchpad/fn/fn_run.sh /data/local/tmp/bench_fn2/ >/dev/null
run() { ~/androidlm-tools/wait_cool.sh 32 >/dev/null; adb shell "$1 sh /data/local/tmp/bench_fn2/fn_run.sh $2 $3 0"; }
run "BIN=/data/local/tmp/bmoe-fn4" d4a decode_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn3" d3a decode_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4" d4b decode_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800" m2800 decode_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=3300" m3300 decode_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800" p512 prefill_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800 EXTRA='--n-expert-used 8'" k8 places_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800 EXTRA='--n-expert-used 6'" k6 places_req.jsonl
run "BIN=/data/local/tmp/bmoe-fn4 UB=512 CACHE=2800" k10 places_req.jsonl
echo AB4_DONE
