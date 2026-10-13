#!/usr/bin/env python3
"""SOURCE-ONLY PROPOSAL. No downloads. Execute only after separate review/admission.

Consumes the exact local model and pinned host ORT archive, links unchanged
production C++ Session, and measures independent official-frontend probabilities.
No probability tolerance is defined or inferred here.
"""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import resource
import signal
import struct
import subprocess
import sys
import tarfile
import time

COMMIT = '165ffbb46af313d51e3947a2e70cab3eaef3a543'
TREE = 'bd90286517b045508d2b2b778eb89322fac5920d'
MODEL_SHA = '2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f'
ORT_SHA = '25b1ef1fea1acd210d63f8f24dc870ad6e077795ce1f54876252c6d3803c15af'
PINS = {
    'app/src/main/cpp/smartturn/smart_turn_session.h': 'bc31e706c33633cf509ca2483a3d280c26b404609e266837b7e79a030a33b23c',
    'app/src/main/cpp/smartturn/whisper_features.h': '48b3758b5ccb422eff32d221d6f32c745dc054ab2091031e228bc2f6c8ff74e3',
    'app/src/main/cpp/smartturn/whisper_features.cc': 'cb32f66aa6194039350437cb3f5074d8560da17d06893be755b94a92a357bb3e',
    'third_party/smart-turn/reference_whisper_features.py': '139b047d10dee42ad5b4894489012e515a882d8f5e2bffe8ad27b30728fc9968',
    'scripts/smart-turn/fixtures/manifest.json': '1b8bf8d17de8469aca6b40c1613af35078b1af9cf90962c7fd0b8fe81bae1420',
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def limit_child():
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
    resource.setrlimit(resource.RLIMIT_CPU, (120, 120))
    resource.setrlimit(resource.RLIMIT_FSIZE, (128 * 1024**2, 128 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--ort-archive', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    source, out = args.source.resolve(), args.out.resolve()
    if source == out or source in out.parents or out in source.parents:
        raise ValueError('Output must be a separate directory outside the candidate')
    if sys.byteorder != 'little' or os.uname().machine != 'x86_64':
        raise ValueError('This proposal is bounded to little-endian x86_64 Linux')
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
    resource.setrlimit(resource.RLIMIT_CPU, (600, 600))
    resource.setrlimit(resource.RLIMIT_FSIZE, (128 * 1024**2, 128 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})
    def terminate(_signal, _frame):
        raise TimeoutError('External bounded launcher terminated the task')
    signal.signal(signal.SIGTERM, terminate)
    identity = subprocess.check_output(['git', '--no-optional-locks', '-C', str(source), 'rev-parse', 'HEAD', 'HEAD^{tree}'], text=True).splitlines()
    if identity != [COMMIT, TREE]:
        raise ValueError('Candidate identity changed; independent re-admission required')
    if subprocess.check_output(['git', '--no-optional-locks', '-C', str(source), 'status', '--porcelain', '--untracked-files=no']):
        raise ValueError('Candidate tracked files changed; require a clean frozen source')
    for path, expected in PINS.items():
        if sha((source / path).read_bytes()) != expected:
            raise ValueError('Production/reference identity changed: ' + path)
    if args.model.stat().st_size != 8679182 or args.ort_archive.stat().st_size > 64 * 1024**2:
        raise ValueError('Model or dependency transfer size outside scope')
    with args.model.open('rb') as stream:
        model = stream.read(8679183)
    with args.ort_archive.open('rb') as stream:
        archive_bytes = stream.read(64 * 1024**2 + 1)
    if len(model) != 8679182 or len(archive_bytes) > 64 * 1024**2:
        raise ValueError('Bounded read exceeded admitted model/dependency size')
    if sha(model) != MODEL_SHA or sha(archive_bytes) != ORT_SHA:
        raise ValueError('Pinned model or dependency hash mismatch')
    # Model bytes are hashed once and passed unchanged through stdin to both
    # sequential processes; neither process reopens a replaceable model pathname.
    os.umask(0o077)
    out.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    receipt = {'status': 'running', 'source_commit': COMMIT, 'source_tree': TREE,
               'model_sha256': MODEL_SHA, 'model_bytes': len(model), 'ort_archive_sha256': ORT_SHA,
               'ort_distribution': 'Microsoft official onnxruntime-linux-x64-1.27.1.tgz',
               'source_sha256': PINS, 'probability_tolerance': None,
               'private_audio_used': False, 'device_validation': False,
               'scope': 'Host-only production C++ Session; official model and independent official frontend fixtures',
               'not_covered': ['JNI/Android/minified linkage', 'calibration or endpoint quality',
                               'phone latency/thermals/contention', 'in-flight cancellation races',
                               'encoder model', 'keyguard', 'private call exports'], 'commands': []}
    here = Path(__file__).resolve().parent
    receipt['harness_sha256'] = {p.name: sha(p.read_bytes()) for p in
                                (Path(__file__).resolve(), here/'native_host_probe.cc', here/'reference_ort_probe.cc')}
    env = dict(os.environ, OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', MKL_NUM_THREADS='1',
               NUMEXPR_NUM_THREADS='1', VECLIB_MAXIMUM_THREADS='1', PYTHONDONTWRITEBYTECODE='1')
    os.environ.update({k: env[k] for k in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS',
                                         'NUMEXPR_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS', 'PYTHONDONTWRITEBYTECODE')})
    sys.dont_write_bytecode = True

    def quota():
        if sum(p.stat().st_size for p in out.rglob('*') if p.is_file()) > 256 * 1024**2:
            raise ValueError('Task output exceeded 256 MiB quota')

    def run(label, command, input_bytes=None):
        quota()
        remaining = 540 - (time.monotonic() - start)
        if remaining <= 0:
            raise TimeoutError('Nine-minute inner budget exhausted')
        record = {'label': label, 'argv': [str(x) for x in command], 'pid': None,
                  'owned_process_group': None, 'exit_code': None, 'wall_seconds': None,
                  'communicate_completed': False, 'direct_child_reaped': False,
                  'group_state': 'not_launched', 'cleanup_verified': False, 'status': 'launching'}
        receipt['commands'].append(record)
        child_started = time.monotonic()
        try:
            child = subprocess.Popen([str(x) for x in command], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env=env, preexec_fn=limit_child, start_new_session=True)
        except BaseException as error:
            record.update(status='launch_failed', error_type=type(error).__name__,
                          wall_seconds=time.monotonic()-child_started)
            raise
        record.update(pid=child.pid, owned_process_group=child.pid, group_state='unchecked', status='running')
        stdout, stderr = b'', b''
        pending_error = None
        try:
            stdout, stderr = child.communicate(input_bytes, timeout=min(180, remaining))
            record['communicate_completed'] = True
        except BaseException as error:
            pending_error = error
            record['error_type'] = type(error).__name__
            record['status'] = 'interrupted' if isinstance(error, KeyboardInterrupt) else 'failed'
            stdout, stderr = getattr(error, 'stdout', None) or b'', getattr(error, 'stderr', None) or b''
            # The process group is created solely for this child and its owned
            # compiler descendants. Never attach to or signal unrelated work.
            try:
                os.killpg(child.pid, signal.SIGKILL)
                record['termination'] = 'owned_group_sigkill_sent'
            except ProcessLookupError:
                record['termination'] = 'owned_group_already_absent'
            except OSError as cleanup_error:
                record['termination'] = 'failed'
                record['termination_error_type'] = type(cleanup_error).__name__
            try:
                stdout, stderr = child.communicate(timeout=5)
                record['communicate_completed'] = True
            except BaseException as cleanup_error:
                record['cleanup_wait_error_type'] = type(cleanup_error).__name__
                stdout = getattr(cleanup_error, 'stdout', None) or stdout
                stderr = getattr(cleanup_error, 'stderr', None) or stderr
        finally:
            record['exit_code'] = child.poll()
            record['direct_child_reaped'] = record['exit_code'] is not None
            # Signal zero only observes our recorded group's existence. A
            # surviving or uncheckable group is not treated as cleaned up.
            try:
                os.killpg(child.pid, 0)
                record['group_state'] = 'present'
            except ProcessLookupError:
                record['group_state'] = 'absent'
            except OSError as cleanup_error:
                record['group_state'] = 'ambiguous'
                record['group_check_error_type'] = type(cleanup_error).__name__
            record['cleanup_verified'] = (record['communicate_completed'] and
                                          record['direct_child_reaped'] and record['group_state'] == 'absent')
            record['wall_seconds'] = time.monotonic()-child_started
            if pending_error is None:
                record['status'] = 'exited_zero' if record['exit_code'] == 0 else 'exited_nonzero_or_unreaped'
        (out / (label + '.stdout')).write_bytes(stdout)
        (out / (label + '.stderr')).write_bytes(stderr)
        if not record['cleanup_verified']:
            record['status'] = 'cleanup_unverified'
            raise RuntimeError(label + ' cleanup was not verified; stop before another phase') from pending_error
        if pending_error is not None:
            raise pending_error
        quota()
        if child.returncode:
            record['status'] = 'failed'
            raise RuntimeError(label + ' failed with exit ' + str(child.returncode))
        record['status'] = 'completed'
        return stdout.decode()

    try:
        ort = out / 'ort'
        with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode='r:gz') as archive:
            members = archive.getmembers()
            if len(members) > 2000 or sum(p.size for p in members) > 128 * 1024**2:
                raise ValueError('ORT archive expansion outside scope')
            for member in members:
                target = (ort / member.name).resolve()
                if target == ort or ort not in target.parents or not (member.isfile() or member.isdir() or member.issym()):
                    raise ValueError('Unsafe archive member')
                if member.issym():
                    # Permit only the official library's local version aliases.
                    if (Path(member.name).name not in ('libonnxruntime.so', 'libonnxruntime.so.1') or
                            member.linkname not in ('libonnxruntime.so.1', 'libonnxruntime.so.1.27.1')):
                        raise ValueError('Unexpected dependency archive link')
                    if ort not in (target.parent / member.linkname).resolve().parents:
                        raise ValueError('Dependency archive link escaped output')
            archive.extractall(ort, filter='data')
        quota()
        headers = list(ort.rglob('onnxruntime_cxx_api.h'))
        libraries = list(ort.rglob('libonnxruntime.so'))
        if len(headers) != 1 or len(libraries) != 1:
            raise ValueError('Ambiguous host ORT package layout')
        include, library = headers[0].parent, libraries[0].parent
        env['LD_LIBRARY_PATH'] = str(library)
        receipt['ort_library_sha256'] = {p.name: sha(p.read_bytes()) for p in library.glob('*.so*') if p.is_file()}
        receipt['compiler_version'] = run('compiler-version', ['g++', '--version']).splitlines()[0]
        import numpy as np
        if np.__version__ != '2.3.5':
            raise ValueError('Use pinned available NumPy 2.3.5')
        receipt['numpy_version'] = np.__version__
        ref_path = source / 'third_party/smart-turn/reference_whisper_features.py'
        spec = importlib.util.spec_from_file_location('independent_pinned_frontend', ref_path)
        reference = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reference)
        fixture_dir = source / 'scripts/smart-turn/fixtures'
        manifest = json.loads((fixture_dir / 'manifest.json').read_bytes())
        if len(manifest['cases']) != 8 or manifest['absolute_tolerance'] != 2e-6:
            raise ValueError('Frozen fixture contract changed')
        pcm_paths, reference_paths = [], []
        cases = []
        for case in manifest['cases']:
            decoded = {}
            for kind in ('pcm16le', 'features.f32le'):
                pin = case[kind]
                if pin['bytes_uncompressed'] > 333920:
                    raise ValueError('Fixture input exceeds admitted bound')
                with gzip.open(fixture_dir / pin['file'], 'rb') as stream:
                    data = stream.read(pin['bytes_uncompressed'] + 1)
                if len(data) != pin['bytes_uncompressed'] or sha(data) != pin['sha256_uncompressed']:
                    raise ValueError('Frozen fixture mismatch: ' + case['id'])
                decoded[kind] = data
            pcm = np.frombuffer(decoded['pcm16le'], dtype='<i2').astype(np.float32)[-128000:] / np.float32(32768)
            # Pipecat's analyzer crops the recent window and LEFT pads before
            # calling this official frontend. Its internal default pad is right.
            padded = np.pad(pcm, (128000-len(pcm), 0))
            official = reference.compute_whisper_log_mel_features(padded, do_normalize=True).astype('<f4')
            if official.shape != (80, 800) or official.tobytes() != decoded['features.f32le']:
                raise ValueError('Fresh official reference did not reproduce frozen bytes: ' + case['id'])
            pcm_path = out / (case['id'] + '.pcm.f32le')
            reference_path = out / (case['id'] + '.reference.f32le')
            pcm_path.write_bytes(pcm.astype('<f4').tobytes())
            reference_path.write_bytes(official.tobytes())
            pcm_paths.append(pcm_path)
            reference_paths.append(reference_path)
            cases.append({'id': case['id'], 'samples': len(pcm), 'reference_sha256': sha(official.tobytes())})
        native = source / 'app/src/main/cpp/smartturn'
        common = ['g++', '-O2', '-std=c++17', '-pthread', '-I'+str(include), '-L'+str(library),
                  '-Wl,-rpath,'+str(library)]
        run('compile-native', common + ['-I'+str(native), here/'native_host_probe.cc', native/'whisper_features.cc',
                                       '-lonnxruntime', '-o', out/'native_host_probe'])
        run('compile-reference', common + [here/'reference_ort_probe.cc', '-lonnxruntime', '-o', out/'reference_ort_probe'])
        native_lines = [json.loads(line) for line in run('native', [out/'native_host_probe', *pcm_paths], model).splitlines()]
        controls = {'unprepared_rejected': True, 'pre_cancel_rejected': True,
                    'nonfinite_rejected': True, 'reuse_after_rejections_exact': True}
        if (len(native_lines) != 9 or native_lines[-1] != controls or
                [row.get('case_index') for row in native_lines[:8]] != list(range(8))):
            raise ValueError('Native record/negative-control contract mismatch')
        actual_paths = [Path(str(p)+'.native-features.f32le') for p in pcm_paths]
        oracle_lines = [json.loads(line) for line in run('reference', [out/'reference_ort_probe', *reference_paths, *actual_paths], model).splitlines()]
        if len(oracle_lines) != 16 or [row.get('input_index') for row in oracle_lines] != list(range(16)):
            raise ValueError('Independent record count mismatch')
        receipt['cases'] = cases
        exact = True
        for i, case in enumerate(cases):
            actual = np.frombuffer(actual_paths[i].read_bytes(), dtype='<f4')
            expected = np.frombuffer(reference_paths[i].read_bytes(), dtype='<f4')
            if actual.shape != (64000,) or not np.isfinite(actual).all():
                raise ValueError('Invalid native frontend output')
            error = float(np.max(np.abs(actual-expected)))
            p, q, same_features = [float(np.float32(value)) for value in (
                native_lines[i]['probability'], oracle_lines[i]['probability'], oracle_lines[i+8]['probability'])]
            if not all(math.isfinite(v) and 0 <= v <= 1 for v in (p, q, same_features)):
                raise ValueError('Invalid host probability')
            bits = lambda value: struct.unpack('<I', struct.pack('<f', value))[0]
            io_exact = bits(p) == bits(same_features)
            probability_exact = bits(p) == bits(q)
            case.update(frontend_max_abs_error=error, frontend_tolerance=manifest['absolute_tolerance'],
                        native_probability=p, official_frontend_probability=q,
                        direct_native_features_probability=same_features, session_io_exact=io_exact,
                        probability_bit_identical=probability_exact, probability_abs_difference=abs(p-q),
                        probability_ulp_difference=abs(bits(p)-bits(q)),
                        frontend_nanos=native_lines[i]['frontend_nanos'], inference_nanos=native_lines[i]['inference_nanos'])
            if error > manifest['absolute_tolerance'] or not io_exact:
                raise ValueError('Existing frontend or exact same-feature I/O contract failed')
            exact = exact and probability_exact
        receipt['cases'] = cases
        receipt['negative_controls'] = native_lines[-1]
        for path, expected in PINS.items():
            if sha((source/path).read_bytes()) != expected:
                raise ValueError('Source changed during validation')
        if subprocess.check_output(['git', '--no-optional-locks', '-C', str(source), 'status', '--porcelain', '--untracked-files=no']):
            raise ValueError('Candidate tracked files changed during validation')
        receipt['status'] = 'exact_fixture_probability_agreement' if exact else 'probability_difference_requires_independent_review'
        receipt['probability_parity_admitted'] = False
        receipt['admission_note'] = 'Independent review of receipts remains required, including exact observed agreement.'
        return 0 if exact else 3
    except BaseException as error:
        receipt['status'] = 'interrupted' if isinstance(error, KeyboardInterrupt) else 'blocked_or_failed'
        receipt['error_type'] = type(error).__name__
        receipt['error'] = str(error)
        raise
    finally:
        receipt['wall_seconds'] = time.monotonic() - start
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')

if __name__ == '__main__':
    raise SystemExit(main())
