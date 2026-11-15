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
