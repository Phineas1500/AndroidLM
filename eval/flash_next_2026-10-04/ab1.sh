#!/usr/bin/env bash
# Flash-Next on the phone: the shipped engine against the Q2_0 + BF16 kernels (426-token places
# prompt, 32 tokens written), then a profile of the new engine on the 1,216-token prompt.
source ~/androidlm-tools/env.sh
adb shell 'mv /data/local/tmp/fnext/*.gguf /data/local/tmp/bmoe/ && rmdir /data/local/tmp/fnext; ls -l /data/local/tmp/bmoe'
for cfg in "ship1 /data/local/tmp/bmoe-dfinal" "fn2a /data/local/tmp/bmoe-fn2"; do
  set -- $cfg
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell "BIN=$2 sh /data/local/tmp/bench_fn2/fn_run.sh $1 places_req.jsonl 0"
done
~/androidlm-tools/wait_cool.sh 32 >/dev/null
adb shell "BIN=/data/local/tmp/bmoe-fn2 sh /data/local/tmp/bench_fn2/fn_run.sh fn2prof prefill_req.jsonl 150"
adb shell 'simpleperf report -i /data/local/tmp/bench_fn2/fn2prof.perf --sort dso,symbol --percent-limit 0.3 2>/dev/null | head -50'
echo AB1_DONE
