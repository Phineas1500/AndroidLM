#!/usr/bin/env bash
# Demo v2: four takes on the current build, phone in airplane mode; settings restored at the end.
source ~/androidlm-tools/env.sh
restore() {
  adb shell "pkill -INT screenrecord; cmd connectivity airplane-mode disable; sleep 2; cmd wifi set-wifi-enabled enabled; cmd bluetooth_manager enable; cmd notification set_dnd off; settings put system show_touches 0; settings put system accelerometer_rotation 1; echo restored: airplane=\$(settings get global airplane_mode_on) wifi=\$(settings get global wifi_on) bt=\$(settings get global bluetooth_on) zen=\$(settings get global zen_mode) touches=\$(settings get system show_touches) accel=\$(settings get system accelerometer_rotation)" </dev/null
}
trap restore EXIT
adb shell "cmd connectivity airplane-mode enable; sleep 2; cmd wifi set-wifi-enabled disabled; cmd bluetooth_manager disable; cmd notification set_dnd on; settings put system show_touches 1; settings put system accelerometer_rotation 0; settings put system user_rotation 0; sleep 2; echo demo: airplane=\$(settings get global airplane_mode_on) wifi=\$(settings get global wifi_on) bt=\$(settings get global bluetooth_on) zen=\$(settings get global zen_mode)" </dev/null
date +%s > ~/androidlm-tools/demo2/last_take_end
for t in t1_harrods t2_bigmotor t3_pinkfloyd t4_leh; do
  ~/androidlm-tools/take.sh v2_$t $t.sh || echo "TAKE FAILED $t"
done
echo RECORD_V2_DONE
