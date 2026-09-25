#!/usr/bin/env bash
# One demo take: $1 = take name, $2 = question script on the phone (q*_*.sh). Screen recorded to
# /sdcard/Movies/<name>.mp4 and pulled to ~/androidlm-tools/demo/. Writes a phase log beside it.
# Runs on the computer the phone is attached to (adb over USB). Before the takes: airplane mode on
# (Wi-Fi and Bluetooth off), Do Not Disturb, "show touches", portrait lock. A question script is
#   input tap 504 743; input keycombination 113 29; input keyevent 67
#   input text "<the question with %s for each space>"
# (it clears the prompt box, whose position is for a Pixel 8 Pro in portrait, and types).
source ~/androidlm-tools/env.sh
NAME=$1; Q=$2
PKG=io.github.phineas1500.androidlm.dev
OUT=~/androidlm-tools/demo
tapfor() {  # tap the centre of the node whose text is $1 and whose top is below $2
  adb shell "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml" </dev/null > /tmp/take_ui.xml
  python3 - "$1" "$2" <<PY
import re, subprocess, sys
s = open("/tmp/take_ui.xml").read()
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", n).group(1)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
    if t == sys.argv[1] and y1 > int(sys.argv[2]):
        subprocess.run(["adb", "shell", f"input tap {(x1+x2)//2} {(y1+y2)//2}"], stdin=subprocess.DEVNULL)
        print("tapped", t, (x1+x2)//2, (y1+y2)//2); break
else:
    print("NOT FOUND", sys.argv[1])
PY
}
# Start at 30.5 C or 9 minutes after the previous take ended, whichever comes first: the app unloads
# the model after 10 idle minutes, and a take should not spend its first 30 s reloading it.
LAST=$(cat ~/androidlm-tools/demo/last_take_end 2>/dev/null || echo 0)
while true; do
  t=$(adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/")
  awk -v t="$t" "BEGIN{exit !(t>0 && t<=30.5)}" && break
  [ $(( $(date +%s) - LAST )) -ge 540 ] && break
  sleep 15
done
echo "start skin=$t"
totop() { for i in 1 2 3 4 5; do adb shell "input swipe 540 600 540 2000 150" </dev/null; sleep 0.5; done; sleep 1; }
adb shell "am start -n $PKG/io.bigmoeonedge.example.MainActivity" </dev/null >/dev/null; sleep 3
adb shell "dumpsys window | grep -m1 mCurrentFocus" </dev/null | grep -q androidlm || { echo "app not in front; abort"; exit 1; }
totop
tapfor "New chat" 1000; sleep 2
totop
adb logcat -c </dev/null
adb logcat -s AndroidLM:I </dev/null > $OUT/$NAME.log 2>&1 &
cap=$!
adb shell "rm -f /sdcard/Movies/$NAME.mp4; nohup screenrecord --time-limit 0 --bit-rate 6000000 /sdcard/Movies/$NAME.mp4 >/dev/null 2>&1 &" </dev/null
sleep 3
adb shell "cmd statusbar expand-settings" </dev/null; sleep 4
adb shell "cmd statusbar collapse" </dev/null; sleep 2
adb shell "sh /data/local/tmp/$Q" </dev/null; sleep 2
adb shell "dumpsys input_method | grep -q mInputShown=true && input keyevent 4" </dev/null; sleep 1
if ! tapfor "Research" 1000 | tee /dev/stderr | grep -q tapped; then
  echo "Research button not found; abort"
  adb shell "pkill -INT screenrecord" </dev/null; kill $cap 2>/dev/null; exit 1
fi
T0=$(date +%s)
sleep 4
# keep the newest content on screen while the research runs (the app does not follow it)
until grep -qE "completed|failed" $OUT/$NAME.log; do adb shell "input swipe 540 1700 540 700 400" </dev/null; sleep 8; done
echo "research done after $(( $(date +%s) - T0 )) s"
sleep 4
for i in 1 2 3 4 5 6; do adb shell "input swipe 540 1800 540 1100 1500" </dev/null; sleep 3; done
sleep 2
adb shell "pkill -INT screenrecord" </dev/null; sleep 4
kill $cap 2>/dev/null
date +%s > ~/androidlm-tools/demo/last_take_end
adb pull /sdcard/Movies/$NAME.mp4 $OUT/ </dev/null | tail -1
grep -E "phase_done|first_answer|first_check|route=|completed" $OUT/$NAME.log | sed -E "s/^.*AndroidLM: //"
echo TAKE_DONE
