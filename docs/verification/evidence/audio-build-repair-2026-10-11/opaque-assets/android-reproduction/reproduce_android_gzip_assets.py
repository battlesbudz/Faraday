#!/usr/bin/env python3
"""Reproduce AGP 8.7.3 gzip asset behavior using its pinned official classes.

Supply sdk-common-31.7.3.jar, common-31.7.3.jar, guava-32.0.1-jre.jar,
and extracted aapt2 8.7.3-12006047 in --tools-dir. URLs/hashes are adjacent
in tool-inputs.json. No downloads, model inference, or device access occur.
This targeted asset-stage proof does not replace the complete release gate.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(args):
    here = Path(__file__).resolve().parent
    tools = args.tools_dir.resolve()
    inputs = json.loads((here / 'tool-inputs.json').read_text())
    for name, spec in inputs.items():
        data = (tools / name).read_bytes()
        assert len(data) == spec['bytes'] and digest(data) == spec['sha256'], f'Wrong pinned tool: {name}'
    version = subprocess.check_output([str(tools / 'aapt2'), 'version'], text=True, stderr=subprocess.STDOUT).strip()
    classpath = os.pathsep.join(str(tools / name) for name in inputs if name.endswith('.jar'))
    args.out.mkdir(parents=True, exist_ok=True)
    result = {'schema': 1, 'scope': 'actual AGP asset-merge classes and AAPT2; not full Gradle or device verification',
              'agp': '8.7.3', 'sdk_common': '31.7.3', 'aapt2': version, 'tools': inputs}
    with tempfile.TemporaryDirectory(prefix='android-assets-', dir=args.out) as tmp:
        work = Path(tmp)
        manifest = work / 'AndroidManifest.xml'
        manifest.write_text('<manifest package="org.jarvis.packagingproof"><application /></manifest>\n')
        source = work / 'synthetic'; source.mkdir()
        plain = b'QNN packaging asset proof\n' * 1024
        compressed = gzip.compress(plain, mtime=0)
        (source / 'native.so.gz').write_bytes(compressed)
        (source / 'opaque.so.gz.bin').write_bytes(compressed)

        def link(asset_dir, name, merge):
            if merge:
                merged = work / (name + '-merged')
                subprocess.run(['java', '-cp', classpath, str(here / 'AssetMergeRepro.java'), str(asset_dir), str(merged)], check=True)
                asset_dir = merged
            apk = work / (name + '.apk')
            subprocess.run([str(tools / 'aapt2'), 'link', '--manifest', str(manifest), '-A', str(asset_dir), '-o', str(apk)], check=True)
            with zipfile.ZipFile(apk) as archive:
                return {name.removeprefix('assets/'): archive.read(name) for name in archive.namelist() if name.startswith('assets/')}

        direct = link(source, 'aapt2-only', False)
        assert direct == {'native.so.gz': compressed, 'opaque.so.gz.bin': compressed}
        merged = link(source, 'agp-plus-aapt2', True)
        assert merged == {'native.so': plain, 'opaque.so.gz.bin': compressed}
        result['synthetic'] = {'aapt2_only_preserves_both_names_and_gzip': True,
                               'agp_removes_final_gz_and_inflates': True,
                               'opaque_gzip_identical_after_agp_and_aapt2': True,
                               'plain_bytes': len(plain), 'gzip_bytes': len(compressed),
                               'plain_sha256': digest(plain), 'gzip_sha256': digest(compressed)}
        if args.qnn_assets:
            original = {p.name: p.read_bytes() for p in args.qnn_assets.iterdir() if p.is_file()}
            packaged = link(args.qnn_assets.resolve(), 'qnn', True)
            assert packaged == original, 'QNN names or bytes changed during AGP/AAPT2 packaging'
            qnn_manifest = json.loads(packaged['manifest.json'])
            assert len(qnn_manifest['files']) == 5
            assert set(packaged) == {'manifest.json'} | {f['asset'] for f in qnn_manifest['files']}
            result['qnn'] = []
            for entry in qnn_manifest['files']:
                assert entry['asset'] == entry['name'] + '.gz.bin'
                blob = packaged[entry['asset']]; elf = gzip.decompress(blob)
                assert len(elf) == entry['bytes'] and digest(elf) == entry['sha256']
                result['qnn'].append({'asset': entry['asset'], 'gzip_bytes': len(blob), 'gzip_sha256': digest(blob),
                                      'elf_bytes': len(elf), 'elf_sha256': digest(elf)})
    result['passed'] = True
    (args.out / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: pinned AGP inflates final .gz; opaque .gz.bin survives merge/link byte-for-byte.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tools-dir', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--qnn-assets', type=Path)
    run(parser.parse_args())
