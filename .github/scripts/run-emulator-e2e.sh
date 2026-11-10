#!/usr/bin/env bash
set -euo pipefail

EVIDENCE_DIR="${GITHUB_WORKSPACE}/evidence"
mkdir -p "${EVIDENCE_DIR}"

cleanup() {
  STATUS=$?
  trap - EXIT
  adb logcat -d -v threadtime > "${EVIDENCE_DIR}/live_test_logcat.txt" 2>/dev/null || true
  exit "${STATUS}"
}
trap cleanup EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell wm dismiss-keyguard
adb logcat -c
adb shell getprop ro.product.cpu.abilist | tee "${EVIDENCE_DIR}/emulator-abis.txt"

echo "Installing application under test"
adb install -r -t artifacts/app-debug.apk
adb shell pm list packages | grep "^package:${PACKAGE_ID}$"

echo "Launching HomeActivity and waiting for rendering"
adb shell am start -W -S -n "${PACKAGE_ID}/${MAIN_ACTIVITY}"
sleep 5
adb shell pidof "${PACKAGE_ID}" | grep -E '[0-9]+'
adb exec-out screencap -p > "${EVIDENCE_DIR}/live_test_home_screen.png"

# UI XML is supplementary evidence; instrumentation below is the required UI assertion.
if adb shell uiautomator dump /sdcard/clonehub-window.xml && \
  adb pull /sdcard/clonehub-window.xml "${EVIDENCE_DIR}/live_test_window.xml"; then
  grep -q "Clone Hub Pro" "${EVIDENCE_DIR}/live_test_window.xml" || \
    echo "WARNING: optional UI XML did not contain the expected title"
else
  echo "WARNING: optional UI XML capture failed; strict instrumentation will still run"
fi

echo "Installing and running instrumentation tests"
adb install -r -t artifacts/app-debug-androidTest.apk
set +e
adb shell am instrument -w -r --no-window-animation \
  "${TEST_PACKAGE_ID}/androidx.test.runner.AndroidJUnitRunner" \
  > "${EVIDENCE_DIR}/instrumentation-results.txt" 2>&1
TEST_STATUS=$?
set -e
cat "${EVIDENCE_DIR}/instrumentation-results.txt"
if [ "${TEST_STATUS}" -ne 0 ] || grep -q "FAILURES!!!" "${EVIDENCE_DIR}/instrumentation-results.txt"; then
  echo "Instrumentation tests failed"
  exit 1
fi

adb logcat -d -v threadtime > "${EVIDENCE_DIR}/live_test_logcat.txt"
if grep -q "FATAL EXCEPTION" "${EVIDENCE_DIR}/live_test_logcat.txt"; then
  echo "A fatal application exception was found in logcat"
  exit 1
fi

echo "All emulator E2E checks passed"
