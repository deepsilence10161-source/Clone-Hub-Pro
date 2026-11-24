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

# Waits for the device to answer again after a suite, and records what happened.
#
# Measured on run 43668222165: both sign-in suites passed ('OK (5 tests)', 'OK (6 tests)') and then
# every evidence command answered 'Can't find service: package' / 'Can't find service: account' —
# the emulator was restarting underneath the script, and the job died on the evidence step with
# exit code 20, reporting a failure for a run whose tests had passed. The evidence is worth waiting
# for: it is the only record of what the device said.
wait_for_device_services() {
  local tries=${1:-20}
  for _ in $(seq 1 "$tries"); do
    if adb shell pm list packages >/dev/null 2>&1 && adb shell dumpsys account >/dev/null 2>&1; then
      return 0
    fi
    echo "device is not answering (package/account service); waiting"
    sleep 3
  done
  return 1
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
# The account service's own verbose trace is the only way to tell "the request reached the platform
# and was filtered" from "the request never left the container". Debug-level tags for that service are
# not on by default, so they are turned on for this test and the tag dump is kept as evidence.
adb shell setprop log.tag.AccountManagerService VERBOSE 2>/dev/null || true
adb shell setprop log.tag.Accounts VERBOSE 2>/dev/null || true
adb shell setprop log.tag.AccountManager VERBOSE 2>/dev/null || true
run_tests google-signin -e class "$SIGNIN_CLASS" || deep_status=$?

# The other half of "sign-in inside a clone", and the half a field report actually came from: a
# web-based app signing in with Google through a WebView. Nothing about that path touches
# AccountManager, so the suite above cannot see it. Its own status, so a WebView failure names
# itself instead of hiding inside the native suite.
# Why installing a newer APK sometimes answers "App not installed", measured on this device.
#
# The cases run here, not inside the instrumentation, for a measured reason: UiDevice.executeShellCommand
# runs as the test app's own uid, so `pm install` is refused and returns an empty answer (runs
# 45440731787, 45442678874 - the files were staged correctly and every case still came back blank). The
# gate shell is the uid Android allows to install, which is also the only place the installer's own words
# are visible. `pm install <path>` is still refused there on Android 14: the installer may not open an
# app-supplied file under /data/local/tmp and says so plainly - "Can't open file:
# /data/local/tmp/legacy-update/migrate-older.apk", run 45444458598. So the APK is streamed from the host,
# which is what `adb install` does and what every store does.
#
# The two APKs are one package, one versionCode, two keys: `migrate-older` carries Gradle's per-machine
# debug key (the build a user already has), `migrate-newer` carries the committed key (the build being
# shipped). Nothing here touches the app under test.
LEGACY_PKG="com.clonehub.pro.migrate"
legacy_status=0
{
  echo "probe package=$LEGACY_PKG"
  adb shell pm uninstall "$LEGACY_PKG" > /dev/null 2>&1 || true

  echo "--- case=install-older-key"
  adb install -r -t --user 0 artifacts/migrate-older.apk 2>&1
  adb shell pm list packages "$LEGACY_PKG" 2>&1

  echo "--- case=new-over-old-same-package-different-key"
  adb install -r -t --user 0 artifacts/migrate-newer.apk 2>&1
  adb shell dumpsys package "$LEGACY_PKG" 2>/dev/null | grep -m1 -E "signatures=|Signature" || true

  echo "--- case=remove-old-key-build"
  adb shell pm uninstall "$LEGACY_PKG" 2>&1
  adb shell pm list packages "$LEGACY_PKG" 2>&1

  echo "--- case=install-new-key-on-empty"
  adb install -r -t --user 0 artifacts/migrate-newer.apk 2>&1
  adb shell dumpsys package "$LEGACY_PKG" 2>/dev/null | grep -m1 -E "signatures=|Signature" || true

  echo "--- case=in-place-update-same-key"
  adb install -r -t --user 0 artifacts/migrate-newer.apk 2>&1
  adb shell pm list packages "$LEGACY_PKG" 2>&1
} > "$OUT/legacy-update.txt" 2>&1 || true
cat "$OUT/legacy-update.txt"

# The verdict is the device's own words, checked here. A different key must be refused; the same key must
# install and must update in place. If the device says something else, this gate fails and the evidence
# above is what says why.
if [ -f artifacts/migrate-older.apk ] && [ -f artifacts/migrate-newer.apk ]; then
  grep -q "Success" "$OUT/legacy-update.txt" || { echo "the same-key install did not succeed"; legacy_status=1; }
  grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|Failure" "$OUT/legacy-update.txt" \
    || { echo "the different-key install was not refused; the case did not reproduce"; legacy_status=1; }
  echo "Migration gate: $(grep -c '^--- case=' "$OUT/legacy-update.txt") cases, verdict=$legacy_status"
else
  echo "migration probe APKs not staged in artifacts/; the migration gate is NOT TESTED in this run"
fi

# The real in-place update, on the app under test itself.
#
# The migration cases above prove the rule with two throwaway builds. This case proves the thing the user
# actually does: a published copy is already installed, and the new build is installed over it without
# deleting anything. Both are signed with the committed key, so Android treats them as one app and only
# the versionCode differs - which is exactly what the in-app updater hands to the installer.
#
# The app's own suite is taken off the device first, because a test package is bound to one app signature
# and cannot outlive an install; it is put back afterwards so the later suites still have it.
REAL_UPDATE_OUT="$OUT/real-update.txt"
real_update_status=0
if [ -f artifacts/released-previous.apk ]; then
  {
    echo "--- installed previously released build"
    adb shell pm uninstall "$PACKAGE_ID" > /dev/null 2>&1 || true
    adb install -r -t --user 0 artifacts/released-previous.apk 2>&1
    echo "existing version: $(adb shell dumpsys package "$PACKAGE_ID" 2>/dev/null | grep -m1 versionCode)"
    echo "--- installed copy before the update (user data marker)"
    adb shell run-as "$PACKAGE_ID" sh -c 'mkdir -p files && echo kept-through-update > files/update_marker.txt' 2>&1
    adb shell pm list packages "$PACKAGE_ID" 2>&1

    echo "--- new build over the installed copy, no uninstall"
    adb install -r -t --user 0 artifacts/app-debug.apk 2>&1
    echo "updated version: $(adb shell dumpsys package "$PACKAGE_ID" 2>/dev/null | grep -m1 versionCode)"

    echo "--- the user's data survived the update"
    adb shell run-as "$PACKAGE_ID" sh -c 'cat files/update_marker.txt' 2>&1
  } > "$REAL_UPDATE_OUT" 2>&1 || true
  cat "$REAL_UPDATE_OUT"

  # The verdict: two "Success" lines (install, then update over it), the versionCode moved forward, the
  # package was never removed in between, and the marker file written before the update is still there.
  if [ "$(grep -c '^Success$' "$REAL_UPDATE_OUT")" -lt 2 ]; then
    echo "the in-place update did not succeed twice"
    real_update_status=1
  fi
  grep -q "^updated version:.*versionCode=2003" "$REAL_UPDATE_OUT" \
    || { echo "the updated build is not the versionCode this run built"; real_update_status=1; }
  grep -q "kept-through-update" "$REAL_UPDATE_OUT" \
    || { echo "the installed copy's data did not survive the update"; real_update_status=1; }
  echo "Real in-place update: verdict=$real_update_status"
else
  echo "the previously released build was not staged; the in-place update is NOT TESTED in this run"
  real_update_status=1
fi

# The test package does not survive an app reinstall, so it is installed again for the suites below.
adb install -r -t artifacts/app-debug-androidTest.apk > /dev/null 2>&1 || true

WEBVIEW_CLASS="com.clonehub.advanced.ui.home.WebViewSignInCloneInstrumentedTest"
webview_status=0
run_tests webview-signin -e class "$WEBVIEW_CLASS" || webview_status=$?
adb logcat -d -v threadtime | grep -E "WEBVIEW_SIGNIN|WEBVIEW_GATE" > "$OUT/webview-signin-logcat.txt" 2>/dev/null || true
cat "$OUT/webview-signin-logcat.txt" || true

# The in-app updater's gate: the installed build's own versionCode/signing key, the declared install
# permission, the FileProvider the installer path depends on, and — when the device has a network —
# that the release channel parses into a comparable versionCode. Run here because it is the same
# ARM64 device the update is installed on, and because a FileProvider that only resolves on API 34
# is exactly the kind of thing that ships broken.
UPDATE_CLASS="com.clonehub.advanced.ui.update.UpdateFlowInstrumentedTest"
update_status=0
adb logcat -d -v threadtime > "$OUT/google-signin-logcat.txt" 2>/dev/null || true
run_tests update-flow -e class "$UPDATE_CLASS" || update_status=$?
adb logcat -d -v threadtime | grep "UPDATE_" > "$OUT/update-logcat.txt" 2>/dev/null || true
cat "$OUT/update-logcat.txt" || true
# Every line the account service wrote while the sign-in test ran, kept separately so the verdict is
# readable without grepping 4 MB of logcat.
adb logcat -d -v threadtime -s AccountManagerService:V Accounts:V AccountManager:V \
  > "$OUT/account-service-logcat.txt" 2>/dev/null || true
cat "$OUT/account-service-logcat.txt" || true
# How the clone's account answer changes over the life of one process: a timeline, not a verdict.
adb logcat -d -v threadtime | grep "file=account_samples.txt" \
  > "$OUT/account-samples.txt" 2>/dev/null || true
cat "$OUT/account-samples.txt" || true
# The probe reports the clone wrote about Google sign-in.
adb shell run-as "$PACKAGE_ID" sh -c 'cat files/account_probe-*.txt files/token_probe-*.txt' \
  > "$OUT/google-signin-probes.txt" 2>&1 || true
cat "$OUT/google-signin-probes.txt" || true
# Platform answers about accounts, from the device's own point of view.
adb shell dumpsys account > "$OUT/account-service.txt" 2>&1 || true
# The suites can outlive the emulator's patience: wait for it to answer before asking anything.
if ! wait_for_device_services 20; then
  echo "device did not come back after the suites; the evidence below is what it answered"
  adb shell getprop sys.boot_completed > "$OUT/device-after-suites.txt" 2>&1 || true
fi

# Did the platform accept the seeded com.google account, and did a token request reach the
# authenticator? Both answers are in the device's own words.
adb shell dumpsys account | grep -A3 "Accounts: " > "$OUT/account-list.txt" 2>&1 || true
adb shell cat "/sdcard/Android/data/$TEST_PACKAGE_ID/files/test_authenticator_proof.txt" \
  > "$OUT/test-authenticator-proof.txt" 2>&1 || true
cat "$OUT/test-authenticator-proof.txt" || true
adb shell pm list packages | grep -i "test.google\|authenticator" > "$OUT/authenticator-packages.txt" 2>&1 || true
adb shell pm list packages | grep -i "google\|gsf\|vending" > "$OUT/play-services-packages.txt" 2>&1 || true
adb shell dumpsys account | grep -iE "type=|Authenticator" | head -40 > "$OUT/account-types.txt" 2>&1 || true
cat "$OUT/account-types.txt" || true
# The permission that decides whether a cloned app can read the account list at all, in the
# platform's own words, plus the clone UI on screen. Without these two files a sign-in failure
# cannot be told apart from a layout that hides the button.
adb shell dumpsys package "$PACKAGE_ID" | grep -A2 "GET_ACCOUNTS" \
  > "$OUT/account-permission.txt" 2>&1 || true
mkdir -p "$OUT/google-signin"
adb pull "/sdcard/Android/data/$PACKAGE_ID/files/google-signin" "$OUT/google-signin" \
  > /dev/null 2>&1 || adb pull "$(adb shell run-as "$PACKAGE_ID" pwd | tr -d '\r')/app_external/files/google-signin" \
  "$OUT/google-signin" > /dev/null 2>&1 || true
ls -l "$OUT/google-signin" 2>/dev/null || true
echo "Sign-in evidence captured; packages: $(cat "$OUT/play-services-packages.txt" | tr '\n' ' ')"

# Instrumentation has terminated the target process. Force-stop again explicitly,
# then run only the persistence check in a genuinely fresh target process.
adb shell run-as "$PACKAGE_ID" cat shared_prefs/clone_spaces_db.xml \
  > "$OUT/model-before-cold-restart.xml" 2>&1 || true
adb shell am force-stop "$PACKAGE_ID"
cold_status=0
run_tests cold-restart -e class "$DEEP_CLASS#z_coldRestart_preservesModelAndFixtureData" || cold_status=$?
if [ "$deep_status" -ne 0 ] || [ "$cold_status" -ne 0 ] || [ "$update_status" -ne 0 ] || [ "$webview_status" -ne 0 ] || [ "$legacy_status" -ne 0 ] || [ "$real_update_status" -ne 0 ]; then
  echo "Deep gate failure: deep-suite=$deep_status cold-restart=$cold_status update-flow=$update_status webview-signin=$webview_status legacy-update=$legacy_status real-update=$real_update_status"
  exit 1
fi

if grep -E 'Fatal signal 11|SIGSEGV|native crash' "$OUT/deep-logcat.txt"; then
  echo "Native crash detected during deep testing"
  exit 1
fi

adb shell am start -W -S -n "$PACKAGE_ID/$MAIN_ACTIVITY"
sleep 2
adb shell pidof "$PACKAGE_ID" | grep -E '[0-9]+'
echo "Legacy update gate: $(grep -c "LEGACY_UPDATE_GATE" "$OUT/legacy-update-logcat.txt" 2>/dev/null || echo 0) measured cases"
grep -E "LEGACY_UPDATE_GATE case=" "$OUT/legacy-update-logcat.txt" 2>/dev/null || true

echo "WebView sign-in gate: $(grep -c "WEBVIEW_SIGNIN_MARKER" "$OUT/webview-signin-logcat.txt" 2>/dev/null || echo 0) measured markers"
grep -E "WEBVIEW_GATE_(PARITY|VERDICT|STEP)" "$OUT/webview-signin-logcat.txt" || true
echo "Updater gate: $(grep -c "UPDATE_GATE" "$OUT/update-logcat.txt" 2>/dev/null || echo 0) measured lines"
grep -E "UPDATE_GATE (installed|install_permission|provider|channel)" "$OUT/update-logcat.txt" || true
echo "Deep native ARM64 tests passed"
