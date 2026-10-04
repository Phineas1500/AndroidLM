#!/usr/bin/env bash
# Flash-Next: the dense kernels (fn3) against fn2 on the places prompt, then a profile of fn3 on the
# 1,216-token prompt.
source ~/androidlm-tools/env.sh
for cfg in "fn3a /data/local/tmp/bmoe-fn3" "fn2b /data/local/tmp/bmoe-fn2" "fn3b /data/local/tmp/bmoe-fn3"; do
  set -- $cfg
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell "BIN=$2 sh /data/local/tmp/bench_fn2/fn_run.sh $1 places_req.jsonl 0"
done
~/androidlm-tools/wait_cool.sh 32 >/dev/null
adb shell "BIN=/data/local/tmp/bmoe-fn3 sh /data/local/tmp/bench_fn2/fn_run.sh fn3prof prefill_req.jsonl 120"
adb shell 'simpleperf report -i /data/local/tmp/bench_fn2/fn3prof.perf --sort dso,symbol --percent-limit 0.3 2>/dev/null | tail -n +8 | head -45'
echo AB2_DONE
