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

# A test result exists: the app ran, so a failure here is a real failure.
if [ -s "${RESULTS}" ] && grep -q "INSTRUMENTATION_STATUS\|OK (\|FAILURES" "${RESULTS}"; then
  echo "no retry: instrumentation produced results (the app was tested)"
  exit 1
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
