#!/usr/bin/env bash
# Time research questions in the AndroidLM dev app over adb. One question per line of $1.
# Uses the dev-only research_question extra and the AndroidLM logcat tag. adb reads stdin, so the
# question list is read from fd 3 and every adb call gets </dev/null.
source ~/androidlm-tools/env.sh
PKG=io.github.phineas1500.androidlm.dev
adb logcat -G 16M </dev/null >/dev/null 2>&1
while IFS= read -r -u 3 q; do
  [ -n "$q" ] || continue
  echo "##### $q"
  adb logcat -c </dev/null
  adb logcat -s AndroidLM:I </dev/null > /tmp/androidlm_run.log 2>&1 &
  cap=$!
  start=$(date +%s)
  q_sh=${q//\'/\'\\\'\'}   # quote for the phone's shell: ' becomes '\''
  adb shell "am start -S -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q_sh'" </dev/null >/dev/null
  until grep -qE "completed|failed" /tmp/androidlm_run.log; do
    [ $(( $(date +%s) - start )) -gt 1500 ] && { echo "TIMEOUT"; break; }
    sleep 5
  done
  sleep 2; kill $cap 2>/dev/null
  echo "wall from am start: $(( $(date +%s) - start )) s"
  sed -E 's/^.*AndroidLM: //' /tmp/androidlm_run.log | grep -v "beginning of" | cut -c1-950
  sleep 15
done 3< "$1"
echo TIMING_DONE
