#!/usr/bin/env bash
# Decides whether a failed API level deserves one automatic retry.
#
# The distinction this script makes is the whole point: a level where the *Android runtime* died
# (system_server SIGABRT, dead emulator, boot never completing) says nothing about Clone Hub Pro and
# is retried; a level where the instrumentation ran and a test failed is a real result and is never
# retried. Evidence: run 43257236404 on API 27 — system_server crashed in a Binder thread during
# boot (`Fatal signal 6 (SIGABRT) ... >>> system_server <<<`), no instrumentation result was ever
# produced, and the job failed with no test outcome.
set -uo pipefail

EVIDENCE_DIR="${1:-evidence}"
LOG="${EVIDENCE_DIR}/live_test_logcat.txt"
RESULTS="${EVIDENCE_DIR}/instrumentation-results.txt"

# The device stopped answering mid-run. This is checked before anything else because the levels that
# lose their emulator leave a half-written evidence directory: measured on API 28, run 45446120669, where
# HomeActivity was started and every later adb call answered "device offline" until the step died with an
# unrelated exit code. The old order read the pinned reference app's INSTALL_FAILED_OLDER_SDK line first
# and answered "not applicable", so a dead platform was filed as a real app failure and never retried.
if [ -f "${EVIDENCE_DIR}/device-offline.txt" ] && [ -s "${EVIDENCE_DIR}/device-offline.txt" ]; then
  echo "retry: the device went offline during the run ($(head -n 1 "${EVIDENCE_DIR}/device-offline.txt"))"
  exit 0
fi

# A test result exists: the app ran, so a failure here is a real failure.
if [ -s "${RESULTS}" ] && grep -q "INSTRUMENTATION_STATUS\|OK (\|FAILURES" "${RESULTS}"; then
  echo "no retry: instrumentation produced results (the app was tested)"
  exit 1
fi

# A package the platform refuses on purpose is not a flaky level: the API 27 leg (run
# 43592167654) failed after a retry with INSTALL_FAILED_OLDER_SDK for the pinned reference app
# (minSdkVersion 32, read from its manifest) while Clone Hub Pro itself had installed with
# "Success" and had already been listed as package:com.clonehub.pro. The reference app's level
# requirement is a property of that app, so the coexistence check is not applicable and is recorded
# as such instead of being retried.
if [ -f "${EVIDENCE_DIR}/real-clonehub-install.txt" ] \
  && grep -q "INSTALL_FAILED_OLDER_SDK" "${EVIDENCE_DIR}/real-clonehub-install.txt"; then
  echo "no retry: the pinned reference app requires a newer Android level (not applicable here)"
  exit 1
fi

# The platform went away during the run: measured on run 43668222149 (API 28), where Clone Hub Pro
# installed with "Success" on two consecutive attempts and then every command answered "device
# offline" / "cmd: Failure calling service activity: Broken pipe". That is the emulator, not the app
# — no test ever ran — so the level deserves its one retry.
if [ -f "${EVIDENCE_DIR}/live_test_logcat.txt" ] && \
  grep -q "device offline" "${EVIDENCE_DIR}/live_test_logcat.txt" 2>/dev/null; then
  echo "retry: the emulator went offline before any test ran"
  exit 0
fi

# The platform itself crashed or never came up.
if [ -f "${LOG}" ] && grep -qE ">>> system_server <<<|Fatal signal 11|Fatal signal 6 .* system_server" "${LOG}"; then
  echo "retry: the Android runtime crashed (system_server) before any test ran"
  exit 0
fi
if [ -f "${LOG}" ] && ! grep -q "instrumentation" "${LOG}"; then
  echo "retry: the emulator produced no instrumentation output at all"
  exit 0
fi

echo "no retry: the failure is not a platform crash"
exit 1
