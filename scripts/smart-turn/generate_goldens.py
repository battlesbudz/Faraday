#!/usr/bin/env python3
"""Explicitly regenerate synthetic/public-fixture goldens from the independently pinned Pipecat frontend.

Requires NumPy 2.3.5. This never imports the Jarvis implementation. Review any changes.
No model weights or private audio are involved. Normal tests do not regenerate expectations.
"""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import wave
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
REFERENCE = ROOT / 'third_party/smart-turn/reference_whisper_features.py'
REFERENCE_HASH = '139b047d10dee42ad5b4894489012e515a882d8f5e2bffe8ad27b30728fc9968'
assert hashlib.sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_HASH
spec = importlib.util.spec_from_file_location('independent_reference', REFERENCE)
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)
out = Path(__file__).with_name('fixtures')
out.mkdir(exist_ok=True)
t = np.arange(16000, dtype=np.float64) / 16000
long_t = np.arange(9 * 16000, dtype=np.float64) / 16000
noise = []
state = 123456789
for _ in range(128000):
    state = (1664525 * state + 1013904223) & 0xffffffff
    noise.append(((state >> 16) & 0xffff) - 32768)
cases = {
    'silence_8s': np.zeros(128000, dtype='<i2'),
    'tone_440hz_1s': np.rint(12000 * np.sin(2 * np.pi * 440 * t)).astype('<i2'),
    'noise_lcg_8s': np.asarray(noise, dtype='<i2'),
    'chirp_9s': np.rint(8000 * np.sin(2 * np.pi * (100 * long_t + 0.5 * 120 * long_t ** 2))).astype('<i2'),
    'quiet_one_lsb': np.where(np.arange(16000) % 37 < 18, 1, -1).astype('<i2'),
    'dc_short': np.full(159, 5000, dtype='<i2'),
    'impulse_one_sample': np.asarray([32767], dtype='<i2'),
}
public = ROOT / 'app/src/test/resources/recorded-audio/librispeech-1089-134686-0000.wav'
assert hashlib.sha256(public.read_bytes()).hexdigest() == '0b1785dba56f22af426ccb25d318f7e103558fd40e1c3ab064b455dba2afae12'
with wave.open(str(public), 'rb') as source:
    assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, 16000)
    cases['public_librispeech_last8s'] = np.frombuffer(source.readframes(source.getnframes()), dtype='<i2')
records = []
for name, pcm in cases.items():
    audio = pcm.astype(np.float32) / 32768.0
    audio = audio[-128000:]
    audio = np.pad(audio, (128000 - len(audio), 0))
    features = reference.compute_whisper_log_mel_features(audio, do_normalize=True).astype('<f4')
    record = {'id': name, 'samples': len(pcm), 'shape': [80, 800]}
    for kind, data in [('pcm16le', pcm.tobytes()), ('features.f32le', features.tobytes())]:
        filename = name + '.' + kind + '.gz'
        (out / filename).write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
        record[kind] = {'file': filename, 'sha256_uncompressed': hashlib.sha256(data).hexdigest(), 'bytes_uncompressed': len(data)}
    records.append(record)
manifest = {'schema_version': 1, 'reference_sha256': REFERENCE_HASH, 'numpy_version': np.__version__,
    'absolute_tolerance': 0.000002, 'private_audio_used': False,
    'public_fixture_attribution': 'LibriSpeech 1089-134686-0000, CC-BY-4.0; see app/src/test/resources/recorded-audio/manifest.json. Last 8 s retained for features only.',
    'cases': records}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
