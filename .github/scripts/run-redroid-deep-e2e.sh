#!/usr/bin/env bash
set -euo pipefail

OUT=deep-evidence
mkdir -p "$OUT"
RUNNER="$TEST_PACKAGE_ID/androidx.test.runner.AndroidJUnitRunner"
DEEP_CLASS=com.clonehub.advanced.ui.home.DeepCloneFlowInstrumentedTest
# Google sign-in inside a clone: account-list parity, token path, and account added while the
# clone is running. Same device, same APKs, driven through the runtime like a real cloned app.
SIGNIN_CLASS=com.clonehub.advanced.ui.home.GoogleSignInCloneInstrumentedTest

cleanup() {
  status=$?
  trap - EXIT
  adb logcat -d -v threadtime > "$OUT/deep-logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/final-activity-stack.txt" 2>/dev/null || true
  adb shell ps -A > "$OUT/final-processes.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$OUT/final-screen.png" 2>/dev/null || true
  # Derived from the logcat written above, so it has to happen here and not earlier.
  grep -h "Launch timing for" "$OUT/deep-logcat.txt" > "$OUT/launch-timings.txt" 2>/dev/null || true
  # Facts needed to design backup/restore: where the runtime keeps clone data for real.
  adb shell run-as "$PACKAGE_ID" sh -c 'ls -la files; ls -la files/wpsx 2>/dev/null; du -sk files/* files/wpsx/* 2>/dev/null' \
    > "$OUT/data-layout.txt" 2>&1 || true
  adb shell run-as "$PACKAGE_ID" sh -c 'find files -maxdepth 3 -type d 2>/dev/null | head -80' \
    > "$OUT/data-dirs.txt" 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

run_tests() {
  local name=$1
  shift
  echo "Running $name"
  set +e
  adb shell am instrument -w -r --no-window-animation "$@" "$RUNNER" \
    > "$OUT/$name.txt" 2>&1
  local status=$?
  set -e
  cat "$OUT/$name.txt"
  if [ "$status" -ne 0 ] || grep -q 'FAILURES!!!' "$OUT/$name.txt" || \
    ! grep -q 'INSTRUMENTATION_CODE: -1' "$OUT/$name.txt"; then
    echo "$name failed"
    return 1
  fi
}

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global heads_up_notifications_enabled 0
adb shell settings put global notification_bubbles 0
adb shell wm dismiss-keyguard
adb logcat -c

adb install -r -t artifacts/app-debug.apk
adb install -r -t artifacts/app-debug-androidTest.apk
adb shell pm clear "$PACKAGE_ID"
adb shell pm clear "$TEST_PACKAGE_ID"

# All deep tests, including an in-process persistence check. Keep the independent
# cold-restart gate runnable even when the first phase fails; both statuses remain fatal.
deep_status=0
# Cold-start timing of the host UI, measured on the device the tests run on: this is the
# baseline every later performance claim has to be compared against.
adb shell am force-stop "$PACKAGE_ID"
adb shell am start -W -n "$PACKAGE_ID/$MAIN_ACTIVITY" > "$OUT/cold-start.txt" 2>&1 || true
grep -E "TotalTime|WaitTime" "$OUT/cold-start.txt" || true
run_tests deep-suite -e class "$DEEP_CLASS" || deep_status=$?
# Kept separate so a sign-in failure names itself instead of hiding inside the deep suite.
adb logcat -c
run_tests google-signin -e class "$SIGNIN_CLASS" || deep_status=$?
adb logcat -d -v threadtime > "$OUT/google-signin-logcat.txt" 2>/dev/null || true
# Platform answers about accounts, from the device's own point of view.
adb shell dumpsys account > "$OUT/account-service.txt" 2>&1 || true
adb shell pm list packages | grep -i "google\|gsf\|vending" > "$OUT/play-services-packages.txt" 2>&1 || true
echo "Sign-in evidence captured; packages: $(cat "$OUT/play-services-packages.txt" | tr '\n' ' ')"

# Instrumentation has terminated the target process. Force-stop again explicitly,
# then run only the persistence check in a genuinely fresh target process.
adb shell run-as "$PACKAGE_ID" cat shared_prefs/clone_spaces_db.xml \
  > "$OUT/model-before-cold-restart.xml" 2>&1 || true
adb shell am force-stop "$PACKAGE_ID"
cold_status=0
run_tests cold-restart -e class "$DEEP_CLASS#z_coldRestart_preservesModelAndFixtureData" || cold_status=$?
if [ "$deep_status" -ne 0 ] || [ "$cold_status" -ne 0 ]; then
  echo "Deep gate failure: deep-suite=$deep_status cold-restart=$cold_status"
  exit 1
fi

if grep -E 'Fatal signal 11|SIGSEGV|native crash' "$OUT/deep-logcat.txt"; then
  echo "Native crash detected during deep testing"
  exit 1
fi

adb shell am start -W -S -n "$PACKAGE_ID/$MAIN_ACTIVITY"
sleep 2
adb shell pidof "$PACKAGE_ID" | grep -E '[0-9]+'
echo "Deep native ARM64 tests passed"
