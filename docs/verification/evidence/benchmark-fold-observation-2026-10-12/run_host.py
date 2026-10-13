"""Reproduce the frozen host logic suite with an existing verified dependency cache.

No network, Android SDK, model weights, credentials, or dependency installation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[4])
parser.add_argument('--dependency-cache', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
receipt = json.loads(Path(__file__).with_name('host-receipt.json').read_text())
for relative, expected in receipt['sources_sha256'].items():
    assert hashlib.sha256((args.repo / relative).read_bytes()).hexdigest() == expected, relative
for name, expected in receipt['dependency_sha256'].items():
    assert hashlib.sha256((args.dependency_cache / name).read_bytes()).hexdigest() == expected, name
args.output.mkdir(parents=True, exist_ok=True)
replacements = {'<repo>': str(args.repo.resolve()), '<dependency-cache>': str(args.dependency_cache.resolve()),
                '<evidence>': str(args.output.resolve())}
for name in ('compile_command', 'test_command'):
    command = receipt[name]
    for marker, replacement in replacements.items():
        command = [part.replace(marker, replacement) for part in command]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    (args.output / (name + '.log')).write_text(result.stdout + result.stderr)
    print(result.stdout + result.stderr)
    result.check_returncode()
for relative, expected in receipt['sources_sha256'].items():
    assert hashlib.sha256((args.repo / relative).read_bytes()).hexdigest() == expected, relative
