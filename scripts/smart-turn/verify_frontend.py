#!/usr/bin/env python3
"""Reconstructed weight-free verifier against unchanged pre-rollback decoded golden pins.

No expectation generation, model download, model inference or network access occurs here.
"""
import argparse
from array import array
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_SHA256 = '1b8bf8d17de8469aca6b40c1613af35078b1af9cf90962c7fd0b8fe81bae1420'
REFERENCE_SHA256 = '139b047d10dee42ad5b4894489012e515a882d8f5e2bffe8ad27b30728fc9968'


def floats_le(data):
    values = array('f'); values.frombytes(data)
    if sys.byteorder != 'little': values.byteswap()
    return values


def verify(root=ROOT):
    fixtures = root / 'scripts/smart-turn/fixtures'
    manifest_bytes = (fixtures / 'manifest.json').read_bytes()
    if hashlib.sha256(manifest_bytes).hexdigest() != MANIFEST_SHA256:
        raise ValueError('Frozen fixture manifest changed; independent review required')
    manifest = json.loads(manifest_bytes)
    reference = root / 'third_party/smart-turn/reference_whisper_features.py'
    if hashlib.sha256(reference.read_bytes()).hexdigest() != REFERENCE_SHA256:
        raise ValueError('Pinned independent reference changed')
    native = root / 'app/src/main/cpp/smartturn'
    records = []
    with tempfile.TemporaryDirectory(prefix='smart-turn-frontend-') as directory:
        work = Path(directory)
        command = ['g++', '-O2', '-std=c++17', '-I' + str(native),
                   str(root / 'scripts/smart-turn/frontend_probe.cc'), str(native / 'whisper_features.cc'),
                   '-o', str(work / 'frontend_probe')]
        subprocess.run(command, check=True, timeout=90, capture_output=True)
        for case in manifest['cases']:
            decoded = {}
            for kind in ('pcm16le', 'features.f32le'):
                pin = case[kind]
                data = gzip.decompress((fixtures / pin['file']).read_bytes())
                if len(data) != pin['bytes_uncompressed'] or hashlib.sha256(data).hexdigest() != pin['sha256_uncompressed']:
                    raise ValueError('Frozen fixture data changed: ' + case['id'])
                decoded[kind] = data
            pcm = array('h'); pcm.frombytes(decoded['pcm16le'])
            if sys.byteorder != 'little': pcm.byteswap()
            audio = array('f', (value / 32768.0 for value in pcm[-128000:]))
            if sys.byteorder != 'little': audio.byteswap()
            source = work / 'input.f32le'; output = work / 'actual.f32le'
            source.write_bytes(audio.tobytes())
            subprocess.run([str(work / 'frontend_probe'), str(source), str(output)], check=True, timeout=10, capture_output=True)
            actual = floats_le(output.read_bytes()); expected = floats_le(decoded['features.f32le'])
            if len(actual) != 64000: raise ValueError('Incorrect feature shape')
            error = max(abs(a - b) for a, b in zip(actual, expected))
            if not error <= manifest['absolute_tolerance']:
                raise ValueError('Frontend differs from independent frozen reference: ' + case['id'])
            records.append({'id': case['id'], 'max_abs_error': error, 'passed': True})
        # Negative controls: nonfinite audio must not turn into plausible evidence.
        (work / 'input.f32le').write_bytes(array('f', [float('nan')]).tobytes())
        rejected = subprocess.run([str(work / 'frontend_probe'), str(work / 'input.f32le'), str(work / 'invalid.f32le')],
                                  timeout=10, capture_output=True)
        if rejected.returncode == 0: raise ValueError('Nonfinite input was admitted')
    return {'scope': 'Fresh reconstructed verifier; original decoded expectations unchanged; no model/device inference',
            'manifest_sha256': MANIFEST_SHA256, 'reference_sha256': REFERENCE_SHA256,
            'frontend_sha256': hashlib.sha256((native / 'whisper_features.cc').read_bytes()).hexdigest(),
            'shape': [80, 800], 'absolute_tolerance': manifest['absolute_tolerance'], 'cases': records,
            'nonfinite_input_rejected': True, 'weights_used': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    result = verify()
    text = json.dumps(result, indent=2) + '\n'
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True); args.receipt.write_text(text)
    print(text, end='')
