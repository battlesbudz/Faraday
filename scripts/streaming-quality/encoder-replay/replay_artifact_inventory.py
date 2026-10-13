#!/usr/bin/env python3
"""Draft a fail-closed, weight-free replay inventory. Never uploads or executes ELF.

This is a source-review helper, not a packager or a provenance acceptance gate.
It deliberately refuses publication readiness until a separately reviewed complete
Bazel action/input graph proves static and header dependency/license coverage.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

MAX_FILE = 128 * 1024**2
MAX_PAYLOAD = 512 * 1024**2
MAX_LIBRARIES = 512
SYSTEM_ROOTS = (Path('/lib'), Path('/lib64'), Path('/usr/lib'), Path('/usr/lib64'))
SYSTEM_NAMES = re.compile(r'^(?:ld-linux[^/]*\.so(?:\.[0-9]+)*|lib(?:c|m|dl|pthread|rt|stdc\+\+|gcc_s)\.so(?:\.[0-9]+)*)$')
SAFE_ALIAS = re.compile(r'^[A-Za-z0-9_+.-]+$')


def fail(message):
    raise ValueError(message)


def describe(path):
    path = Path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_FILE:
        fail('Missing, empty or oversized file: ' + str(path))
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024**2), b''): digest.update(block)
    return {'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}


def verify(path, expected):
    if describe(path) != {key: expected[key] for key in ('bytes', 'sha256')}:
        fail('Identity mismatch: ' + str(path))


def parse_listing(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('linux-vdso.so.'): continue
        if 'not found' in line: fail('Unresolved dependency: ' + line)
        match = re.fullmatch(r'(\S+)\s+=>\s+(/\S+)\s+\(0x[0-9a-f]+\)', line)
        if match:
            alias, source = match.groups()
        else:
            match = re.fullmatch(r'(/\S+)\s+\(0x[0-9a-f]+\)', line)
            if not match: fail('Unrecognized dependency listing row: ' + line)
            source = match[1]; alias = Path(source).name
        if not SAFE_ALIAS.fullmatch(alias) or alias in ('.', '..'):
            fail('Unsafe loader alias: ' + alias)
        real = Path(source).resolve(strict=True)
        if alias in result and result[alias] != real: fail('Conflicting alias: ' + alias)
        result[alias] = real
    if not 0 < len(result) <= MAX_LIBRARIES: fail('Unexpected library count')
    return result


def elf_metadata(path):
    with Path(path).open('rb') as stream:
        if stream.read(4) != b'\x7fELF': fail('Non-ELF payload rejected: ' + str(path))
    # readelf reads the file; neither ldd nor the executable is invoked here.
    result = subprocess.run(['readelf', '-d', '-h', '-V', str(path)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=10, check=True)
    text = result.stdout
    if len(text) > 4*1024**2: fail('Oversized ELF metadata')
    if '(RPATH)' in text: fail('DT_RPATH requires separate search-order review')
    return {'needed': re.findall(r'\(NEEDED\).*?\[(.*?)\]', text),
            'soname': re.findall(r'\(SONAME\).*?\[(.*?)\]', text),
            'runpath': re.findall(r'\(RUNPATH\).*?\[(.*?)\]', text),
            'required_symbol_versions': sorted(set(re.findall(r'Name: ((?:GLIBC|GLIBCXX|CXXABI|GCC)_[^\s]+)', text))),
            'metadata_sha256': hashlib.sha256(text.encode()).hexdigest()}


def artifact_origin(path, execroot):
    try: relative = Path(path).resolve(strict=True).relative_to(execroot.resolve(strict=True)).as_posix()
    except ValueError: fail('Non-system library escaped build root: ' + str(path))
    parts = relative.split('/')
    if len(parts) < 5 or parts[0] != 'bazel-out' or parts[2] != 'bin':
        fail('Not a source-built Bazel output: ' + relative)
    if parts[3] == 'external':
        if len(parts) < 6: fail('Missing external origin: ' + relative)
        return parts[4]
    return 'sdk'


def source_record(origin, catalog, blockers):
    record = catalog.get(origin)
    if record is None:
        blockers.append('Unknown source/license origin: ' + origin)
        return None
    for key in ('upstream_url', 'pin', 'declaration', 'licenses'):
        if not record.get(key): fail('Incomplete origin ' + origin + ': ' + key)
    if not record['upstream_url'].startswith('https://'): fail('Non-HTTPS origin')
    declaration = record['declaration']
    verify(declaration['path'], declaration)
    for license_file in record['licenses']: verify(license_file['path'], license_file)
    if record.get('binary_distribution_review') != 'complete':
        blockers.append('Binary/source-notice obligations not reviewed: ' + origin)
    # File bodies and absolute source locations are not included in this public
    # portion of the plan. A later packager must use explicit reviewed paths.
    return {'upstream_url': record['upstream_url'], 'pin': record['pin'],
            'archive_sha256': record.get('archive_sha256'),
            'declaration_sha256': declaration['sha256'],
            'licenses': [{k: item[k] for k in ('name', 'bytes', 'sha256')} for item in record['licenses']],
            'binary_distribution_review': record.get('binary_distribution_review', 'pending')}


def inventory(probe, receipt, listing, execroot, link_params, catalog, header_graph):
    blockers = ['Exact hosted per-output compiler executables and compile-argument hashes remain uncaptured.',
                'Hosted and local system-library/loader compatibility and symbol-provider comparison remain required.']
    probe = probe.resolve(strict=True)
    verify(probe, receipt['binaries']['pinned_encoder_probe'])
    dependency_pins = {str(Path(path).resolve(strict=True)): pin
                       for path, pin in receipt['dynamic_libraries'].items()}
    aliases = parse_listing(listing)
    entries = []; system = []; origins = set()
    total = probe.stat().st_size
    for alias, path in sorted(aliases.items()):
        pin = dependency_pins.get(str(path))
        if pin is None: fail('Dependency missing from exact build receipt: ' + str(path))
        verify(path, pin)
        metadata = elf_metadata(path)
        if SYSTEM_NAMES.fullmatch(alias):
            if not any(path.is_relative_to(root.resolve()) for root in SYSTEM_ROOTS if root.exists()):
                fail('System SONAME outside system roots: ' + alias)
            system.append(dict(alias=alias, identity=pin, elf=metadata, disposition='record_only_never_copy'))
            continue
        origin = artifact_origin(path, execroot); origins.add(origin)
        if 'prebuilt/' in str(path): fail('Prebuilt dependency requires separate approval')
        entries.append(dict(alias=alias, identity=pin, elf=metadata, origin=origin,
                            capsule_path='lib/' + alias))
        total += pin['bytes']
    probe_elf = elf_metadata(probe)
    for entry in entries + [{'elf': probe_elf}]:
        for needed in entry['elf']['needed']:
            if needed not in aliases: fail('Dependency closure incomplete: ' + needed)
    if total > MAX_PAYLOAD: fail('Capsule exceeds bounded payload size')
    static_inputs = []
    for argument in link_params.read_text().splitlines():
        if argument.startswith('@'): fail('Nested response file needs explicit capture')
        if not argument.endswith(('.a', '.o')): continue
        path = Path(argument)
        if not path.is_absolute(): path = execroot/path
        origin = artifact_origin(path, execroot); origins.add(origin)
        static_inputs.append({'origin': origin, 'identity': describe(path),
                              'execroot_relative_path': str(path.relative_to(execroot))})
    if not static_inputs: fail('Static link input provenance omitted')
    if header_graph.get('unresolved'): fail('Header/link provenance contains unresolved inputs')
    if not header_graph.get('dependency_file_sha256') or not header_graph.get('response_file_sha256'):
        fail('Compiler dependency and link evidence omitted')
    for item in header_graph['sources']:
        origin = item['origin']
        if origin in ('system_headers', 'compiler_headers'): continue
        origins.add('sdk' if origin == 'sdk_or_generated' else origin)
    origins.add('sdk')
    sources = {origin: source_record(origin, catalog, blockers) for origin in sorted(origins)}
    return {'schema_version': 1, 'purpose': 'Reviewed encoder-only compiled-runtime replay on another CPU',
            'publishable': False, 'executable_replay_authorized': False,
            'scope': 'Inventory only. No ELF executed, copied, uploaded or installed.',
            'blockers': sorted(set(blockers)), 'source_snapshot_sha256': receipt.get('source_snapshot_sha256'),
            'compiler_version_declared': receipt.get('host_compiler'),
            'compiler_elf_sha256': None, 'per_action_compiler_flags_sha256': None,
            'link_params_sha256': describe(link_params)['sha256'],
            'probe': {'identity': describe(probe), 'elf': probe_elf, 'capsule_path': 'bin/pinned_encoder_probe'},
            'libraries': entries, 'system_libraries': system, 'source_origins': sources,
            'static_link_inputs': static_inputs, 'maximum_payload_bytes': total,
            'header_origin_counts': header_graph['origin_counts'],
            'response_files_count': len(header_graph['response_file_sha256']),
            'compiler_dependency_files_count': len(header_graph['dependency_file_sha256']),
            'loader_plan': 'Preserve every recorded DT_NEEDED alias in lib/. Use verified local loader/library-path after separate review; do not rewrite ELF.',
            'system_environment_claim': 'Different local system libraries are recorded; this does not reproduce the exact hosted environment.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('probe', 'receipt', 'dependency-listing', 'execroot', 'link-params', 'source-map', 'header-graph', 'out'):
        p.add_argument('--'+name, type=Path, required=True)
    args=p.parse_args()
    if args.out.exists(): fail('Fresh output required')
    result=inventory(args.probe, json.loads(args.receipt.read_text()), args.dependency_listing.read_text(),
                     args.execroot, args.link_params, json.loads(args.source_map.read_text()),
                     json.loads(args.header_graph.read_text()))
    args.out.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({'publishable': False, 'libraries': len(result['libraries']),
                      'system_libraries_excluded': len(result['system_libraries']),
                      'blockers': result['blockers']}, indent=2))
    return 2


if __name__ == '__main__': raise SystemExit(main())
