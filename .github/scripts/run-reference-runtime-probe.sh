#!/usr/bin/env bash
set -euo pipefail

EVIDENCE_DIR="${GITHUB_WORKSPACE}/reference-evidence"
mkdir -p "${EVIDENCE_DIR}"
cleanup() {
  STATUS=$?
  trap - EXIT
  adb logcat -d -v threadtime > "${EVIDENCE_DIR}/reference-logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "${EVIDENCE_DIR}/reference-activity-stack.txt" 2>/dev/null || true
  adb shell ps -A > "${EVIDENCE_DIR}/reference-processes.txt" 2>/dev/null || true
  adb exec-out screencap -p > "${EVIDENCE_DIR}/reference-screen.png" 2>/dev/null || true
  exit "${STATUS}"
}
trap cleanup EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_animation_scale 0 || true
adb shell wm dismiss-keyguard
adb logcat -c
adb shell getprop ro.product.cpu.abilist | tee "${EVIDENCE_DIR}/reference-emulator-abis.txt"

adb install-multiple -r -t \
  .github/reference/original.apk .github/reference/split_config.xhdpi.apk
adb shell pm list packages | grep '^package:com.multibox.clone.hub$'
adb shell am start -W -S -n com.multibox.clone.hub/com.multibox.clone.hub.MainActivity \
  > "${EVIDENCE_DIR}/reference-launch.txt" 2>&1 || true
sleep 15
adb shell content call --uri \
  'content://com.multibox.clone.hub.com.multibox.clone.hub.Jrhl$Service' \
  --method 995 > "${EVIDENCE_DIR}/reference-provider-call.txt" 2>&1 || true
sleep 10
cat "${EVIDENCE_DIR}/reference-launch.txt"
cat "${EVIDENCE_DIR}/reference-provider-call.txt"
