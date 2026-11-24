#!/usr/bin/env bash
# Local gate before pushing: Java/XML sanity plus workflow YAML and embedded shell syntax.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
python3 tools/precheck.py || exit 1
python3 - <<'PY' || exit 1
import yaml, subprocess, glob, sys
bad = False
for f in glob.glob('.github/workflows/*.yml'):
    d = yaml.safe_load(open(f))
    for job in d.get('jobs', {}).values():
        for st in job.get('steps', []):
            if isinstance(st.get('run'), str):
                r = subprocess.run(['bash', '-n', '-'], input=st['run'], text=True, capture_output=True)
                if r.returncode != 0:
                    print('shell syntax error in', f, st.get('name'), r.stderr.strip()); bad = True
sys.exit(1 if bad else 0)
PY
for s in .github/scripts/*.sh; do bash -n "$s" || exit 1; done
echo "precheck OK"

# Java fixtures are compiled too, when a platform jar is available. A Kotlin source check cannot see
# an Android API that does not exist: `javac` named one in two seconds that the platform build named
# after a thirteen-minute queue. CI still compiles everything; this just makes the answer local.
if command -v javac >/dev/null 2>&1 && [ -x tools/javac-androidtest.sh ]; then
  if [ -f "${ANDROID_JAR:-/tmp/clonehub-android-sdk/android-35/android.jar}" ]; then
    ANDROID_JAR="${ANDROID_JAR:-/tmp/clonehub-android-sdk/android-35/android.jar}" tools/javac-androidtest.sh
  else
    echo "precheck: android.jar not cached, skipping the Java compile (CI still compiles it)"
  fi
fi

# Android resources: an XML parser accepts what aapt2 rejects (an escaped quote inside a <string>, for
# instance), and the build failed on exactly that. When aapt2 is available, compile the resources for
# real; otherwise say so instead of passing silently.
AAPT2_BIN="${AAPT2_BIN:-}"
if [ -z "$AAPT2_BIN" ]; then
  AAPT2_BIN="$(command -v aapt2 || true)"
fi
if [ -n "$AAPT2_BIN" ] && [ -x "$AAPT2_BIN" ]; then
  TMP_RES="$(mktemp -d)"
  if ! "$AAPT2_BIN" compile --dir app/src/main/res -o "$TMP_RES" > "$TMP_RES/out.txt" 2>&1; then
    cat "$TMP_RES/out.txt"
    echo "precheck: aapt2 rejected the resources" >&2
    rm -rf "$TMP_RES"
    exit 1
  fi
  rm -rf "$TMP_RES"
  echo "aapt2 accepted the resources"
else
  echo "precheck: aapt2 not available, resources not compiled (CI compiles them)"
fi
