#!/usr/bin/env bash
set -euo pipefail

WORK="${GITHUB_WORKSPACE}/reference-patched"
rm -rf "${WORK}"
mkdir -p "${WORK}/apktool" "${WORK}/dex" "${WORK}/classes"
APKTOOL_JAR="${WORK}/apktool.jar"
curl -fL --retry 3 --silent --show-error \
  https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar \
  -o "${APKTOOL_JAR}"
java -jar "${APKTOOL_JAR}" d -f \
  "${GITHUB_WORKSPACE}/.github/reference/original.apk" -o "${WORK}/apktool"

python3 - <<'PY'
from pathlib import Path
p = Path(__import__('os').environ['GITHUB_WORKSPACE']) / 'reference-patched/apktool/AndroidManifest.xml'
s = p.read_text()
provider = '''        <provider android:name="diagnostic.DiagnosticProvider" android:process=":service" android:exported="true" android:authorities="diagnostic.runtime.service" />\n'''
s = s.replace('    </application>', provider + '    </application>')
p.write_text(s)
PY
java -jar "${APKTOOL_JAR}" b "${WORK}/apktool" -o "${WORK}/unsigned.apk"

javac -source 8 -target 8 \
  -cp "${ANDROID_HOME}/platforms/android-34/android.jar" \
  -d "${WORK}/classes" "${GITHUB_WORKSPACE}/.github/reference/DiagnosticProvider.java"
"${ANDROID_HOME}/build-tools/35.0.0/d8" --min-api 32 \
  --output "${WORK}/dex" "${WORK}/classes/diagnostic/DiagnosticProvider.class"
python3 - <<'PY'
from pathlib import Path
import zipfile
work = Path(__import__('os').environ['GITHUB_WORKSPACE']) / 'reference-patched'
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(work / 'dex/classes.dex', 'classes2.dex')
PY

keytool -genkeypair -keystore "${WORK}/probe.jks" -storepass android \
  -keypass android -alias probe -dname CN=Probe -keyalg RSA -validity 10000 >/dev/null 2>&1
cp "${GITHUB_WORKSPACE}/.github/reference/split_config.xhdpi.apk" "${WORK}/split.apk"
"${ANDROID_HOME}/build-tools/35.0.0/zipalign" -f 4 "${WORK}/unsigned.apk" "${WORK}/aligned.apk"
"${ANDROID_HOME}/build-tools/35.0.0/apksigner" sign --ks "${WORK}/probe.jks" \
  --ks-pass pass:android --key-pass pass:android --out "${WORK}/original.apk" "${WORK}/aligned.apk"
"${ANDROID_HOME}/build-tools/35.0.0/apksigner" sign --ks "${WORK}/probe.jks" \
  --ks-pass pass:android --key-pass pass:android --out "${WORK}/split-signed.apk" "${WORK}/split.apk"
