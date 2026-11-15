#!/usr/bin/env bash
# Compatibility shim: the gate lives in tools/check.sh
set -euo pipefail
exec "$(dirname "$0")/tools/check.sh"
