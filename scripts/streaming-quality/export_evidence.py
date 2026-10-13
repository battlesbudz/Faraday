#!/usr/bin/env python3
"""Copy an explicit receipt allowlist; never upload the run or build trees."""
import argparse
import json
import os
from pathlib import Path
import sys
from common import *

BUILD_FILES = ('build-status.json', 'source-snapshot.json', 'diagnostic-status.json')
RUN_FILES = ('summary.json', 'input-identity.json', 'frontend.json', 'encoder-oracle.json', 'comparison.json',
    'diagnostic-pair.json',
    'reassembly/process.json', 'frontend/process.json',
    *[f'encoder-{s}/process.json' for s in ('stateful','static','adapter','eoa')],
    'projected_null/process.json', 'projected_null/result.json', 'raw/process.json', 'raw/result.json')
FORBIDDEN_KEYS = {'blob', 'pcm_data', 'mel_data', 'activation_data', 'weights', 'token_values'}


def validate_json(value):
    if isinstance(value, dict):
        need(not FORBIDDEN_KEYS.intersection(value), 'Raw input/model data cannot be exported', 'evidence_failure')
        for key, child in value.items(): validate_json(child)
    elif isinstance(value, list):
        need(len(value) <= 256, 'Large value arrays cannot be exported', 'evidence_failure')
        for child in value: validate_json(child)
    elif isinstance(value, str):
        need(len(value) <= 65536, 'Oversized string cannot be exported', 'evidence_failure')


def select(build, run):
    selected = []
    for root, names, prefix in [(build, BUILD_FILES, 'build'), (run, RUN_FILES, 'run')]:
        for name in names:
            path = root/name
            if not path.exists(): continue
            need(path.resolve().is_relative_to(root.resolve()) and not path.is_symlink(), 'Receipt escapes evidence root', 'evidence_failure')
            need(path.stat().st_size <= 4*1024**2, 'Oversized evidence file', 'evidence_failure')
            if prefix == 'build' and name == 'diagnostic-status.json':
                # This public record has a fixed enum/scalar schema and an 8 KiB
                # read cap. Private supervisor reports and child logs stay out.
                sys.path.insert(0, str(HERE/'encoder-replay'))
                from diagnostic_status import read as read_diagnostic_status
                value = read_diagnostic_status(path)
            elif prefix == 'run' and name == 'diagnostic-pair.json':
                from diagnostic_pair import read_public
                value = read_public(path)
            else:
                value = load(path)
            validate_json(value)
            selected.append((prefix+'/'+name, value))
    return selected


def export(build, run, out):
    need(not out.exists(), 'Evidence destination must be new')
    out.mkdir(parents=True)
    summary = load(run/'summary.json') if (run/'summary.json').is_file() else {
        'passed': False, 'classification': 'not_run', 'stage': 'before_inference',
        'reason': 'Quality never reached a run receipt; inspect build status and CI step failure.'}
    try:
        inventory = {}
        for name, value in select(build, run):
            target = out/name; target.parent.mkdir(parents=True, exist_ok=True); write(target, value)
            inventory[name] = describe(target)
        validate_json(summary)
        write(out/'EVIDENCE-INDEX.json', {'summary': summary, 'files': inventory,
            'ci': {k: os.environ.get(k) for k in ('GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')},
            'audio_weights_activations_uploaded': False,
            'selection': 'Explicit structured-receipt allowlist only; requests, logs, models, audio, Mel, tensors, binaries and source weights are excluded.'})
    except Exception as e:
        # Fail closed without leaking a partially selected payload.
        # OSError and parser exception strings can contain paths or input values.
        # Retain only a fixed class and phase, never their message or repr.
        kind = ('filesystem' if isinstance(e, OSError) else 'invalid_unicode' if isinstance(e, UnicodeError)
                else 'invalid_json' if isinstance(e, json.JSONDecodeError)
                else 'validation' if isinstance(e, (ValueError, GateError, TypeError, KeyError)) else 'internal')
        try:
            for p in out.rglob('*.json'): p.unlink()
            write(out/'EVIDENCE-INDEX.json', {'summary': {'passed': False, 'classification': 'evidence_export_failure'},
                'stage': 'structured_receipt_export', 'error_class': kind,
                'error': 'Structured receipt export failed; private detail is withheld.',
                'audio_weights_activations_uploaded': False})
        except Exception:
            raise GateError('evidence_failure', 'Unable to preserve bounded export failure receipt') from None
        raise GateError('evidence_failure', 'Structured receipt export failed; inspect bounded failure index') from None


def main():
    p = argparse.ArgumentParser()
    for n in ('build-dir','run-dir','out'): p.add_argument('--'+n, type=Path, required=True)
    a = p.parse_args(); export(a.build_dir, a.run_dir, a.out)
if __name__ == '__main__': main()
