#!/usr/bin/env bash
# Measures every Pixel emulator profile with ProbeActivity and writes one
# JSON object per device (gesture and 3-button navigation) to $OUT.
# Usage: scripts/probe-pixels.sh [out.jsonl] [api-image]
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator
AVDM=$SDK/cmdline-tools/latest/bin/avdmanager
OUT=${1:-probe-pixels.jsonl}
IMAGE=${2:-"system-images;android-37.0;google_apis;arm64-v8a"}
APK=$(dirname "$0")/../sample/build/outputs/apk/debug/sample-debug.apk
DEVICES=(pixel_6 pixel_6_pro pixel_6a pixel_7 pixel_7_pro pixel_7a pixel_8 pixel_8_pro pixel_8a pixel_9 pixel_9_pro pixel_9_pro_xl pixel_9a pixel_10 pixel_10_pro pixel_10_pro_xl)
: > "$OUT"
$ADB emu kill >/dev/null 2>&1 || true
sleep 3
for d in "${DEVICES[@]}"; do
  name=a8probe_$d
  echo no | $AVDM create avd -n "$name" -k "$IMAGE" -d "$d" --force >/dev/null 2>&1
  $EMU -avd "$name" -no-window -no-audio -no-boot-anim -no-snapshot -gpu host >/dev/null 2>&1 &
  $ADB wait-for-device
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  sleep 4
  $ADB install -r "$APK" >/dev/null
  for nav in gesture threebutton; do
    if [ $nav = threebutton ]; then
      $ADB shell cmd overlay enable com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1 || true
      sleep 4
    fi
    $ADB logcat -c
    $ADB shell am force-stop dev.app8.sample
    $ADB shell am start -W -n dev.app8.sample/.ProbeActivity >/dev/null
    sleep 2
    line=$($ADB logcat -d -s A8Probe:I | grep -o '{.*}' | tail -1)
    echo "{\"profile\":\"$d\",\"nav\":\"$nav\",\"probe\":$line}" >> "$OUT"
    echo "$d $nav ok"
  done
  $ADB emu kill >/dev/null 2>&1 || true
  sleep 5
  $AVDM delete avd -n "$name" >/dev/null 2>&1 || true
done
