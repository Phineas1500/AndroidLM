#!/usr/bin/env bash
# In-app A/B of the dense path: 1.3.0 (A) against the dense-engine build (B), one question per cold
# start, each from 31 C, in the order A B B A per question pair.
source ~/androidlm-tools/env.sh
P=io.github.phineas1500.androidlm.dev; ACT=$P/io.bigmoeonedge.example.MainActivity
A=/Volumes/T7/AndroidLM-dev/androidlm-tools/assets-cache/androidlm-1.3.0.apk
B=/Volumes/T7/AndroidLM-dev/work/engine-dense-final/androidlm-dense-test.apk
O=${O:-$(dirname "$0")/app_ab}
REST="Tell me the best vegan restaurants in Lisbon"
CRY="Which signature algorithms are quantum resistant?"
cur=""
ask() { # label apk question
  local label=$1 apk=$2 q=$3
  if [ "$cur" != "$apk" ]; then adb install -r "$apk" >/dev/null && cur=$apk; fi
  ~/androidlm-tools/wait_cool.sh 31 >/dev/null
  adb logcat -c
  adb shell "am start -S -n $ACT --es research_question '$q'" >/dev/null
  local start=$(date +%s)
  until adb logcat -d -s AndroidLM:I > $O/$label.log 2>&1 && grep -qE "run=1 t=[0-9]+ms (completed|failed)" $O/$label.log; do
    [ $(( $(date +%s) - start )) -gt 900 ] && { echo "TIMEOUT $label"; break; }
    sleep 5
  done
  sleep 3; adb logcat -d -s AndroidLM:I > $O/$label.log 2>&1
  echo "=== $label"
  sed -E 's/^.*AndroidLM: //' $O/$label.log | grep -E "run=1 t=" | grep -E "route=|first_answer|phase_done=(ANSWERING|PLANNING)|completed|failed" | cut -c1-150
}
ask restA1 $A "$REST"; ask restB1 $B "$REST"; ask restB2 $B "$REST"; ask restA2 $A "$REST"
ask cryA1 $A "$CRY"; ask cryB1 $B "$CRY"; ask cryB2 $B "$CRY"; ask cryA2 $A "$CRY"
adb install -r $A >/dev/null; adb shell am force-stop $P
echo APP_AB_DONE
