#!/usr/bin/env bash
# Ask one question with the model already loaded: open the app (it loads the model), wait for the
# load to finish, then send the question to the running app (--activity-single-top), which avoids
# the cold-start race that dropped some questions. Args: <id> <out dir> <question> [lat lon]
source ~/androidlm-tools/env.sh
id=$1; OUT=$2; q=$3; lat=${4:-}; lon=${5:-}
PKG=io.github.phineas1500.androidlm.dev; ACT=$PKG/io.bigmoeonedge.example.MainActivity
DONE="run=[0-9]+ t=[0-9]+ms (completed|failed)"
~/androidlm-tools/wait_cool.sh 32 >/dev/null
adb shell am force-stop $PKG; adb logcat -c
adb shell am start -n $ACT >/dev/null
# loaded: the engine prints BMOE_READY to the app, which shows "loaded"; wait for the session to be idle
sleep 5
for i in $(seq 1 60); do
  adb shell dumpsys activity services $PKG 2>/dev/null | grep -q "RunService" || true
  adb logcat -d | grep -qE "BMOE_READY|model loaded|Loaded" && break
  sleep 3
done
sleep 75   # the load itself takes about 66 s for this build
[ -n "$lat" ] && adb shell cmd location providers set-test-provider-location gps --location "$lat,$lon"
q_sh=${q//\'/\'\\\'\'}
adb shell "am start --activity-single-top -n $ACT --es research_question '$q_sh'" >/dev/null
start=$(date +%s)
until adb logcat -d -s AndroidLM:I > "$OUT/$id.log" 2>&1 && grep -qE "$DONE" "$OUT/$id.log"; do
  [ $(( $(date +%s) - start )) -gt 2400 ] && { echo "TIMEOUT $id"; break; }
  [ -n "$lat" ] && adb shell cmd location providers set-test-provider-location gps --location "$lat,$lon"
  sleep 10
done
sleep 3; adb logcat -d -s AndroidLM:I > "$OUT/$id.log" 2>&1
echo "##### $id $(grep -oE 'run=[0-9]+ t=[0-9]+ms (completed|failed)' "$OUT/$id.log" | head -1)"
