#!/usr/bin/env bash
# The three "near me" questions of eval/questions_vitalik.jsonl with a test GPS position on the phone (Flash-Next),
# each from a cold app start; restores the phone's location settings afterwards.
source ~/androidlm-tools/env.sh
PKG=io.github.phineas1500.androidlm.dev
mkdir -p /Volumes/T7/AndroidLM-dev/work/eval-fn-vitalik
OUT=/Volumes/T7/AndroidLM-dev/work/eval-fn-vitalik
SP=scratchpad
DONE="run=[0-9]+ t=[0-9]+ms (completed|failed)"
WAS_ON=$(adb shell cmd location is-location-enabled | tr -d "\r")
adb shell cmd location set-location-enabled true
adb shell appops set com.android.shell android:mock_location allow
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell cmd location providers add-test-provider gps
adb shell cmd location providers set-test-provider-enabled gps true
ask() {  # id lat lon question
  local id=$1 lat=$2 lon=$3 q=$4
  ~/androidlm-tools/wait_cool.sh 32 >/dev/null
  adb shell cmd location providers set-test-provider-location gps --location "$lat,$lon"
  adb logcat -c
  adb shell "am start -S -n $PKG/io.bigmoeonedge.example.MainActivity --es research_question '$q'" >/dev/null
  local start=$(date +%s)
  until adb logcat -d -s AndroidLM:I > "$OUT/$id.log" 2>&1 && grep -qE "$DONE" "$OUT/$id.log"; do
    [ $(( $(date +%s) - start )) -gt 2400 ] && { echo "TIMEOUT $id"; break; }
    adb shell cmd location providers set-test-provider-location gps --location "$lat,$lon"
    sleep 5
  done
  sleep 3; adb logcat -d -s AndroidLM:I > "$OUT/$id.log" 2>&1
  echo "##### $id $(grep -oE 'places=[0-9]+ here=[a-z]+ where=.*' "$OUT/$id.log" | head -1)"
}
ask food-018 41.3874 2.1686 "Tell me the best vegan restaurants in the city I am currently in"
ask food-019 22.2819 114.1582 "Tell me the best vegan restaurants in the city I am currently in"
ask food-020 39.7392 -104.9903 "Tell me the best vegan restaurants near me"
adb shell am force-stop $PKG
adb shell cmd location providers remove-test-provider gps
adb shell pm revoke $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm revoke $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell appops set com.android.shell android:mock_location default
[ "$WAS_ON" = "false" ] && adb shell cmd location set-location-enabled false
echo LOCATED_DONE
