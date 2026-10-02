#!/usr/bin/env bash
# A B C A: each from 30.5 C. A = the app's layout (5 threads, cpus 4-8: four A715 + the X3);
# B = four threads on the A715s only (cpus 4-7); C = four threads, X3 + three A715 (cpus 5-8).
source ~/androidlm-tools/env.sh
for cfg in "A1 1f0 5" "B 0f0 4" "C 1e0 4" "A2 1f0 5"; do
  set -- $cfg
  ~/androidlm-tools/wait_cool.sh 30.5 >/dev/null
  adb shell sh /data/local/tmp/bench_tune/sus_layout.sh $1 $2 $3
done
echo LAYOUTS_DONE
