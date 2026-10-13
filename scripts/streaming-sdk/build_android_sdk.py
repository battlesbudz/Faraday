#!/usr/bin/env python3
"""Reviewed hosted-build proposal. Default is a dry plan, never license acceptance.

Requires an already licensed/installed NDK r28b, SDK35 and JDK21. Final reviewed
patch bytes and their digest must be supplied; there is no floating SDK branch.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
SDK_PIN = '924e79c91542761242244e4f1651851f822e4cbb'
LITERT_PIN = '0ff28117f1cb5556d0e015bf80b773f74e2bee51'
BAZEL = {'filename': 'bazel-7.6.1-linux-x86_64', 'bytes': 57509759,
         'sha256': 'ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025',
         'url': 'https://github.com/bazelbuild/bazel/releases/download/7.6.1/bazel-7.6.1-linux-x86_64'}


def run(argv, cwd=None, env=None, timeout=None):
    print('+ ' + ' '.join(map(str, argv)), flush=True)
    subprocess.run(list(map(str, argv)), cwd=cwd, env=env, check=True, timeout=timeout)


def verify_packaged_kotlin_contract(java, compiler_cp, runtime_cp, classes_jar, out):
    """SDK tests may inspect internal serialization; app tests may not.

    Compile a separate SDK-owned friend test module against the actual production
    jar. Never add its output to the production class allowlist or packaged AAR.
    """
    source = HERE / 'PackagedSealedContentContract.kt'
    test_classes = out / 'packaged-sdk-contract-classes'
    test_classes.mkdir()
    receipt_path = out / 'packaged-kotlin-contract.json'
    run([java, '-Xmx384m', '-XX:ActiveProcessorCount=1', '-cp', compiler_cp,
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
         '-jvm-target', '17', '-module-name', 'packaged_sdk_contract',
         '-Xfriend-paths=' + str(classes_jar),
         '-classpath', str(classes_jar) + ':' + runtime_cp,
         '-d', test_classes, source], timeout=60)
    run([java, '-Xmx128m', '-XX:ActiveProcessorCount=1', '-cp',
         str(test_classes) + ':' + str(classes_jar) + ':' + runtime_cp,
         'com.google.ai.edge.litertlm.PackagedSealedContentContractKt', receipt_path], timeout=20)
    result = json.loads(receipt_path.read_text())
    if result.get('passed') is not True or result.get('checks') != 13:
        raise ValueError('Packaged SDK serialization contract did not pass completely')
    result.update(classes_jar_sha256=hashlib.sha256(classes_jar.read_bytes()).hexdigest(),
                  test_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
    receipt_path.write_text(json.dumps(result, indent=2) + '\n')
    return result


def download(pin, destination):
    kind = 'sha256' if 'sha256' in pin else 'sha1'
    digest = hashlib.new(kind)
    count = 0
    temp = destination.with_name(destination.name + '.partial')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(pin['url'], timeout=60) as source, temp.open('xb') as target:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            count += len(chunk)
            if count > pin['bytes']:
                raise ValueError(f'Oversized download: {destination.name}')
            digest.update(chunk)
            target.write(chunk)
    if count != pin['bytes'] or digest.hexdigest() != pin[kind]:
        raise ValueError(f'Download checksum mismatch: {destination.name}')
    temp.replace(destination)


def prepare_patchelf(downloads):
    pin = json.loads((HERE / 'patchelf-tool.json').read_text())
    archive = downloads / pin['filename']
    download(pin, archive)
    target = downloads / 'patchelf'
    with tarfile.open(archive, 'r:gz') as source:
        members = [member for member in source.getmembers()
                   if member.name.removeprefix('./') == pin['binary_path']]
        if len(members) != 1 or not members[0].isfile() or members[0].size != pin['binary_bytes']:
            raise ValueError('Pinned PatchELF archive has unexpected binary member')
        binary = source.extractfile(members[0]).read(pin['binary_bytes'] + 1)
    if len(binary) != pin['binary_bytes'] or hashlib.sha256(binary).hexdigest() != pin['binary_sha256']:
        raise ValueError('Pinned PatchELF binary mismatch')
    target.write_bytes(binary)
    target.chmod(0o755)
    return target, pin


def checked_overlay(text):
    old = 'android_ndk_repository(name = "androidndk")'
    if text.count(old) != 1:
        raise ValueError('NDK declaration changed; re-review API30 overlay')
    return text.replace(old, 'android_ndk_repository(name = "androidndk", api_level = 30)')


def checked_owner_overlay(text):
    """Keep the tested Linux owner topology, statically link Android dependencies."""
    marker = '    name = "libnative_audio_owner_jni.so",'
    prefix, separator, suffix = text.partition(marker)
    normal, separator2, test = suffix.partition('# Deliberately separate test library:')
    if not separator or not separator2 or normal.count('    linkstatic = False,') != 1:
        raise ValueError('Owner normal target changed; re-review Android linkstatic overlay')
    normal = normal.replace('    linkstatic = False,', '''    linkstatic = select({
        "@platforms//os:android": True,
        "//conditions:default": False,
    }),''')
    return prefix + separator + normal + separator2 + test


def verify_reviewed_sources(repo, manifest):
    for relative, expected in manifest['files'].items():
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or not re.fullmatch('[0-9a-f]{64}', expected):
            raise ValueError('Unsafe reviewed source manifest')
        if hashlib.sha256((repo / path).read_bytes()).hexdigest() != expected:
            raise ValueError(f'Reviewed SDK source changed: {relative}')


def verify_owner_sources(repo, inventory=None):
    inventory = inventory or json.loads((HERE / 'native-owner-production.json').read_text())
    for relative, expected in inventory['owner_source_sha256'].items():
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or not re.fullmatch(r'[0-9a-f]{64}', expected):
            raise ValueError('Unsafe native-owner source inventory')
        target = repo / path
        if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Reviewed native owner source changed: {relative}')
    sources = []
    for relative in inventory['kotlin_sources']:
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts or path.suffix != '.kt':
            raise ValueError('Unsafe production Kotlin source allowlist')
        target = repo / path
        if not target.is_file():
            raise ValueError(f'Missing production Kotlin source: {relative}')
        sources.append(target)
    if len(sources) != len(set(sources)):
        raise ValueError('Duplicate production Kotlin sources')
    return inventory, sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', help='Execute only after parent-controlled review/authorization')
    parser.add_argument('--patch', type=Path)
    parser.add_argument('--patch-sha256')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--page-auditor', type=Path)
    parser.add_argument('--reviewed-source', type=Path)
    parser.add_argument('--jobs', type=int, choices=[1, 2], default=2)
    args = parser.parse_args()
    if not args.run:
        print(json.dumps({'sdk_commit': SDK_PIN, 'litert_commit': LITERT_PIN,
                          'ndk': '28.1.13356709', 'native_api': 30, 'target': 'arm64-v8a',
                          'requires': ['reviewed source-only patch + SHA256', 'licensed hosted SDK/NDK',
                                       'JDK21', 'host clang/clang++', 'unchanged Jarvis page auditor'],
                          'performs': ['isolated pinned SDK checkout', 'verified runtime/tool downloads',
                                       'API30 build overlay', 'SDK + normal owner JNI crossbuild', 'allowlisted production Kotlin compile',
                                       'DT_NEEDED + dlopen closure', 'ARM64/16KB audit', 'source-built AAR'],
                          'does_not': ['accept licenses', 'install software globally', 'download Gemma weights',
                                       'change app dependency', 'sign/install APK', 'push or publish']}, indent=2))
        return
    for name in ['patch', 'patch_sha256', 'out', 'page_auditor', 'reviewed_source']:
        if getattr(args, name) is None:
            parser.error(f'--{name.replace("_", "-")} is required with --run')
    if not re.fullmatch(r'[0-9a-f]{64}', args.patch_sha256):
        raise ValueError('Expected a reviewed lower-case SHA256')
    patch = args.patch.resolve().read_bytes()
    if hashlib.sha256(patch).hexdigest() != args.patch_sha256:
        raise ValueError('Reviewed source patch changed')
    manifest = json.loads(args.reviewed_source.read_text())
    if (manifest['patch_sha256'] != args.patch_sha256 or manifest['sdk_commit'] != SDK_PIN
            or manifest['litert_commit'] != LITERT_PIN):
        raise ValueError('Reviewed manifest does not match pinned SDK patch')
    if b'GIT binary patch' in patch or re.search(rb'^\+\+\+ b/.*\.(?:tflite|litertlm|safetensors|so|aar|jar|bin)$', patch, re.M):
        raise ValueError('Binary/model artifacts cannot be part of the source patch')
    ndk = Path(os.environ['ANDROID_NDK_HOME']).resolve()
    if not re.search(r'^Pkg\.Revision\s*=\s*28\.1\.13356709\s*$', (ndk / 'source.properties').read_text(), re.M):
        raise ValueError('Expected exactly NDK r28b, separate from app NDK r27c')
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'version "21[.\"]', version):
        raise ValueError('Expected JDK21')
    sdk_home = Path(os.environ['ANDROID_HOME']).resolve()
    if not (sdk_home / 'platforms/android-35/android.jar').is_file():
        raise ValueError('SDK35 must already be installed through an authorized route')
    cc = shutil.which(os.environ.get('HOST_CC', 'clang'))
    cxx = shutil.which(os.environ.get('HOST_CXX', 'clang++'))
    if not cc or not cxx:
        raise ValueError('A host Clang C/C++ compiler is required')
    if args.out.exists():
        raise ValueError('Use a fresh output directory; failed evidence is retained')
    if shutil.disk_usage(args.out.parent).free < 12 * 1024**3:
        raise ValueError('Less than12GiB free before source crossbuild')
    args.out.mkdir()
    out = args.out.resolve()
    repo, downloads = out / 'sdk', out / 'downloads'
    downloads.mkdir()
    env = dict(os.environ, GIT_LFS_SKIP_SMUDGE='1')
    run(['git', 'init', repo])
    run(['git', 'remote', 'add', 'origin', 'https://github.com/google-ai-edge/LiteRT-LM.git'], repo)
    run(['git', 'fetch', '--depth=1', 'origin', SDK_PIN], repo, env)
    run(['git', 'checkout', '--detach', SDK_PIN], repo, env)
    run(['git', 'apply', '--check', args.patch.resolve()], repo)
    run(['git', 'apply', args.patch.resolve()], repo)
    verify_reviewed_sources(repo, manifest)
    owner_inventory, production_sources = verify_owner_sources(repo)
    workspace = repo / 'WORKSPACE'
    if LITERT_PIN not in workspace.read_text():
        raise ValueError('SDK LiteRT dependency pin changed')
    before_overlay = workspace.read_bytes()
    workspace.write_text(checked_overlay(before_overlay.decode()))
    owner_build = repo / 'experimental/native_audio_owner_jni_20261006/BUILD'
    owner_before = owner_build.read_bytes()
    owner_build.write_text(checked_owner_overlay(owner_before.decode()))
    overlays = {str(path.relative_to(repo)): {
        'before_sha256': hashlib.sha256(before).hexdigest(),
        'after_sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        for path, before in [(workspace, before_overlay), (owner_build, owner_before)]}
    prebuilts = json.loads((HERE / 'android-prebuilts.json').read_text())
    for pin in prebuilts:
        pointer = subprocess.check_output(['git', 'show', SDK_PIN + ':' + pin['path']], cwd=repo, text=True)
        if f'oid sha256:{pin["sha256"]}\nsize {pin["bytes"]}\n' not in pointer:
            raise ValueError('Pinned upstream runtime identity changed')
        download(pin, repo / pin['path'])
    patchelf, patchelf_pin = prepare_patchelf(downloads)
    sampler_contract = out / 'sampler-dependency-contract.json'
    run([sys.executable, HERE / 'verify_sampler_derivation.py', '--input-dir', repo / 'prebuilt/android_arm64',
         '--tool', patchelf, '--out', sampler_contract], timeout=60)
    sampler_checks = json.loads(sampler_contract.read_text())
    if sampler_checks.get('passed') is not True or sampler_checks.get('check_count') != 24:
        raise ValueError('Sampler dependency derivation contracts did not pass completely')
    download(BAZEL, downloads / BAZEL['filename'])
    bazel = downloads / BAZEL['filename']
    bazel.chmod(0o755)
    kotlin_pins = json.loads((HERE / 'kotlin-dependencies.json').read_text())
    for pin in kotlin_pins:
        download(pin, downloads / pin['filename'])
    base_pin = json.loads((HERE / 'official-litert-aar-metadata.json').read_text())
    base = downloads / 'litertlm-android-0.16.0.aar'
    download(base_pin, base)
    receipt = {'sdk_commit': SDK_PIN, 'litert_commit': LITERT_PIN,
               'reviewed_patch_sha256': args.patch_sha256,
               'reviewed_source_sha256': hashlib.sha256(args.reviewed_source.read_bytes()).hexdigest(),
               'build_overlays': overlays,
               'workflow_identity': {key: os.environ.get(key, '') for key in
                   ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA', 'GITHUB_REPOSITORY']},
               'workspace_before_api30_sha256': hashlib.sha256(before_overlay).hexdigest(),
               'workspace_after_api30_sha256': hashlib.sha256(workspace.read_bytes()).hexdigest(),
               'native_api': 30, 'ndk_revision': '28.1.13356709', 'bazel': BAZEL,
               'java': version.strip(), 'host_clang': subprocess.check_output([cc, '--version'], text=True).strip(),
               'kotlin_dependencies': kotlin_pins, 'runtime_prebuilts': prebuilts,
               'sampler_dependency_tool': patchelf_pin,
               'sampler_dependency_contract': sampler_checks,
               'sampler_dependency_recipe_sha256': {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                   for name in ['repair_sampler_dependencies.py', 'verify_sampler_derivation.py', 'patchelf-tool.json']},
               'owner_inventory_sha256': hashlib.sha256((HERE / 'native-owner-production.json').read_bytes()).hexdigest(),
               'native_owner_target': owner_inventory['normal_target'],
               'production_kotlin_source_sha256': {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production_sources}}
    source_receipt = out / 'source-receipt.json'
    source_receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    run([bazel, '--batch', f'--output_user_root={out / "bazel"}', f'--server_javabase={java.parent.parent}',
         '--host_jvm_args=-Xmx2048m', 'build', '--config=android_arm64', f'--jobs={args.jobs}',
         '--local_resources=memory=6144', '--remote_executor=', '--remote_cache=', '--noremote_upload_local_results',
         f'--repo_env=CC={cc}', f'--repo_env=CXX={cxx}',
         '--linkopt=-Wl,-z,max-page-size=16384', '--linkopt=-Wl,-z,common-page-size=16384',
         '//kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni', owner_inventory['normal_target']], repo, env)
    classes = out / 'kotlin-classes'
    classes.mkdir()
    compiler_cp = ':'.join(str(downloads / p['filename']) for p in kotlin_pins)
    runtime_names = ['kotlin-stdlib-2.3.21.jar', 'annotations-13.0.jar', 'kotlin-reflect-2.3.21.jar',
                     'kotlinx-coroutines-core-jvm-1.9.0.jar', 'gson-2.13.2.jar']
    runtime_cp = ':'.join(str(downloads / n) for n in runtime_names)
    sources = production_sources  # Exact allowlist, never experimental/test globs.
    run([java, '-Xmx1024m', '-XX:ActiveProcessorCount=2', '-cp', compiler_cp,
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
         '-classpath', runtime_cp, '-d', classes, *sources])
    from package_android_aar import write_archive
    classes_jar = out / 'classes.jar'
    write_archive(classes_jar, {str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*') if p.is_file()})
    receipt['packaged_kotlin_contract'] = verify_packaged_kotlin_contract(
        java, compiler_cp, runtime_cp, classes_jar, out)
    source_receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    native = repo / 'bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni'
    solib = sorted((repo / 'bazel-bin').glob('_solib*'))
    command = [sys.executable, HERE / 'package_android_aar.py', '--base-aar', base, '--classes-jar', classes_jar,
               '--ndk', ndk, '--page-auditor', args.page_auditor.resolve(), '--source-receipt', source_receipt,
               '--patchelf', patchelf,
               '--out', out / 'litertlm-android-0.16.0-sealed-audio-arm64.aar']
    owner_native = repo / 'bazel-bin/experimental/native_audio_owner_jni_20261006'
    for path in [native, owner_native, repo / 'prebuilt/android_arm64', *solib]:
        command += ['--native-directory', path]
    run(command)


if __name__ == '__main__':
    main()
