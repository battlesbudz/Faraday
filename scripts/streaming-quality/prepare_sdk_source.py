#!/usr/bin/env python3
"""Reproduce SDK source authenticated by the successful Android producer.

The workflow first binds this receipt to the exact AAR, producer artifact and
source. This helper reproduces its reviewed inputs; it does not turn an arbitrary
receipt into an authenticity anchor or compile/download any model.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

from common import SDK_PIN, LITERT_PIN, describe, digest, download, load, need, sha, sdk_snapshot, verify_recipe_sources

SDK_HELPERS = Path(__file__).resolve().parents[1] / 'streaming-sdk'
sys.path.insert(0, str(SDK_HELPERS))
import build_android_sdk as android


def validate_inputs(receipt, patch, reviewed_source):
    manifest = load(reviewed_source)
    patch_bytes = Path(patch).read_bytes()
    need(receipt['sdk_commit'] == manifest['sdk_commit'] == SDK_PIN == android.SDK_PIN,
         'SDK source pin differs from producer')
    need(receipt['litert_commit'] == manifest['litert_commit'] == LITERT_PIN == android.LITERT_PIN,
         'LiteRT source pin differs from producer')
    need(receipt['reviewed_patch_sha256'] == manifest['patch_sha256'] == sha(patch),
         'Reviewed patch differs from producer')
    need(receipt['reviewed_source_sha256'] == sha(reviewed_source),
         'Reviewed source inventory differs from producer')
    need(b'GIT binary patch' not in patch_bytes and not re.search(
        rb'^\+\+\+ b/.*\.(?:tflite|litertlm|safetensors|so|aar|jar|bin)$', patch_bytes, re.M),
        'Only reviewed source patches are accepted')
    inventory = load(SDK_HELPERS/'native-owner-production.json')
    need(receipt['owner_inventory_sha256'] == sha(SDK_HELPERS/'native-owner-production.json'),
         'Native owner inventory differs from producer')
    need(receipt['native_owner_target'] == inventory['normal_target'], 'Native owner target differs')
    need(receipt['native_api'] == 30 and receipt['ndk_revision'] == '28.1.13356709',
         'Android producer toolchain contract differs')
    need(receipt['bazel'] == android.BAZEL, 'Bazel pin differs from producer')
    need(set(receipt['production_kotlin_source_sha256']) == set(inventory['kotlin_sources']),
         'Production Kotlin source set differs from producer')
    return manifest


def apply_verified_sources(sdk, receipt, manifest):
    """Validate the patched, pre-overlay source before applying exact overlays."""
    sdk = Path(sdk)
    android.verify_reviewed_sources(sdk, manifest)
    _, sources = android.verify_owner_sources(sdk)
    need({str(p.relative_to(sdk)): sha(p) for p in sources} == receipt['production_kotlin_source_sha256'],
         'Production Kotlin bytes differ from Android producer')
    overlays = {'WORKSPACE': android.checked_overlay,
                'experimental/native_audio_owner_jni_20261006/BUILD': android.checked_owner_overlay}
    need(set(receipt['build_overlays']) == set(overlays), 'Unreviewed source overlay')
    for relative, transform in overlays.items():
        path = sdk/relative
        expected = receipt['build_overlays'][relative]
        need(sha(path) == expected['before_sha256'], 'Overlay input differs from producer')
        path.write_text(transform(path.read_text()))
        need(sha(path) == expected['after_sha256'], 'Overlay output differs from producer')
    need(sha(sdk/'WORKSPACE') == receipt['workspace_after_api30_sha256'],
         'Final WORKSPACE differs from producer')


def run(argv, cwd=None):
    subprocess.run(list(map(str, argv)), cwd=cwd, check=True, timeout=180,
                   stdin=subprocess.DEVNULL, env=dict(os.environ, GIT_LFS_SKIP_SMUDGE='1'))


def prepare(args):
    verify_recipe_sources()
    receipt = load(args.android_receipt)
    manifest = validate_inputs(receipt, args.patch, args.reviewed_source)
    need(not args.sdk.exists() and not args.sdk.is_symlink(), 'Use a fresh SDK source directory')
    args.sdk.parent.mkdir(parents=True, exist_ok=True)
    sdk = args.sdk.resolve()
    run(['git', 'init', sdk])
    run(['git', 'remote', 'add', 'origin', 'https://github.com/google-ai-edge/LiteRT-LM.git'], sdk)
    run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', SDK_PIN], sdk)
    run(['git', 'checkout', '--detach', SDK_PIN], sdk)
    run(['git', 'apply', '--check', args.patch.resolve()], sdk)
    run(['git', 'apply', args.patch.resolve()], sdk)
    apply_verified_sources(sdk, receipt, manifest)
    snapshot = sdk_snapshot(sdk)
    args.bazel.parent.mkdir(parents=True, exist_ok=True)
    download(android.BAZEL, args.bazel, seconds=180)
    args.bazel.chmod(0o755)
    return {'sdk_commit': SDK_PIN, 'litert_commit': LITERT_PIN,
            'android_source_receipt_sha256': sha(args.android_receipt),
            'reviewed_patch_sha256': sha(args.patch), 'source_snapshot_sha256': digest(snapshot),
            'bazel': describe(args.bazel), 'model_downloaded': False, 'compilation_run': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('android-receipt', 'patch', 'reviewed-source', 'sdk', 'bazel'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    import json
    print(json.dumps(prepare(args), indent=2))


if __name__ == '__main__':
    main()
