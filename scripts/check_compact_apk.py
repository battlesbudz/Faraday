#!/usr/bin/env python3
"""Ensure the compact sideload APK changes native ZIP storage, not runtime payloads."""
import sys
import zipfile


def check(normal, compact):
    with zipfile.ZipFile(normal) as original, zipfile.ZipFile(compact) as packed:
        # The manifest intentionally changes extractNativeLibs; native/DEX/assets must not.
        def payload_names(archive):
            return {n for n in archive.namelist() if n.startswith(('lib/', 'assets/')) or n.endswith('.dex')}
        names = payload_names(original)
        assert names == payload_names(packed), 'Compact APK changed its runtime payload inventory'
        native = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
        assert native, 'Missing native payloads'
        for name in names:
            assert original.read(name) == packed.read(name), f'Compact APK changed bytes: {name}'
        for name in native:
            assert original.getinfo(name).compress_type == zipfile.ZIP_STORED, f'Normal APK unexpectedly compresses {name}'
            assert packed.getinfo(name).compress_type == zipfile.ZIP_DEFLATED, f'Compact APK failed to compress {name}'
    print('Compact APK preserves identical native, DEX and asset payloads.')


if __name__ == '__main__':
    check(*sys.argv[1:])
