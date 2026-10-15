#!/usr/bin/env bash
# Ask the app, on the phone, every question of a question file (JSONL with id and q) in one
# session, as a person would: the app is started once, and each later question goes to the running
# app (--activity-single-top), so the loaded model and the previous exchange carry over and a
# follow-up is a follow-up. The next question is asked PHONE_SESSION_GAP seconds (default 30) after
# the previous answer finished, with no waiting for the phone to cool.
# session.tsv gets one line per question: the skin temperature and battery level when it was asked,
# the skin temperature when it finished, and the app's and the engine's process ids and memory
# after it, so a restart or a crash shows as a changed id.
# A run is given up after PHONE_SESSION_TIMEOUT seconds (default 1200); the next question then
# starts the app afresh. Rerunning skips finished questions and goes on in the running app.
# usage: phone_session.sh <questions.jsonl> <out dir>
source ~/androidlm-tools/env.sh
Q=$1; OUT=$2; mkdir -p "$OUT"
GAP=${PHONE_SESSION_GAP:-30}
PKG=io.github.phineas1500.androidlm.dev
python3 -c "import json,sys; [print(json.loads(l)['id']+'\t'+json.loads(l)['q']) for l in open(sys.argv[1])]" "$Q" > "$OUT/questions.tsv"
adb logcat -G 16M </dev/null >/dev/null 2>&1
# the app's own end-of-run lines (an engine warning forwarded to the log can contain "failed");
# "phase=DONE" too, since the log has dropped a run's "completed" line under load
DONE="run=[0-9]+ t=[0-9]+ms (completed|failed|phase=DONE)"
skin() { adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
battery() { adb shell "dumpsys battery | grep -m1 ' level:'" </dev/null | tr -dc '0-9'; }
# pid and RSS (kB) of the app, then of the engine (the app's child processes)
procs() {
  adb shell 'app=$(pidof -s '$PKG'); echo "app=$app rss=$(ps -o RSS= -p $app 2>/dev/null | tr -d " ")";
    for p in $(ps -A -o PID=,PPID= | awk -v a="$app" "\$2==a{print \$1}"); do echo "child=$p rss=$(ps -o RSS= -p $p | tr -d " ") $(cat /proc/$p/cmdline | tr "\0" " " | cut -c1-60)"; done' </dev/null | tr '\n' ' '
}
[ -s "$OUT/session.tsv" ] || printf 'id\tasked\tskin_start\tbattery\tseconds\tskin_end\tresult\tprocesses\n' > "$OUT/session.tsv"
fresh=1
[ -n "$(adb shell pidof $PKG </dev/null)" ] && [ -s "$OUT/session.tsv" ] && [ "$(wc -l < "$OUT/session.tsv")" -gt 1 ] && fresh=0
while IFS=$'\t' read -r -u 3 id q; do
  [ -s "$OUT/$id.log" ] && grep -qE "$DONE" "$OUT/$id.log" && continue
  s0=$(skin); b0=$(battery)
  echo "##### $id start skin=$s0 battery=$b0 $(date +%T)"
  adb logcat -c </dev/null
  q_sh=${q//\'/\'\\\'\'}
  if [ $fresh = 1 ]; then flag=-S; else flag=--activity-single-top; fi
  adb shell "am start $flag -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q_sh'" </dev/null >/dev/null
  fresh=0
  start=$(date +%s); result=done
  until adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1 && grep -qE "$DONE" "$OUT/$id.log"; do
    [ $(( $(date +%s) - start )) -gt ${PHONE_SESSION_TIMEOUT:-1200} ] && { echo "TIMEOUT $id"; result=timeout; fresh=1; break; }
    sleep 5
  done
  secs=$(( $(date +%s) - start ))
  sleep 3; adb logcat -d -s AndroidLM:I </dev/null > "$OUT/$id.log" 2>&1   # the answer follows "completed"
  grep -qE "run=[0-9]+ t=[0-9]+ms failed" "$OUT/$id.log" && result=failed
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$(date -r $start +%T)" "$s0" "$b0" "$secs" "$(skin)" "$result" "$(procs)" >> "$OUT/session.tsv"
  grep -E "phase_done|completed|failed|rewr" "$OUT/$id.log" | sed -E "s/^.*AndroidLM: //" | cut -c1-160
  sleep "$GAP"
done 3< "$OUT/questions.tsv"
echo PHONE_SESSION_DONE
