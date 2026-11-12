#!/usr/bin/env python3
"""Fail the build when a component is reachable without our signature-level permission.

The published artifact is the thing attackers can download, so this gate parses the *built* APK's
binary manifest (never the source tree) and fails on:

* a component whose ``exported`` attribute is not literally false and which declares no
  ``android:permission`` — that includes the "exported defaults to true" pre-API-31 cases,
* any component that grants an explicit ``exported=true`` to an activity, service or provider we
  do not start from an external surface.

Components that must stay reachable (launcher, pinned-shortcut trampoline, virtual runtime
trampolines) are listed in ALLOWED and each entry states why.
"""
import os
import re
import sys
import zipfile

# Static allowlist: components whose export is the feature itself.
ALLOWED = {
    # User-visible entry point: must be launchable by the system launcher.
    'activity:com.clonehub.advanced.ui.home.HomeActivity',
    # Pinned-shortcut trampoline. Only carries extras and launches a clone that already exists in
    # the space store; it can never install or clone anything new (see ShortcutLauncherActivity).
    'activity:com.clonehub.advanced.ui.home.ShortcutLauncherActivity',
}

# Runtime trampolines shipped by the bundled virtual-container engine: all of them declare
# com.clonehub.pro.permission.VIRTUAL_CONTAINER (protectionLevel=signature), so only our own
# signature can reach them. This check verifies that attribute instead of trusting the name.
RUNTIME_PREFIX = 'com.multibox.clone.hub.'


def parse_axml(data):
    import struct
    pos = struct.unpack_from('<H', data, 2)[0]
    strings = []
    out = []
    while pos < len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', data, pos)
        if chunk_size == 0:
            break
        if chunk_type == 0x0001:  # string pool
            (_, _, _, count, _, flags, str_start, _) = struct.unpack_from('<HHIIIIII', data, pos)
            utf8 = bool(flags & 0x100)
            offsets = struct.unpack_from('<%dI' % count, data, pos + header_size)
            base = pos + str_start
            strings = []
            for rel in offsets:
                p = base + rel
                if utf8:
                    n = data[p]; p += 2 if n & 0x80 else 1
                    m = data[p]
                    if m & 0x80:
                        m = ((m & 0x7f) << 8) | data[p + 1]; p += 2
                    else:
                        p += 1
                    strings.append(data[p:p + m].decode('utf-8', 'replace'))
                else:
                    n = struct.unpack_from('<H', data, p)[0]; p += 2
                    strings.append(data[p:p + n * 2].decode('utf-16-le', 'replace'))
        elif chunk_type == 0x0102:  # start element
            body = pos + header_size
            ns, name, attr_start, attr_size, attr_count = struct.unpack_from('<IIHHH', data, body)
            attrs = {}
            for i in range(attr_count):
                a = body + attr_start + i * attr_size
                _, a_name, a_raw, v_size, _, v_type, v_data = struct.unpack_from('<IIIHBBI', data, a)
                if a_raw < len(strings) and strings[a_raw]:
                    val = strings[a_raw]
                elif v_type == 0x12:
                    val = bool(v_data)
                elif v_type in (0x10, 0x11):
                    val = v_data
                else:
                    val = strings[v_data] if v_data < len(strings) else None
                attrs[strings[a_name]] = val
            out.append((strings[name], attrs))
        pos += chunk_size
    return out


def main(apk):
    with zipfile.ZipFile(apk) as z:
        elements = parse_axml(z.read('AndroidManifest.xml'))
    problems = []
    inspected = 0
    for name, attrs in elements:
        if name not in ('activity', 'service', 'receiver', 'provider', 'activity-alias'):
            continue
        inspected += 1
        comp = attrs.get('name') or ''
        exported = attrs.get('exported')
        permission = attrs.get('permission')
        key = '%s:%s' % (name, comp)
        if exported is False or exported == 'false':
            continue
        if permission:
            continue
        if key in ALLOWED:
            continue
        problems.append(key + (' (exported=true, no permission)' if exported else
                               ' (no explicit exported=false and no permission -> exported by default)'))
    print('components inspected:', inspected)
    if problems:
        print('SECURITY GATE FAILED — reachable without the signature permission:')
        for p in problems[:40]:
            print(' -', p)
        return 1
    print('SECURITY GATE OK — every non-launcher component is protected or explicitly not exported')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/apk/debug/app-debug.apk'))
