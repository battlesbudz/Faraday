#!/usr/bin/env python3
"""Fail closed before Gradle if this exact run's reviewed SDK bytes drift."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import zipfile

from build_android_sdk import SDK_PIN, LITERT_PIN
from producer_identity import workflow_identity
from package_android_aar import ROOTS, OWNER, inspect_classes, sha256
from repair_sampler_dependencies import TARGETS as SAMPLER_INPUTS, OUTPUTS as SAMPLER_OUTPUTS, PROVIDER, TOOL_SHA256

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def validate(aar, provenance_path, reviewed_path, patch_path, expected_aar=None,
             expected_provenance=None, identity=None, source_receipt=None, expected_source_receipt=None):
    for path in [aar, provenance_path, reviewed_path, patch_path]:
        if not path.is_file():
            raise ValueError(f'Missing reviewed SDK input: {path}')
    for path, expected in [(aar, expected_aar), (provenance_path, expected_provenance)]:
        if expected is not None and (not re.fullmatch('[0-9a-f]{64}', expected) or sha256(path) != expected):
            raise ValueError(f'Producer SHA256 mismatch: {path.name}')
    reviewed = json.loads(reviewed_path.read_text())
    if (reviewed['sdk_commit'] != SDK_PIN or reviewed['litert_commit'] != LITERT_PIN
            or reviewed['patch_sha256'] != sha256(patch_path)):
        raise ValueError('Reviewed source identity mismatch')
    provenance = json.loads(provenance_path.read_text())
    if provenance.get('aar_sha256') != sha256(aar):
        raise ValueError('AAR checksum mismatch')
    source = provenance['source']
    if expected_source_receipt is not None and source_receipt is None:
        raise ValueError('Expected source receipt digest requires its exact file')
    if source_receipt is not None:
        if not source_receipt.is_file():
            raise ValueError('Missing SDK source receipt')
        if expected_source_receipt is not None and (not re.fullmatch('[0-9a-f]{64}', expected_source_receipt)
                or sha256(source_receipt) != expected_source_receipt):
            raise ValueError('Producer source receipt SHA256 mismatch')
        if json.loads(source_receipt.read_text()) != source:
            raise ValueError('Source receipt differs from AAR embedded source provenance')
    if (source.get('sdk_commit') != SDK_PIN or source.get('litert_commit') != LITERT_PIN
            or source.get('reviewed_patch_sha256') != reviewed['patch_sha256']
            or source.get('reviewed_source_sha256') != sha256(reviewed_path)
            or source.get('native_api') != 30 or source.get('ndk_revision') != '28.1.13356709'):
        raise ValueError('AAR source/toolchain provenance does not match reviewed inputs')
    if provenance.get('owner_inventory_sha256') != sha256(HERE / 'native-owner-production.json'):
        raise ValueError('Production class/JNI inventory changed')
    if provenance.get('page_auditor_sha256') != sha256(ROOT / 'scripts/check_page_sizes.py'):
        raise ValueError('Native page auditor identity changed')
    expected_recipe = {name: sha256(HERE / name) for name in
                       ['repair_sampler_dependencies.py', 'verify_sampler_derivation.py', 'patchelf-tool.json']}
    if source.get('sampler_dependency_recipe_sha256') != expected_recipe:
        raise ValueError('Sampler dependency recipe provenance changed')
    if source.get('sampler_dependency_tool') != json.loads((HERE / 'patchelf-tool.json').read_text()):
        raise ValueError('Sampler dependency tool provenance changed')
    contract = source.get('sampler_dependency_contract', {})
    if contract.get('passed') is not True or contract.get('check_count') != 24:
        raise ValueError('Sampler dependency contracts missing')
    for key, value in (identity or {}).items():
        if not value or source.get('workflow_identity', {}).get(key) != value:
            raise ValueError(f'SDK artifact is not from this exact workflow identity: {key}')
    if set(provenance.get('sdk_jni_exports', [])) != set(OWNER['sdk_jni_exports']):
        raise ValueError('Checked Session/Conversation JNI evidence missing')
    if set(provenance.get('owner_jni_exports', [])) != set(OWNER['owner_jni_exports']):
        raise ValueError('Normal owner JNI evidence missing')
    if set(provenance.get('explicit_dlopen_roots', [])) != set(ROOTS):
        raise ValueError('Explicit GPU/runtime root inventory drift')
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate AAR ZIP entries')
        embedded = json.loads(archive.read('assets/litert-lm-source-provenance.json'))
        if embedded != {k: v for k, v in provenance.items() if k != 'aar_sha256'}:
            raise ValueError('Embedded and external provenance differ')
        classes = archive.read('classes.jar')
        notice_sha = sha256(HERE / 'SAMPLER-DEPENDENCY-NOTICE.md')
        if (provenance.get('sampler_modifications_notice_sha256') != notice_sha or
                hashlib.sha256(archive.read('assets/litert-lm-sampler-modifications.md')).hexdigest() != notice_sha):
            raise ValueError('Sampler modification/license notice missing or changed')
        if hashlib.sha256(classes).hexdigest() != provenance['classes_sha256']:
            raise ValueError('SDK class bytes do not match provenance')
        inspect_classes(classes)
        native = {n.removeprefix('jni/arm64-v8a/'): n for n in names if n.startswith('jni/')}
        if set(native) != set(provenance['native_sha256']) or not set(ROOTS) <= set(native):
            raise ValueError('Unexpected ABI or incomplete native closure')
        for name, entry in native.items():
            if hashlib.sha256(archive.read(entry)).hexdigest() != provenance['native_sha256'][name]:
                raise ValueError(f'Native bytes changed: {name}')
        system = set(provenance['ndk_api30_system_libraries'])
        if 'libc++_shared.so' in system:
            raise ValueError('The C++ runtime cannot be treated as a platform library')
        dependencies = provenance['native_dependencies']
        if set(dependencies) != set(native):
            raise ValueError('Incomplete dependency evidence')
        if any(dep not in native and dep not in system for deps in dependencies.values() for dep in deps):
            raise ValueError('Unresolved packaged runtime dependency')
        derivations = provenance.get('sampler_dependency_derivations', {})
        if set(derivations) != set(SAMPLER_INPUTS):
            raise ValueError('Sampler dependency derivation receipt missing')
        for name, input_sha in SAMPLER_INPUTS.items():
            value = derivations[name]
            if (value.get('input_sha256') != input_sha or value.get('output_sha256') != SAMPLER_OUTPUTS[name]
                    or provenance['native_sha256'][name] != SAMPLER_OUTPUTS[name]
                    or value.get('provider') != PROVIDER or dependencies[name] != value.get('derived_needed')
                    or dependencies[name] != [PROVIDER] + value.get('original_needed', [])
                    or value.get('tool_sha256') != TOOL_SHA256
                    or value.get('original_relro_unchanged') is not True
                    or value.get('original_runtime_sections_unchanged') is not True
                    or value.get('symbol_abi_unchanged') is not True
                    or value.get('new_metadata_load_read_only') is not True
                    or not isinstance(value.get('relocation_targets_verified'), int)
                    or value['relocation_targets_verified'] <= 0):
                raise ValueError('Sampler dependency or hardening evidence changed: ' + name)
    result = {'aar': str(aar.resolve()), 'sha256': sha256(aar),
            'provenance': str(provenance_path.resolve()),
            'provenance_sha256': sha256(provenance_path),
            'patch_sha256': reviewed['patch_sha256']}
    if source_receipt is not None:
        result['source_receipt_sha256'] = sha256(source_receipt)
    return result


def check_stl(aar, ndk):
    """Do not make a guessed runtime choice or hide Gradle's duplicate-STL error."""
    with zipfile.ZipFile(aar) as archive:
        name = 'jni/arm64-v8a/libc++_shared.so'
        if name not in archive.namelist():
            return
        paths = list(ndk.glob('toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'))
        if len(paths) != 1:
            raise ValueError('Cannot inspect the app NDK27 C++ runtime')
        sdk_hash = hashlib.sha256(archive.read(name)).hexdigest()
        raise ValueError('SDK closure requires libc++_shared.so, which collides with the Sherpa NDK27 copy. '
                         f'SDK SHA256={sdk_hash}; Sherpa SHA256={sha256(paths[0])}. '
                         'Review symbols and a deterministic single-runtime adapter; never pickFirst or omit required STL.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    parser.add_argument('--reviewed-source', type=Path, default=ROOT / 'third_party/litert-lm-0.16.0/reviewed-source.json')
    parser.add_argument('--patch', type=Path, default=ROOT / 'third_party/litert-lm-0.16.0/PATCH.diff')
    parser.add_argument('--expected-aar-sha256')
    parser.add_argument('--expected-provenance-sha256')
    parser.add_argument('--source-receipt', type=Path)
    parser.add_argument('--expected-source-receipt-sha256')
    parser.add_argument('--require-digests', action='store_true')
    parser.add_argument('--check-workflow', action='store_true')
    parser.add_argument('--producer-attempt', help='Original successful producer attempt, retained by needs outputs')
    parser.add_argument('--app-ndk', type=Path)
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    if args.require_digests and (not args.expected_aar_sha256 or not args.expected_provenance_sha256):
        parser.error('Both exact producer digests are required before Gradle')
    if args.require_digests and args.source_receipt and not args.expected_source_receipt_sha256:
        parser.error('Exact producer source receipt digest is required')
    identity = workflow_identity(os.environ, args.producer_attempt) if args.check_workflow else {}
    result = validate(args.aar, args.provenance, args.reviewed_source, args.patch,
                      args.expected_aar_sha256, args.expected_provenance_sha256, identity,
                      args.source_receipt, args.expected_source_receipt_sha256)
    if args.app_ndk:
        check_stl(args.aar, args.app_ndk)
    if args.github_output:
        with args.github_output.open('a') as output:
            for key, value in result.items():
                if '\n' in value or '\r' in value:
                    raise ValueError('Unsafe GitHub output')
                output.write(f'{key}={value}\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
