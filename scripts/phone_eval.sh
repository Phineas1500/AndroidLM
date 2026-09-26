#!/usr/bin/env bash
# Ask the app, on the phone, every question of a question file (JSONL with id and q), one at a
# time through the dev-only research_question extra, and keep each run's AndroidLM log.
# Each run starts at 30.5 C or 4 minutes after the previous one ended, whichever comes first.
# usage: phone_eval.sh <questions.jsonl> <out dir>
source ~/androidlm-tools/env.sh
Q=$1; OUT=$2; mkdir -p "$OUT"
PKG=io.github.phineas1500.androidlm.dev
python3 -c "import json,sys; [print(json.loads(l)['id']+'\t'+json.loads(l)['q']) for l in open(sys.argv[1])]" "$Q" > "$OUT/questions.tsv"
# The run's log is read back from the phone's own buffer, large enough for a whole run: a live
# `adb logcat` stream once dropped a run's last lines (completed and the answer) while the
# buffer still held them.
adb logcat -G 16M </dev/null >/dev/null 2>&1
# the app's own end-of-run line (an engine warning forwarded to the log can contain "failed")
DONE="run=[0-9]+ t=[0-9]+ms (completed|failed)"
LAST=0
while IFS=$'\t' read -r -u 3 id q; do
  [ -s "$OUT/$id.log" ] && grep -qE "$DONE" "$OUT/$id.log" && continue   # resumable
  while true; do
    t=$(adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/")
    awk -v t="$t" "BEGIN{exit !(t>0 && t<=30.5)}" && break
    [ $(( $(date +%s) - LAST )) -ge 240 ] && break
    sleep 15
  done
  echo "##### $id start skin=$t $(date +%T)"
  adb logcat -c </dev/null
  q_sh=${q//\'/\'\\\'\'}
  adb shell "am start -S -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q_sh'" </dev/null >/dev/null
  start=$(date +%s)
  until adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1 && grep -qE "$DONE" "$OUT/$id.log"; do
    [ $(( $(date +%s) - start )) -gt 1200 ] && { echo "TIMEOUT $id"; break; }
    sleep 10
  done
  sleep 3; adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1   # the answer follows "completed"
  LAST=$(date +%s)
  grep -E "phase_done|completed|failed" "$OUT/$id.log" | sed -E "s/^.*AndroidLM: //" | cut -c1-160
done 3< "$OUT/questions.tsv"
adb shell "am force-stop $PKG" </dev/null
echo PHONE_EVAL_DONE
