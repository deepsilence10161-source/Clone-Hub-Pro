#!/usr/bin/env bash
# Compiles the androidTest Java fixture against the real android.jar, before CI does.
#
# Why this exists: `javac` names an API mistake in two seconds ("cannot find symbol
# getWebViewProviderPackage"), while the platform build answers the same thing after a thirteen-minute
# queue. It caught exactly that once; this makes the check routine instead of lucky.
#
# The jar is not committed (27 MB, and it is a Google download). Point ANDROID_JAR at one, or let the
# script fetch the API 35 platform image into a cache directory.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="${ANDROID_JAR:-}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  CACHE="${ANDROID_JAR_CACHE:-/tmp/clonehub-android-sdk}"
  JAR="$CACHE/android-35/android.jar"
  if [ ! -f "$JAR" ]; then
    echo "Fetching android.jar (API 35) into $CACHE ..."
    mkdir -p "$CACHE"
    curl -sL --max-time 600 -o "$CACHE/platform.zip" \
      https://dl.google.com/android/repository/platform-35_r01.zip
    unzip -o -q "$CACHE/platform.zip" 'android-*/android.jar' -d "$CACHE"
  fi
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "javac against $JAR"
# -encoding UTF-8: the sources contain the field report's own wording, and javac's default platform
# encoding would reject it.
javac -encoding UTF-8 -nowarn -proc:none -d "$OUT" -cp "$JAR" \
  $(find app/src/androidTest -name '*.java')
echo "androidTest Java sources compile"
