#!/usr/bin/env python3
"""Package a reviewed source-built SDK; no downloads, compilation or model inputs.

DT_NEEDED closure is in addition to all explicit GPU dlopen roots. The existing
Jarvis native-page auditor is reused unchanged; APK ZIP alignment is checked later
on the final release/compact APKs by the ordinary release gate.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile
from repair_sampler_dependencies import TARGETS as SAMPLERS, PROVIDER, repair

HERE = Path(__file__).resolve().parent
OWNER = json.loads((HERE / 'native-owner-production.json').read_text())
ROOTS = ['liblitertlm_jni.so', OWNER['normal_library']] + [Path(x['path']).name for x in json.loads((HERE / 'android-prebuilts.json').read_text())]


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def library_name(name):
    if not re.fullmatch(r'lib[A-Za-z0-9_+.-]+\.so', name):
        raise ValueError(f'Unsafe library name: {name}')
    if name in OWNER['forbidden_libraries']:
        raise ValueError(f'Test owner library forbidden in production package: {name}')
    return name


def dependency_closure(roots, available, system, read_needed):
    pending, selected, dependencies = list(roots), {}, {}
    while pending:
        name = library_name(pending.pop())
        if name in selected:
            continue
        if name not in available:
            raise ValueError(f'Missing Android runtime library: {name}')
        selected[name] = available[name]
        needs = [library_name(n) for n in read_needed(available[name])]
        dependencies[name] = needs
        for dependency in needs:
            if dependency not in system:
                pending.append(dependency)
    return selected, dependencies


def index_libraries(paths):
    result = {}
    for path in paths:
        name = library_name(path.name)
        if name in result and sha256(path) != sha256(result[name]):
            raise ValueError(f'Conflicting library bytes for {name}')
        result[name] = path
    return result


def class_methods(data):
    """Read method names/descriptors without loading a JVM or trusting metadata."""
    offset = 8
    def take(n):
        nonlocal offset
        if n < 0 or offset + n > len(data):
            raise ValueError('Truncated class structure')
        value = data[offset:offset+n]; offset += n; return value
    def u1(): return int.from_bytes(take(1), 'big')
    def u2(): return int.from_bytes(take(2), 'big')
    def u4(): return int.from_bytes(take(4), 'big')
    pool, count, index = {}, u2(), 1
    while index < count:
        tag = u1()
        if tag == 1: pool[index] = take(u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4): take(4)
        elif tag in (5, 6): take(8); index += 1
        elif tag in (7, 8, 16, 19, 20): take(2)
        elif tag in (9, 10, 11, 12, 17, 18): take(4)
        elif tag == 15: take(3)
        else: raise ValueError('Unknown class constant-pool tag')
        index += 1
    take(6); take(2 * u2())
    def members():
        values = []
        for _ in range(u2()):
            flags, name, descriptor = u2(), u2(), u2()
            if name not in pool or descriptor not in pool:
                raise ValueError('Invalid class member constant reference')
            values.append({'name': pool[name], 'descriptor': pool[descriptor],
                           'native': bool(flags & 256), 'static': bool(flags & 8)})
            for _ in range(u2()): u2(); take(u4())
        return values
    members()  # fields
    return members()


def validate_owner_exports(exports):
    expected = set(OWNER['owner_jni_exports'])
    if set(exports) != expected:
        raise ValueError('Owner JNI exports do not exactly match the reviewed production methods')


def validate_sdk_exports(exports):
    if set(exports) != set(OWNER['sdk_jni_exports']):
        raise ValueError('SDK JNI exports do not exactly match the frozen checked lifecycle ABI')


def inspect_classes(data):
    required = set(OWNER['sdk_classes']) | set(OWNER['owner_classes'])
    owner_classes = set(OWNER['owner_classes'])
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not required <= set(names):
            raise ValueError('Incomplete or duplicate SDK/owner class set')
        if {n for n in names if n.endswith('.class')} != required:
            raise ValueError('SDK class outside explicit production allowlist')
        for name in names:
            basename = Path(name).name
            if name.endswith('.so') or (name.endswith('.class') and any(
                    marker in basename for marker in OWNER['forbidden_class_patterns'])):
                raise ValueError(f'Host runtime/test class in production SDK: {name}')
            if basename.startswith(('NativeAudio', 'CompletedNativeAudio', 'NativeOwner')) and name not in owner_classes:
                raise ValueError(f'Owner class outside explicit production allowlist: {name}')
            if name.endswith('.class'):
                content = archive.read(name)
                header = content[:8]
                if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe' or struct.unpack('>H', header[6:])[0] > 61:
                    raise ValueError(f'Invalid or newer-than-Java17 class: {name}')
                if name.endswith('/LiteRtLmJni.class'):
                    methods = class_methods(content)
                    native = [m for m in methods if m['native']]
                    if {m['name']: m['descriptor'] for m in native} != OWNER['sdk_jni_methods'] or len(native) != len(OWNER['sdk_jni_methods']):
                        raise ValueError('SDK native method descriptor drift, including checked Session/Conversation ABI')
                if name in owner_classes:
                    methods = class_methods(content)
                    if any(any(marker in method['name'] for marker in ('Test', 'Fake', 'LifecycleTest')) for method in methods):
                        raise ValueError(f'Test/fake method in production owner class: {name}')
                    if name.endswith('/NativeAudioOwnerJni.class'):
                        actual = {m['name']: m['descriptor'] for m in methods if m['native'] and m['static']}
                        if actual != OWNER['owner_jni_methods'] or sum(m['native'] for m in methods) != len(OWNER['owner_jni_methods']):
                            raise ValueError('Owner native class does not have exactly the reviewed static native methods')
    return {'required_owner_classes': sorted(owner_classes), 'owner_jni_methods': OWNER['owner_jni_methods']}


def write_archive(path, entries):
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-aar', type=Path, required=True)
    parser.add_argument('--classes-jar', type=Path, required=True)
    parser.add_argument('--native-directory', type=Path, action='append', required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--page-auditor', type=Path, required=True)
    parser.add_argument('--source-receipt', type=Path, required=True)
    parser.add_argument('--patchelf', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise ValueError('Refusing to overwrite an existing artifact')
    base_pin = json.loads((HERE / 'official-litert-aar-metadata.json').read_text())
    base_bytes = args.base_aar.read_bytes()
    if len(base_bytes) != base_pin['bytes'] or hashlib.sha1(base_bytes).hexdigest() != base_pin['sha1']:
        raise ValueError('Official baseline AAR digest mismatch')
    classes = args.classes_jar.read_bytes()
    class_contract = inspect_classes(classes)
    toolchain = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    system_dir = toolchain / 'sysroot/usr/lib/aarch64-linux-android/30'
    if not system_dir.is_dir():
        raise ValueError('Missing NDK API30 public system-library stubs')
    system = {p.name for p in system_dir.glob('*.so')} - {'libc++_shared.so'}
    candidates = [p for directory in args.native_directory for p in directory.rglob('*.so') if p.is_file()]
    candidates += list((toolchain / 'sysroot/usr/lib/aarch64-linux-android').glob('libc++_shared.so'))
    available = index_libraries(candidates)

    def read_needed(path):
        text = subprocess.check_output([str(toolchain / 'bin/llvm-readelf'), '-d', str(path)], text=True)
        sonames = re.findall(r'\(SONAME\).*\[([^\]]+)\]', text)
        if sonames and sonames != [path.name]:
            raise ValueError(f'SONAME mismatch for {path.name}: {sonames}')
        return re.findall(r'\(NEEDED\).*\[([^\]]+)\]', text)

    selected, dependencies = dependency_closure(ROOTS, available, system, read_needed)
    owner_exports = set()
    sdk_exports = set()
    for name, path in selected.items():
        symbols = subprocess.check_output([str(toolchain / 'bin/llvm-readelf'), '--dyn-syms', '--wide', str(path)], text=True)
        exports = {line.split()[-1] for line in symbols.splitlines()
                   if line.split() and line.split()[-1].startswith('Java_') and 'UND' not in line.split()}
        if any('NativeAudioOwnerTestJni' in value or 'nativeCreateForLifecycleTest' in value or 'nativeTest' in value for value in exports):
            raise ValueError(f'Test/fake JNI export in production dependency: {name}')
        if name == OWNER['normal_library']:
            owner_exports = exports
            validate_owner_exports(exports)
        if name == 'liblitertlm_jni.so':
            sdk_exports = exports
            validate_sdk_exports(exports)
    for pin in json.loads((HERE / 'android-prebuilts.json').read_text()):
        actual = selected[Path(pin['path']).name]
        if actual.stat().st_size != pin['bytes'] or sha256(actual) != pin['sha256']:
            raise ValueError(f'Pinned runtime prebuilt mismatch: {actual.name}')

    # Preserve upstream pins above, then derive fresh metadata-only copies.
    # Do not modify the checkout/LFS inputs or make JNI symbols process-global.
    derived = args.out.parent / 'sampler-runtime-dependencies'
    derived.mkdir(exist_ok=False)
    derivations = {}
    for name in SAMPLERS:
        destination = derived / name
        derivations[name] = repair(selected[name], destination, args.patchelf)
        selected[name] = destination
        dependencies[name] = read_needed(destination)
        if PROVIDER not in dependencies[name]:
            raise ValueError('Derived sampler lacks its explicit runtime provider')
    provider_symbols = subprocess.check_output([str(toolchain / 'bin/llvm-readelf'), '--dyn-syms', '--wide',
                                               str(selected[PROVIDER])], text=True)
    if not any(line.split()[-1:] == ['kLiteRtRuntimeBuiltin'] and 'OBJECT' in line.split() and
               'GLOBAL' in line.split() and 'DEFAULT' in line.split() and 'UND' not in line.split()
               for line in provider_symbols.splitlines()):
        raise ValueError('Declared sampler provider does not export the builtin runtime table')
    args.out.with_suffix('.sampler-dependencies.json').write_text(json.dumps(derivations, indent=2) + '\n')

    spec = importlib.util.spec_from_file_location('jarvis_page_auditor', args.page_auditor)
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    # Compress synthetic APK entries so only ELF LOAD/RELRO checks apply here.
    with tempfile.TemporaryDirectory(prefix='sdk-native-audit-') as temp:
        audit_zip = Path(temp) / 'native-audit.apk'
        write_archive(audit_zip, {f'lib/arm64-v8a/{name}': path.read_bytes() for name, path in selected.items()})
        audit = auditor.audit_apk(audit_zip)
    report_path = args.out.with_suffix('.native-audit.json')
    report_path.write_text(json.dumps(audit, indent=2) + '\n')
    if not audit['passed']:
        raise ValueError(f'ARM64/16KB native audit failed; retained {report_path}')

    with zipfile.ZipFile(io.BytesIO(base_bytes)) as base:
        # Preserve the official manifest, resources and notices. Replace all code/native
        # payloads rather than using pickFirst, allowing duplicate SDK definitions.
        keep = ['AndroidManifest.xml', 'R.txt', 'LICENSE', 'THIRD_PARTY_NOTICE.txt']
        entries = {name: base.read(name) for name in keep}
    entries['classes.jar'] = classes
    sampler_notice = (HERE / 'SAMPLER-DEPENDENCY-NOTICE.md').read_bytes()
    entries['assets/litert-lm-sampler-modifications.md'] = sampler_notice
    entries.update({f'jni/arm64-v8a/{name}': path.read_bytes() for name, path in selected.items()})
    source_receipt = json.loads(args.source_receipt.read_text())
    provenance = {'scope': 'experimental reviewed source-built ARM64 SDK; no model weights',
                  'source': source_receipt, 'explicit_dlopen_roots': ROOTS,
                  'native_dependencies': dependencies, 'ndk_api30_system_libraries': sorted(system),
                  'sampler_dependency_derivations': derivations,
                  'sampler_modifications_notice_sha256': hashlib.sha256(sampler_notice).hexdigest(),
                  'owner_inventory_sha256': sha256(HERE / 'native-owner-production.json'),
                  'owner_jni_exports': sorted(owner_exports), 'owner_class_contract': class_contract,
                  'sdk_jni_exports': sorted(sdk_exports),
                  'native_sha256': {name: sha256(path) for name, path in selected.items()},
                  'classes_sha256': hashlib.sha256(classes).hexdigest(),
                  'base_aar_sha256': hashlib.sha256(base_bytes).hexdigest(),
                  'page_auditor_sha256': sha256(args.page_auditor),
                  'not_verified': ['Android execution', 'GPU dynamic initialization', 'trained Gemma end-to-end audio', 'physical device performance']}
    entries['assets/litert-lm-source-provenance.json'] = (json.dumps(provenance, indent=2) + '\n').encode()
    write_archive(args.out, entries)
    provenance['aar_sha256'] = sha256(args.out)
    args.out.with_suffix('.provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({'aar': str(args.out), 'sha256': provenance['aar_sha256'], 'libraries': len(selected)}))


if __name__ == '__main__':
    main()
