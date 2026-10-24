#!/usr/bin/env bash
# The first question of a session as a person asks it: the app is opened (it loads the model, and
# from 1.8.5 reads the first steps' instructions into the prefix cache), PHONE_FIRST_WAIT seconds
# later (default 90) the question is sent to the open app. One fresh app per question, each from a
# phone at PHONE_FIRST_COOL C (default 31.5) or after PHONE_FIRST_MAXWAIT seconds (default 600).
# With PHONE_FIRST_WARM=0 the warm-up is turned off (ANDROIDLM_WARM=0 in the dev env file), so the
# same APK gives both arms of an A/B. Each run's AndroidLM log goes to <out>/<id>.log, as
# phone_eval.sh's (scripts/phone_eval_parse.py reads them).
# usage: phone_first_question.sh <questions.jsonl> <out dir>
source ~/androidlm-tools/env.sh
Q=$1; OUT=$2; mkdir -p "$OUT"
PKG=io.github.phineas1500.androidlm.dev
ENVF=/data/local/tmp/androidlm-engine.env
WAIT=${PHONE_FIRST_WAIT:-90}; COOL=${PHONE_FIRST_COOL:-31.5}; MAXWAIT=${PHONE_FIRST_MAXWAIT:-600}
python3 -c "import json,sys; [print(json.loads(l)['id']+'\t'+json.loads(l)['q']) for l in open(sys.argv[1])]" "$Q" > "$OUT/questions.tsv"
adb logcat -G 16M </dev/null >/dev/null 2>&1
DONE="run=[0-9]+ t=[0-9]+ms (completed|failed|phase=DONE)"
skin() { adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
# the dev env file without our switch, plus the switch when it is off (put back as it was at the end)
had=$(adb shell "[ -e $ENVF ] && echo yes" </dev/null)
adb shell "touch $ENVF; grep -v '^ANDROIDLM_WARM=' $ENVF > $ENVF.tmp; mv $ENVF.tmp $ENVF; chmod 644 $ENVF" </dev/null
[ "${PHONE_FIRST_WARM:-1}" = 0 ] && adb shell "echo ANDROIDLM_WARM=0 >> $ENVF" </dev/null
while IFS=$'\t' read -r -u 3 id q; do
  [ -s "$OUT/$id.log" ] && grep -qE "$DONE" "$OUT/$id.log" && continue
  adb shell am force-stop $PKG </dev/null
  waited=0
  until awk -v s="$(skin)" -v c="$COOL" 'BEGIN { exit !(s <= c) }' || [ $waited -ge $MAXWAIT ]; do sleep 20; waited=$((waited + 20)); done
  s0=$(skin)
  echo "##### $id open skin=$s0 $(date +%T)"
  adb logcat -c </dev/null
  adb shell "am start -S -n $PKG/io.bigmoeonedge.example.MainActivity" </dev/null >/dev/null
  sleep "$WAIT"
  q_sh=${q//\'/\'\\\'\'}
  adb shell "am start --activity-single-top -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q_sh'" </dev/null >/dev/null
  start=$(date +%s)
  until adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1 && grep -qE "$DONE" "$OUT/$id.log"; do
    [ $(( $(date +%s) - start )) -gt 1200 ] && { echo "TIMEOUT $id"; break; }
    sleep 5
  done
  sleep 3; adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1
  echo "skin_start=$s0 skin_end=$(skin)" >> "$OUT/$id.log"
  grep -E "warm prefix|first_answer_token|phase_done|completed" "$OUT/$id.log" | sed -E "s/^.*AndroidLM: //" | cut -c1-160
done 3< "$OUT/questions.tsv"
if [ -n "$had" ]; then adb shell "grep -v '^ANDROIDLM_WARM=' $ENVF > $ENVF.tmp; mv $ENVF.tmp $ENVF; chmod 644 $ENVF" </dev/null
else adb shell "rm -f $ENVF" </dev/null; fi
echo PHONE_FIRST_DONE
