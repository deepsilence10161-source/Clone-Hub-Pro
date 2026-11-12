#!/usr/bin/env python3
"""Read-only Android APK inspector.

Parses the binary AndroidManifest.xml (AXML) that ships inside a real APK, so claims about the
*shipped* artifact (minSdk, targetSdk, permissions, exported components, debuggable flag) are
checked against bytes instead of against the source tree. No Android SDK, no network.

Usage: python3 tools/apk_manifest.py path/to/app.apk [--json]
"""
import json
import struct
import sys
import zipfile

TYPE_XML = 0x0003
TYPE_STRING_POOL = 0x0001
TYPE_RESOURCE_MAP = 0x0180
TYPE_START_NAMESPACE = 0x0100
TYPE_END_NAMESPACE = 0x0101
TYPE_START_ELEMENT = 0x0102
TYPE_END_ELEMENT = 0x0103

# Attribute value types we care about.
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12


class StringPool:
    def __init__(self, data, offset):
        self.utf8 = False
        (chunk_type, header_size, chunk_size, string_count, style_count,
         flags, strings_start, styles_start) = struct.unpack_from('<HHIIIIII', data, offset)
        assert chunk_type == TYPE_STRING_POOL, hex(chunk_type)
        self.utf8 = bool(flags & 0x100)
        offsets = struct.unpack_from('<%dI' % string_count, data, offset + header_size)
        base = offset + strings_start
        self.strings = []
        for rel in offsets:
            self.strings.append(self._read_string(data, base + rel))

    def _read_string(self, data, pos):
        if self.utf8:
            # utf16 length (1-2 bytes), then utf8 length (1-2 bytes), then bytes, then NUL
            n = data[pos]
            pos += 2 if n & 0x80 else 1
            m = data[pos]
            if m & 0x80:
                m = ((m & 0x7f) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            return data[pos:pos + m].decode('utf-8', 'replace')
        n = struct.unpack_from('<H', data, pos)[0]
        pos += 2
        if n & 0x8000:
            n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, pos)[0]
            pos += 2
        return data[pos:pos + n * 2].decode('utf-16-le', 'replace')

    def get(self, index):
        if index == 0xFFFFFFFF or index >= len(self.strings):
            return None
        return self.strings[index]


def parse_manifest(axml):
    """Returns (elements, pool) where elements is a list of dicts with name/attrs."""
    magic, size = struct.unpack_from('<HI', axml, 0)
    assert magic == TYPE_XML, hex(magic)
    pos = struct.unpack_from('<H', axml, 2)[0]
    pool = None
    elements = []
    while pos < len(axml):
        chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', axml, pos)
        if chunk_size == 0:
            break
        if chunk_type == TYPE_STRING_POOL:
            pool = StringPool(axml, pos)
        elif chunk_type == TYPE_START_ELEMENT:
            line = struct.unpack_from('<I', axml, pos + 8)[0]
            body = pos + header_size
            # ResXMLTree_node header (16 bytes) ends before the body; the element body is
            # ResXMLTree_attrExt: ns(4) name(4) attrStart(2) attrSize(2) attrCount(2) ...
            (ns, name, attr_start, attr_size,
             attr_count) = struct.unpack_from('<IIHHH', axml, body)
            attrs = {}
            for i in range(attr_count):
                a = body + attr_start + i * attr_size
                a_ns, a_name, a_raw, v_size, v_res0, v_type, v_data = struct.unpack_from(
                    '<IIIHBBI', axml, a)
                key = pool.get(a_name)
                raw = pool.get(a_raw)
                if raw is not None:
                    value = raw
                elif v_type == TYPE_INT_BOOLEAN:
                    value = bool(v_data)
                elif v_type in (TYPE_INT_DEC, TYPE_INT_HEX):
                    value = v_data
                elif v_type == TYPE_REFERENCE:
                    value = 'reference:0x%08x' % v_data
                else:
                    value = pool.get(v_data)
                attrs[key] = value
            elements.append({'name': pool.get(name), 'attrs': attrs, 'line': line})
        pos += chunk_size
    return elements, pool


ANDROID_NS_ATTRS = ('name', 'versionCode', 'versionName', 'minSdkVersion', 'targetSdkVersion',
                    'exported', 'permission', 'debuggable', 'required', 'authorities', 'usesCleartextTraffic')


def summary(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        names = z.namelist()
        axml = z.read('AndroidManifest.xml')
        dex_names = [n for n in names if n.endswith('.dex')]
        dex_size = sum(z.getinfo(n).file_size for n in dex_names)
        v1 = [n for n in names if n.startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC'))]
        libs = sorted({n.split('/')[1] for n in names if n.startswith('lib/')})
        raw = z.read('AndroidManifest.xml')

    elements, _ = parse_manifest(axml)
    out = {
        'apk': apk_path,
        'manifest_compressed_bytes': len(raw),
        'permissions': [],
        'features': [],
        'components': {'activity': [], 'service': [], 'receiver': [], 'provider': []},
        'exported_without_permission': [],
        'dex_files': dex_names,
        'dex_uncompressed_bytes': dex_size,
        'native_abis': libs,
        'v1_signature_files': v1,
        'sdk': {},
        'application': {},
        'package': None,
    }
    for el in elements:
        a = el['attrs']
        name = el['name']
        if name == 'manifest':
            out['package'] = a.get('package')
            out['sdk']['versionCode'] = a.get('versionCode')
            out['sdk']['versionName'] = a.get('versionName')
        elif name == 'uses-sdk':
            out['sdk']['minSdkVersion'] = a.get('minSdkVersion')
            out['sdk']['targetSdkVersion'] = a.get('targetSdkVersion')
        elif name == 'uses-permission':
            out['permissions'].append(a.get('name'))
        elif name == 'uses-feature':
            out['features'].append({'name': a.get('name'), 'required': a.get('required')})
        elif name == 'application':
            out['application'] = {
                k: v for k, v in a.items()
                if k in ('name', 'debuggable', 'allowBackup', 'usesCleartextTraffic', 'label')
            }
        elif name in out['components']:
            comp = {k: v for k, v in a.items() if k in ANDROID_NS_ATTRS}
            out['components'][name].append(comp)
            exported = comp.get('exported')
            if exported is True and not comp.get('permission'):
                out['exported_without_permission'].append('%s:%s' % (name, comp.get('name')))
    return out


def signing_summary(apk_path):
    with open(apk_path, 'rb') as f:
        data = f.read()
    magic = b'APK Sig Block 42'
    idx = data.rfind(magic)
    return {
        'size_bytes': len(data),
        'apk_signing_block_v2plus': idx != -1,
        'signing_block_offset': idx,
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    path = sys.argv[1]
    info = summary(path)
    info.update(signing_summary(path))
    if '--json' in sys.argv:
        print(json.dumps(info, indent=2, sort_keys=True))
        return
    print('APK      :', info['apk'])
    print('package  :', info['package'])
    print('sdk      :', info['sdk'])
    print('app      :', info['application'])
    print('perms    :', info['permissions'])
    print('features :', info['features'])
    print('dex      :', info['dex_files'], info['dex_uncompressed_bytes'], 'bytes uncompressed')
    print('abis     :', info['native_abis'])
    print('signing  :', 'v2+ block present' if info['apk_signing_block_v2plus'] else 'NO v2+ block',
          '| v1 files:', info['v1_signature_files'] or 'none')
    for kind, comps in info['components'].items():
        print('%-9s: %d' % (kind, len(comps)))
    print('EXPORTED WITHOUT PERMISSION:', info['exported_without_permission'] or 'none')


if __name__ == '__main__':
    main()
