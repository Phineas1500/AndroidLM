#!/usr/bin/env bash
# each run from 32 C (the phone, charging at 100%, no longer cools to 30.5 in reasonable time)
source ~/androidlm-tools/env.sh
for cfg in "i8mm1 /data/local/tmp/bmoe-dense4" "dot1 /data/local/tmp/bmoe-dense4 GGML_IQK_DENSE_I8MM=0" "i8mm2 /data/local/tmp/bmoe-dense4" "dot2 /data/local/tmp/bmoe-dense4 GGML_IQK_DENSE_I8MM=0" "ship5 /data/local/tmp/bmoe-prefix"; do
  set -- $cfg
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell sh /data/local/tmp/bench_tune/prefill_ab.sh "$@"
done
echo AB4_DONE
