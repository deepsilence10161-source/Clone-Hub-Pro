#!/usr/bin/env bash
set -euo pipefail

EVIDENCE_DIR="${GITHUB_WORKSPACE}/evidence"
mkdir -p "${EVIDENCE_DIR}"

cleanup() {
  STATUS=$?
  trap - EXIT
  adb logcat -d -v threadtime > "${EVIDENCE_DIR}/live_test_logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "${EVIDENCE_DIR}/final-activity-stack.txt" 2>/dev/null || true
  adb shell ps -A > "${EVIDENCE_DIR}/final-processes.txt" 2>/dev/null || true
  adb exec-out screencap -p > "${EVIDENCE_DIR}/final-screen.png" 2>/dev/null || true
  adb pull "/sdcard/Android/data/${PACKAGE_ID}/files/real-user-flow" \
    "${EVIDENCE_DIR}/real-user-flow" 2>/dev/null || true
  adb pull "/sdcard/Android/data/${PACKAGE_ID}/files/compatibility" \
    "${EVIDENCE_DIR}/compatibility" 2>/dev/null || true
  adb pull "/sdcard/Android/data/${PACKAGE_ID}/files/settings-clone.png" \
    "${EVIDENCE_DIR}/settings-clone.png" 2>/dev/null || true
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
# Heads-up notifications cover the toolbar and silently consume injected touches.
adb shell settings put global heads_up_notifications_enabled 0
adb shell settings put global notification_bubbles 0
adb shell wm dismiss-keyguard
adb logcat -c
adb shell getprop ro.product.cpu.abilist | tee "${EVIDENCE_DIR}/emulator-abis.txt"

# A launcher ANR dialog takes window focus away from every ActivityScenario test.
# No test needs a home screen, so remove that source of system UI interference.
adb shell settings put global anr_show_background 0
adb shell settings put secure anr_show_first_instance 0 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || \
  echo "WARNING: Pixel Launcher could not be disabled in this test image"
adb shell pm disable-user --user 0 com.google.android.apps.wellbeing || \
  echo "WARNING: Digital Wellbeing could not be disabled in this test image"

# The app does not use Google services. Disable the CI image's GMS process so a
# dying downloadable-fonts provider cannot kill the app during instrumentation.
adb shell pm disable-user --user 0 com.google.android.gms || \
  echo "WARNING: GMS could not be disabled in this test image"
adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || \
  echo "WARNING: Google search could not be disabled in this test image"

echo "Installing application under test"
adb install -r -t artifacts/app-debug.apk
adb shell pm list packages | grep "^package:${PACKAGE_ID}$"

echo "Installing untouched real Clone Hub beside Clone Hub Pro"
# This catches both package-name and provider-authority conflicts in the same
# direction reported by users: Pro is already present, then real Clone Hub installs.
set +e
adb install-multiple -r -t \
  .github/reference/original.apk .github/reference/split_config.xhdpi.apk \
  > "${EVIDENCE_DIR}/real-clonehub-install.txt" 2>&1
REFERENCE_INSTALL_STATUS=$?
set -e
cat "${EVIDENCE_DIR}/real-clonehub-install.txt"
if [ "${REFERENCE_INSTALL_STATUS}" -ne 0 ]; then
  echo "Real Clone Hub could not install beside Clone Hub Pro"
  exit 1
fi
adb shell pm list packages | grep '^package:com.multibox.clone.hub$'

echo "Installing pinned real third-party compatibility apps"
for compatibility_apk in markor.apk calculator.apk aegis.apk; do
  adb install -r -t "artifacts/${compatibility_apk}"
done
adb shell pm list packages | grep -E \
  '^package:(net.gsantner.markor|com.simplemobiletools.calculator|com.beemdevelopment.aegis)$'

echo "Launching HomeActivity and waiting for rendering"
adb shell am start -W -S -n "${PACKAGE_ID}/${MAIN_ACTIVITY}"
sleep 5
adb shell pidof "${PACKAGE_ID}" | grep -E '[0-9]+'
adb exec-out screencap -p > "${EVIDENCE_DIR}/live_test_home_screen.png"

# Do not invoke the separate uiautomator shell process here: on slow emulators its
# UiAutomation connection can time out and interfere with the instrumentation session.
echo "Installing and running instrumentation tests"
adb install -r -t artifacts/app-debug-androidTest.apk
set +e
INSTRUMENTATION_ARGS=()
if [ -n "${TEST_CLASS:-}" ]; then
  INSTRUMENTATION_ARGS=(-e class "${TEST_CLASS}")
fi
adb shell am instrument -w -r --no-window-animation \
  "${INSTRUMENTATION_ARGS[@]}" \
  "${TEST_PACKAGE_ID}/androidx.test.runner.AndroidJUnitRunner" \
  > "${EVIDENCE_DIR}/instrumentation-results.txt" 2>&1
TEST_STATUS=$?
set -e
cat "${EVIDENCE_DIR}/instrumentation-results.txt"
if [ "${TEST_STATUS}" -ne 0 ] || \
  grep -q "FAILURES!!!" "${EVIDENCE_DIR}/instrumentation-results.txt" || \
  ! grep -q "INSTRUMENTATION_CODE: -1" "${EVIDENCE_DIR}/instrumentation-results.txt"; then
  echo "Instrumentation tests failed or did not complete successfully"
  exit 1
fi

adb logcat -d -v threadtime > "${EVIDENCE_DIR}/live_test_logcat.txt"
if grep -q "FATAL EXCEPTION" "${EVIDENCE_DIR}/live_test_logcat.txt"; then
  # The full log also contains unrelated system processes; instrumentation status is authoritative.
  echo "WARNING: a FATAL EXCEPTION section exists in full logcat; inspect package/PID evidence"
fi
# Instrumentation normally force-stops the target process when it finishes.
# Relaunch it before enforcing the final liveness gate.
adb shell am start -W -n "${PACKAGE_ID}/${MAIN_ACTIVITY}"
sleep 2
adb shell pidof "${PACKAGE_ID}" | grep -E '[0-9]+'

echo "All emulator E2E checks passed"
