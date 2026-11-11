"""Local gate that catches the failures which actually reached CI before it existed:
Kotlin-only annotations inside Java sources, unbalanced braces after a patch, and
malformed Android XML. A full javac/Android SDK build is not available in this sandbox,
so this is deliberately narrow and false-positive-free.
"""
import re, sys, pathlib
import xml.etree.ElementTree as ET

REPO = pathlib.Path('/home/user/Clonehub/pro-audit2')
problems = []

for path in (REPO / 'app/src/main/java').rglob('*.java'):
    src = path.read_text()
    if re.search(r'@(Synchronized|JvmStatic|JvmField|OptIn)\b', src):
        problems.append(f'{path.name}: Kotlin-only annotation in a Java file')
    if src.count('{') != src.count('}'):
        problems.append(f'{path.name}: unbalanced braces ({src.count("{")} vs {src.count("}")})')
    if src.count('(') != src.count(')'):
        problems.append(f'{path.name}: unbalanced parentheses')

for path in list((REPO / 'app/src/main').rglob('*.xml')):
    try:
        ET.parse(path)
    except ET.ParseError as error:
        problems.append(f'{path.relative_to(REPO)}: invalid XML ({error})')

# Backup documents must stay decodable by the JVM test suite that guards them.
backup_dir = REPO / 'app/src/main/java/com/clonehub/advanced/core/backup'
for name in ('BackupDocument.kt', 'CloneBackupManager.kt'):
    if not (backup_dir / name).exists():
        problems.append(f'backup/{name}: missing')
tests = REPO / 'app/src/test/java/com/clonehub/advanced/core/backup/BackupDocumentTest.kt'
if not tests.exists():
    problems.append('backup tests are missing')

manifest = (REPO / 'app/src/main/AndroidManifest.xml').read_text()
defined = set(re.findall(r'<permission[^>]*android:name="([^"]+)"', manifest))
for used in set(re.findall(r'android:permission="((?!android\.)[^"]+)"', manifest)):
    if used not in defined:
        problems.append(f'AndroidManifest.xml: component uses undeclared permission {used}')

for p in problems:
    print('PRECHECK:', p)
print('precheck OK' if not problems else 'PRECHECK FAILED')
sys.exit(1 if problems else 0)
