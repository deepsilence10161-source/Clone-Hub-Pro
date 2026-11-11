#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-staged-apks}"
mkdir -p "$OUT"

fetch() {
  local name=$1
  local url=$2
  local sha256=$3
  curl -fL --retry 3 --retry-delay 2 "$url" -o "$OUT/$name"
  echo "$sha256  $OUT/$name" | sha256sum -c -
  unzip -t "$OUT/$name" > /dev/null
}

fetch markor.apk \
  'https://github.com/gsantner/markor/releases/download/v2026.10.0/net.gsantner.markor-v164-2026.10.0-flavorDefault-release.apk' \
  'c105bf5cc9530f7486b984323a185c17086b459a56ec936a5990ceb8da3d8440'
fetch calculator.apk \
  'https://github.com/SimpleMobileTools/Simple-Calculator/releases/download/5.12.0/calculator-fdroid-release.apk' \
  '092629deaeaaea824dbf0cd824989e80b498b1fa07ad5b4c3e744fc64ce5ab2f'
fetch aegis.apk \
  'https://github.com/beemdevelopment/Aegis/releases/download/v3.4.3/aegis-v3.4.3.apk' \
  'fa091c91912c0d56ec3553394fac4158f9a9d178e93bcbb35419601d788cb528'
