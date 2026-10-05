#!/usr/bin/env bash
source ~/androidlm-tools/env.sh
PKG=io.github.phineas1500.androidlm.dev
bash scratchpad/fn/warm_ask.sh cry-004 /Volumes/T7/AndroidLM-dev/work/eval-fn-iq3-rest "What is EIP-4844 and what are blobs in Ethereum?"
WAS_ON=$(adb shell cmd location is-location-enabled | tr -d "\r")
adb shell cmd location set-location-enabled true
adb shell appops set com.android.shell android:mock_location allow
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell cmd location providers add-test-provider gps
adb shell cmd location providers set-test-provider-enabled gps true
adb shell cmd location providers set-test-provider-location gps --location "41.3874,2.1686"
bash scratchpad/fn/warm_ask.sh food-018 /Volumes/T7/AndroidLM-dev/work/eval-fn-iq3-rest "Tell me the best vegan restaurants in the city I am currently in" 41.3874 2.1686
adb shell am force-stop $PKG
adb shell cmd location providers remove-test-provider gps
adb shell pm revoke $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm revoke $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell appops set com.android.shell android:mock_location default
[ "$WAS_ON" = "false" ] && adb shell cmd location set-location-enabled false
echo LAST2_DONE
