#!/usr/bin/env bash
# One demo take on the current app screen: $1 = take name, $2 = question script on the phone (types
# the question). Screen recorded to /sdcard/Movies/<name>.mp4 and pulled to ~/androidlm-tools/demo2/,
# with the app's phase log beside it. The app follows the answer itself, so nothing scrolls during a run.
source ~/androidlm-tools/env.sh
NAME=$1; Q=$2
PKG=io.github.phineas1500.androidlm.dev
OUT=~/androidlm-tools/demo2; mkdir -p $OUT
node() {  # centre of the first node whose text is $1 (or whose class contains $1 when $2 = class)
  adb shell "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml" </dev/null > /tmp/take_ui.xml
  python3 - "$1" "${2:-text}" <<PY
import re, sys
s = open("/tmp/take_ui.xml").read()
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", n).group(1)
    c = re.search(r"class=\"([^\"]*)\"", n).group(1)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
    if (sys.argv[2] == "class" and sys.argv[1] in c) or (sys.argv[2] == "text" and t == sys.argv[1]):
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
totop() { for i in 1 2 3 4 5; do adb shell "input swipe 540 600 540 2000 150" </dev/null; sleep 0.5; done; sleep 1; }
LAST=$(cat $OUT/last_take_end 2>/dev/null || echo 0)
while true; do
  t=$(adb shell "dumpsys thermalservice | sed -n \"/Current temperatures/,\\\$p\" | grep -m1 \"mName=VIRTUAL-SKIN,\"" </dev/null | sed -E "s/.*mValue=([0-9.]+).*/\1/")
  awk -v t="$t" "BEGIN{exit !(t>0 && t<=30.5)}" && break
  [ $(( $(date +%s) - LAST )) -ge 540 ] && break
  sleep 15
done
echo "start skin=$t"
adb shell "am start -n $PKG/io.bigmoeonedge.example.MainActivity" </dev/null >/dev/null; sleep 3
adb shell "dumpsys window | grep -m1 mCurrentFocus" </dev/null | grep -q androidlm || { echo "app not in front; abort"; exit 1; }
totop
C=$(node "Clear the answer"); [ -n "$C" ] && adb shell "input tap $C" </dev/null && sleep 1 && totop
E=$(node EditText class); [ -n "$E" ] || { echo "no question box; abort"; exit 1; }
adb logcat -c </dev/null
adb logcat -s AndroidLM:I </dev/null > $OUT/$NAME.log 2>&1 &
cap=$!
adb shell "rm -f /sdcard/Movies/$NAME.mp4; nohup screenrecord --time-limit 0 --bit-rate 6000000 /sdcard/Movies/$NAME.mp4 >/dev/null 2>&1 &" </dev/null
sleep 3
adb shell "cmd statusbar expand-settings" </dev/null; sleep 4
adb shell "cmd statusbar collapse" </dev/null; sleep 2
adb shell "input tap $E; sleep 1; sh /data/local/tmp/$Q" </dev/null; sleep 2
adb shell "dumpsys input_method | grep -q mInputShown=true && input keyevent 4" </dev/null; sleep 1
adb shell "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml" </dev/null > /tmp/take_ui.xml
# the switch label is also "Research": the button is the last node with that text
R=$(python3 - <<PY
import re
s = open("/tmp/take_ui.xml").read()
hit = None
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0); t = re.search(r"text=\"([^\"]*)\"", n).group(1)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
    if t == "Research": hit = ((x1 + x2) // 2, (y1 + y2) // 2)
if hit: print(hit[0], hit[1])
PY
)
[ -n "$R" ] || { echo "Research button not found; abort"; adb shell "pkill -INT screenrecord" </dev/null; kill $cap; exit 1; }
adb shell "input tap $R" </dev/null; echo "tapped Research $R"
T0=$(date +%s)
until grep -qE "completed|failed" $OUT/$NAME.log; do sleep 5; done
echo "research done after $(( $(date +%s) - T0 )) s"
sleep 5
for i in 1 2 3; do adb shell "input swipe 540 900 540 1600 1500" </dev/null; sleep 3; done   # read back up the answer
sleep 2
adb shell "pkill -INT screenrecord" </dev/null; sleep 4
kill $cap 2>/dev/null
date +%s > $OUT/last_take_end
adb pull /sdcard/Movies/$NAME.mp4 $OUT/ </dev/null | tail -1
grep -E "phase_done|first_answer|first_check|route=|completed" $OUT/$NAME.log | sed -E "s/^.*AndroidLM: //"
echo TAKE_DONE
