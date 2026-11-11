#!/usr/bin/env bash
set -euo pipefail

OUT=fixture-evidence
mkdir -p "$OUT"

cleanup() {
  status=$?
  trap - EXIT
  adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/activity-stack.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$OUT/device-screen.png" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT

adb wait-for-device
adb install -r -t artifacts/app-debug-androidTest.apk
adb shell am start -W -S -n \
  com.clonehub.pro.test/com.clonehub.advanced.fixture.CloneFixtureActivity
sleep 8
adb shell uiautomator dump /sdcard/fixture-ui.xml
adb pull /sdcard/fixture-ui.xml "$OUT/fixture-ui.xml"

set +e
adb pull /sdcard/Pictures/CloneHubEvidence "$OUT/gallery" > "$OUT/gallery-pull.txt" 2>&1
GALLERY_STATUS=$?
adb pull /sdcard/Android/data/com.clonehub.pro.test/files/Pictures/CloneHubEvidence \
  "$OUT/app-fallback" > "$OUT/fallback-pull.txt" 2>&1
FALLBACK_STATUS=$?
set -e

cat "$OUT/fixture-ui.xml"
cat "$OUT/gallery-pull.txt"
cat "$OUT/fallback-pull.txt"

grep -q 'CLONE_FIXTURE_RUNNING' "$OUT/fixture-ui.xml"
grep -q 'SCREENSHOT SAVED' "$OUT/fixture-ui.xml"
if [ "$GALLERY_STATUS" -ne 0 ] || ! find "$OUT/gallery" -type f -name '*.png' | grep -q .; then
  echo "Self-captured PNG was not found in shared Pictures/CloneHubEvidence"
  exit 1
fi

echo "Live fixture self-capture passed"
