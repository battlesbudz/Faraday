#!/usr/bin/env python3
"""Verify the exact weightless encoder assets in final, signed APK files.

The APK is the input, not the source asset directory. Signer verification remains
the preceding workflow gate; these checks do not establish model/device quality.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import zipfile
import zlib

from package_android_aar import sha256 as file_sha256
from producer_identity import workflow_identity

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'assets/gemma_streaming/'
RECIPE_NAME = 'source-copy-recipe.bin'
LITERALS_NAME = 'structural-literals.bin.gzip'
RECIPE_BYTES = 86_196
LITERALS_BYTES = 134_002
DECODED_BYTES = 1_068_632
RECIPE_SHA256 = 'f41105fecf5a86c0ab2486182d4256b98bf33c3a71f58c2b4f8e9a37b5cd86cc'
LITERALS_SHA256 = 'c3093aa1f9bd5ff9cfe3c0a61df722d0bce806a5a4f2f1b747b0ffd91d15d2f1'
DECODED_SHA256 = 'e87747bc9d1289ff15fdf0f49a4834ecc849823fe11bb4671fb11921fa0b9cb0'
NOTICES = ('Apache-2.0.txt', 'MODEL-MODIFICATIONS.txt')
READER = 'app/src/main/java/com/battlesbudz/jarvis/v2/ai/audio/WeightlessEncoderRecipe.java'
STORE = 'app/src/main/java/com/battlesbudz/jarvis/v2/ai/audio/GemmaStreamingArtifactStore.kt'
REPORT_NAME = 'packaged-encoder-assets.json'
APK_NAMES = ('app-release.apk', 'app-compact.apk')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounded_file(path, limit):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError('Missing, unsafe or oversized reviewed source: ' + str(path))
    with path.open('rb') as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Reviewed source grew past its limit')
    return data


def constant(text, name, expected):
    # Require one literal declaration, never evaluate arbitrary source code.
    values = re.findall(r'\b' + re.escape(name) + r'\s*=\s*("[^"\n]*"|[0-9][0-9_]*[Ll]?)\s*[;\n]', text)
    if len(values) != 1:
        raise ValueError('Missing or ambiguous reviewed runtime constant: ' + name)
    actual = (values[0][1:-1] if values[0].startswith('"')
              else int(values[0].rstrip('Ll').replace('_', '')))
    if actual != expected:
        raise ValueError('Reviewed runtime constant drift: ' + name)


def source_contract(root=ROOT):
    reader = bounded_file(root / READER, 128 * 1024)
    store = bounded_file(root / STORE, 128 * 1024)
    for name, expected in {
            'RECIPE_BYTES': RECIPE_BYTES, 'LITERALS_BYTES': LITERALS_BYTES,
            'LITERALS_DECODED_BYTES': DECODED_BYTES,
            'RECIPE_SHA256': RECIPE_SHA256, 'LITERALS_SHA256': LITERALS_SHA256,
            'LITERALS_DECODED_SHA256': DECODED_SHA256}.items():
        constant(reader.decode('utf-8'), name, expected)
    for name, expected in {'ASSET_DIRECTORY': 'gemma_streaming',
                           'RECIPE_ASSET_NAME': RECIPE_NAME,
                           'LITERALS_ASSET_NAME': LITERALS_NAME}.items():
        constant(store.decode('utf-8'), name, expected)
    assets = {
        PREFIX + RECIPE_NAME: {'bytes': RECIPE_BYTES, 'sha256': RECIPE_SHA256},
        PREFIX + LITERALS_NAME: {'bytes': LITERALS_BYTES, 'sha256': LITERALS_SHA256,
                                'decoded_bytes': DECODED_BYTES, 'decoded_sha256': DECODED_SHA256},
    }
    sources = {READER: digest(reader), STORE: digest(store)}
    for name in NOTICES:
        relative = 'app/src/main/' + PREFIX + name
        data = bounded_file(root / relative, 64 * 1024)
        if not data:
            raise ValueError('Empty reviewed encoder notice: ' + name)
        assets[PREFIX + name] = {'bytes': len(data), 'sha256': digest(data)}
        sources[relative] = digest(data)
    contract = {'assets': assets, 'source_files_sha256': sources}
    return {**contract, 'sha256': digest(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode())}


def bounded_entry(archive, name, expected_bytes):
    try:
        entry = archive.getinfo(name)
    except KeyError as error:
        raise ValueError('Missing packaged encoder asset: ' + name) from error
    if entry.is_dir() or entry.file_size != expected_bytes:
        raise ValueError('Packaged encoder asset size mismatch: ' + name)
    try:
        with archive.open(entry) as source:
            data = source.read(expected_bytes + 1)
    except (RuntimeError, NotImplementedError, zipfile.BadZipFile, zlib.error, EOFError) as error:
        raise ValueError('Unreadable packaged encoder asset: ' + name) from error
    if len(data) != expected_bytes:
        raise ValueError('Packaged encoder asset size changed: ' + name)
    return data


def decoded_literals(data):
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        decoded = decoder.decompress(data, DECODED_BYTES + 1)
    except zlib.error as error:
        raise ValueError('Invalid packaged encoder gzip stream') from error
    if len(decoded) > DECODED_BYTES or decoder.unconsumed_tail:
        raise ValueError('Packaged encoder gzip exceeds decoded byte bound')
    if not decoder.eof or decoder.unused_data:
        raise ValueError('Truncated, concatenated or trailing packaged encoder gzip data')
    if len(decoded) != DECODED_BYTES or digest(decoded) != DECODED_SHA256:
        raise ValueError('Packaged encoder decoded bytes or SHA-256 mismatch')
    return {'decoded_bytes': len(decoded), 'decoded_sha256': digest(decoded)}


def verify(apk, contract=None):
    contract = source_contract() if contract is None else contract
    if apk.is_symlink() or not apk.is_file():
        raise ValueError('Missing or unsafe final APK')
    apk_sha256 = file_sha256(apk)
    observed = {}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK ZIP entries')
        expected = set(contract['assets'])
        # Reject legacy .gz, aapt's decoded .bin, aliases and misplaced copies.
        # No extra entry in the owned namespace can shadow the runtime inventory.
        candidates = {name for name in names
                      if 'gemma_streaming' in name.replace('\\', '/').casefold().split('/')
                      or Path(name.replace('\\', '/')).name.casefold().startswith(
                          ('structural-literals', 'source-copy-recipe'))}
        if candidates != expected:
            raise ValueError('Missing, legacy, transformed or ambiguous encoder APK paths: '
                             + json.dumps({'missing': sorted(expected - candidates),
                                           'unexpected': sorted(candidates - expected)}))
        for name, pin in contract['assets'].items():
            data = bounded_entry(archive, name, pin['bytes'])
            if digest(data) != pin['sha256']:
                raise ValueError('Packaged encoder asset SHA-256 mismatch: ' + name)
            observed[name] = {'bytes': len(data), 'sha256': digest(data)}
            if name == PREFIX + LITERALS_NAME:
                observed[name].update(decoded_literals(data))
    if file_sha256(apk) != apk_sha256:
        raise ValueError('Final APK changed during encoder asset verification')
    return {'passed': True, 'apk': apk.name, 'apk_bytes': apk.stat().st_size,
            'apk_sha256': apk_sha256, 'source_contract_sha256': contract['sha256'],
            'assets': observed}


def report(apks, identity=None):
    result = {'schema': 1, 'passed': False, 'apks': [], 'workflow_identity': identity,
              'scope': 'Exact final APK encoder assets and notices; signer, native/model and device gates remain separate.'}
    try:
        apks = list(apks)
        if len(apks) != 2 or {apk.name for apk in apks} != set(APK_NAMES):
            raise ValueError('Both final normal and compact APKs are required exactly once')
        apks.sort(key=lambda apk: APK_NAMES.index(apk.name))
        contract = source_contract()
        result['reviewed_contract'] = contract
        for apk in apks:
            try:
                result['apks'].append(verify(apk, contract))
            except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
                failure = {'passed': False, 'apk': apk.name, 'error': str(error)}
                if apk.is_file():
                    failure.update(apk_bytes=apk.stat().st_size, apk_sha256=file_sha256(apk))
                result['apks'].append(failure)
        result['passed'] = bool(result['apks']) and all(row['passed'] for row in result['apks'])
    except (ValueError, OSError, KeyError, TypeError) as error:
        result['error'] = str(error)
    return result


def bind_report(folder, current_identity):
    data = bounded_file(folder / REPORT_NAME, 256 * 1024)
    retained = json.loads(data)
    if not isinstance(retained, dict) or retained.get('passed') is not True:
        raise ValueError('Missing or failed pre-upload packaged encoder asset report')
    producer = retained.get('workflow_identity')
    if not isinstance(producer, dict):
        raise ValueError('Packaged encoder report has no APK producer identity')
    checked = workflow_identity(current_identity, producer.get('GITHUB_RUN_ATTEMPT'))
    if producer != checked:
        raise ValueError('Packaged encoder report differs from this run/source/repository')
    actual = report([folder / name for name in APK_NAMES], producer)
    if actual['passed'] is not True or actual != retained:
        raise ValueError('Packaged encoder assets or retained report differ from final APKs/reviewed source')
    return {**actual, 'preupload_report_sha256': digest(data)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, action='append', required=True)
    parser.add_argument('--check-workflow', action='store_true')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        identity = workflow_identity(os.environ) if args.check_workflow else None
        result = report(args.apk, identity)
    except ValueError as error:
        result = {'schema': 1, 'passed': False, 'error': str(error), 'apks': []}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
