#!/usr/bin/env bash
# Flash-Next writing: a profile of fn3 writing 64 tokens after a short prompt; then a smaller
# compute buffer (ubatch 512) for a bigger cache (2800 MiB) against the defaults, places prompt.
source ~/androidlm-tools/env.sh
adb push scratchpad/fn/fn_run.sh /data/local/tmp/bench_fn2/ >/dev/null
~/androidlm-tools/wait_cool.sh 32 >/dev/null
adb shell "BIN=/data/local/tmp/bmoe-fn3 sh /data/local/tmp/bench_fn2/fn_run.sh fn3dec decode_req.jsonl 100"
adb shell 'simpleperf report -i /data/local/tmp/bench_fn2/fn3dec.perf --sort dso,symbol --percent-limit 0.4 2>/dev/null | tail -n +8 | head -45'
for cfg in "c2800 512 2800" "c2000 1280 2000" "c2800b 512 2800"; do
  set -- $cfg
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell "BIN=/data/local/tmp/bmoe-fn3 UB=$2 CACHE=$3 sh /data/local/tmp/bench_fn2/fn_run.sh $1 places_req.jsonl 0"
done
echo AB3_DONE
