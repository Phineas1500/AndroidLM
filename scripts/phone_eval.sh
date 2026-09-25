#!/usr/bin/env bash
# Ask the app, on the phone, every question of a question file (JSONL with id and q), one at a
# time through the dev-only research_question extra, and keep each run's AndroidLM log.
# Each run starts at 30.5 C or 4 minutes after the previous one ended, whichever comes first.
# usage: phone_eval.sh <questions.jsonl> <out dir>
source ~/androidlm-tools/env.sh
Q=$1; OUT=$2; mkdir -p "$OUT"
PKG=io.github.phineas1500.androidlm.dev
python3 -c "import json,sys; [print(json.loads(l)['id']+'\t'+json.loads(l)['q']) for l in open(sys.argv[1])]" "$Q" > "$OUT/questions.tsv"
LAST=0
while IFS=$'\t' read -r -u 3 id q; do
  [ -s "$OUT/$id.log" ] && grep -q "completed\|failed" "$OUT/$id.log" && continue   # resumable
  while true; do
    t=$(adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/")
    awk -v t="$t" "BEGIN{exit !(t>0 && t<=30.5)}" && break
    [ $(( $(date +%s) - LAST )) -ge 240 ] && break
    sleep 15
  done
  echo "##### $id start skin=$t $(date +%T)"
  adb logcat -c </dev/null
  adb logcat -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1 &
  cap=$!
  q_sh=${q//\'/\'\\\'\'}
  adb shell "am start -S -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q_sh'" </dev/null >/dev/null
  start=$(date +%s)
  until grep -qE "completed|failed" "$OUT/$id.log"; do
    [ $(( $(date +%s) - start )) -gt 1200 ] && { echo "TIMEOUT $id"; break; }
    sleep 5
  done
  sleep 3; kill $cap 2>/dev/null
  LAST=$(date +%s)
  grep -E "phase_done|completed|failed" "$OUT/$id.log" | sed -E "s/^.*AndroidLM: //" | cut -c1-160
done 3< "$OUT/questions.tsv"
adb shell "am force-stop $PKG" </dev/null
echo PHONE_EVAL_DONE
