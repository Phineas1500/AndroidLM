#!/usr/bin/env bash
# Demo takes on the current build, phone in airplane mode; settings restored at the end.
# Usage: record.sh <prefix> <out dir> <question script>...  (scripts in /data/local/tmp, no .sh)
#   v2: record.sh v2 ~/androidlm-tools/demo2 t1_harrods t2_bigmotor t3_pinkfloyd t4_leh
#   v3: record.sh v3 ~/androidlm-tools/demo3 t1_harrods t2_bigmotor t3_light t4_leh
source ~/androidlm-tools/env.sh
PREFIX=$1; export DEMO_OUT=$2; shift 2
mkdir -p "$DEMO_OUT"
restore() {
  adb shell "pkill -INT screenrecord; cmd connectivity airplane-mode disable; sleep 2; cmd wifi set-wifi-enabled enabled; cmd bluetooth_manager enable; cmd notification set_dnd off; settings put system show_touches 0; settings put system accelerometer_rotation 1; echo restored: airplane=\$(settings get global airplane_mode_on) wifi=\$(settings get global wifi_on) bt=\$(settings get global bluetooth_on) zen=\$(settings get global zen_mode) touches=\$(settings get system show_touches) accel=\$(settings get system accelerometer_rotation)" </dev/null
}
trap restore EXIT
adb shell "cmd connectivity airplane-mode enable; sleep 2; cmd wifi set-wifi-enabled disabled; cmd bluetooth_manager disable; cmd notification set_dnd on; settings put system show_touches 1; settings put system accelerometer_rotation 0; settings put system user_rotation 0; sleep 2; echo demo: airplane=\$(settings get global airplane_mode_on) wifi=\$(settings get global wifi_on) bt=\$(settings get global bluetooth_on) zen=\$(settings get global zen_mode)" </dev/null
date +%s > "$DEMO_OUT/last_take_end"
for t in "$@"; do
  ~/androidlm-tools/take.sh "${PREFIX}_$t" "$t.sh" || echo "TAKE FAILED $t"
done
echo RECORD_DONE
