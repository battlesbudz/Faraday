#!/usr/bin/env python3
"""Bind final APK sampler bytes and required notice to the exact SDK producer."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from repair_sampler_dependencies import OUTPUTS

HERE = Path(__file__).resolve().parent
PROVENANCE_ASSET = 'assets/litert-lm-source-provenance.json'
NOTICE_ASSET = 'assets/litert-lm-sampler-modifications.md'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def bounded_entry(archive, name, limit):
    entry = archive.getinfo(name)
    if entry.file_size > limit or entry.is_dir():
        raise ValueError('Oversized or invalid packaged SDK entry: ' + name)
    try:
        with archive.open(entry) as source:
            data = source.read(limit + 1)
    except (RuntimeError, NotImplementedError) as error:
        raise ValueError('Unsupported packaged SDK entry: ' + name) from error
    if len(data) != entry.file_size or len(data) > limit:
        raise ValueError('Packaged SDK entry size changed: ' + name)
    return data


def verify(apk, provenance, expected_provenance_sha256):
    if (not re.fullmatch('[0-9a-f]{64}', expected_provenance_sha256 or '')
            or provenance.stat().st_size > 4 * 1024**2):
        raise ValueError('Exact bounded SDK producer provenance required')
    with provenance.open('rb') as source:
        data = source.read(4 * 1024**2 + 1)
    if len(data) > 4 * 1024**2:
        raise ValueError('Oversized SDK producer provenance')
    if sha256(data) != expected_provenance_sha256:
        raise ValueError('SDK producer provenance digest mismatch')
    producer = json.loads(data)
    if not isinstance(producer, dict):
        raise ValueError('SDK producer provenance must be an object')
    notice = (HERE / 'SAMPLER-DEPENDENCY-NOTICE.md').read_bytes()
    notice_sha = sha256(notice)
    if producer.get('sampler_modifications_notice_sha256') != notice_sha:
        raise ValueError('SDK producer modification notice differs from reviewed source')
    derivations = producer.get('sampler_dependency_derivations', {})
    native = producer.get('native_sha256')
    if not isinstance(derivations, dict) or set(derivations) != set(OUTPUTS):
        raise ValueError('SDK producer sampler derivation inventory differs')
    if not isinstance(native, dict):
        raise ValueError('SDK producer native hash inventory must be an object')
    for name, expected in OUTPUTS.items():
        row = derivations[name]
        if (not isinstance(row, dict) or native.get(name) != expected
                or row.get('output_sha256') != expected):
            raise ValueError('SDK sampler differs from reviewed derivation: ' + name)
    observed = {}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK ZIP entries')
        embedded = json.loads(bounded_entry(archive, PROVENANCE_ASSET, 4 * 1024**2))
        if embedded != {k: v for k, v in producer.items() if k != 'aar_sha256'}:
            raise ValueError('APK does not embed exact SDK producer provenance')
        if bounded_entry(archive, NOTICE_ASSET, 64 * 1024) != notice:
            raise ValueError('APK sampler modification notice missing or changed')
        expected_entries = {'lib/arm64-v8a/' + name for name in OUTPUTS}
        if {name for name in names if Path(name).name in OUTPUTS} != expected_entries:
            raise ValueError('Missing or unexpected APK sampler ABI/path')
        for name, expected in OUTPUTS.items():
            payload = bounded_entry(archive, 'lib/arm64-v8a/' + name, 32 * 1024**2)
            if sha256(payload) != expected:
                raise ValueError('Final APK sampler bytes changed after SDK validation: ' + name)
            observed[name] = {'bytes': len(payload), 'sha256': expected}
    return {'passed': True, 'apk': apk.name,
            'sdk_provenance_sha256': expected_provenance_sha256,
            'samplers': observed, 'sampler_modifications_notice_sha256': notice_sha,
            'scope': 'Exact packaged sampler bytes and notice; ELF/page, signer, model and device gates remain separate.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, action='append', required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    parser.add_argument('--expected-provenance-sha256', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = {'passed': False, 'apks': []}
    try:
        for apk in args.apk:
            result['apks'].append(verify(apk, args.provenance, args.expected_provenance_sha256))
        result['passed'] = True
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        result['error'] = str(error)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    if not result['passed']:
        raise SystemExit(result['error'])


if __name__ == '__main__':
    main()
