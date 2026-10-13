#!/usr/bin/env python3
"""Build hosted CPU probes only after the serial Android SDK compilation exits."""
import argparse
import ctypes
import signal
import stat as statmod
import time
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
from common import *

PACKAGE = 'experimental/hosted_audio_quality_20261006'
TARGETS = ['native_frontend_quality_probe', 'pinned_encoder_probe', 'native_conversation_quality_probe']
BAZEL_SHA = 'ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025'


def host_cpu_metadata():
    """Bounded runner ISA identity for diagnosing cross-host numeric differences."""
    try:
        with open('/proc/cpuinfo', 'r') as source:
            first = source.read(16384).split('\n\n', 1)[0]
        fields = dict(line.split(':', 1) for line in first.splitlines() if ':' in line)
        fields = {key.strip(): value.strip() for key, value in fields.items()}
        flags = set(fields.get('flags', '').split())
        selected = ('sse4_2', 'fma', 'f16c', 'avx2', 'avx512f', 'avx512_vnni', 'avx512_bf16', 'avx_vnni')
        return {'scope': 'first advertised Linux runner processor; no installed-device claim',
                'model_name': fields.get('model name', 'unavailable')[:256],
                'architecture': os.uname().machine,
                'selected_isa_flags': {flag: flag in flags for flag in selected}}
    except (OSError, ValueError):
        return {'scope': 'unavailable'}


# Compilation has a separate, reviewed 60-minute budget. These limits never
# change bounded_exec.py or any model process's CPU/memory/wall-time limits.
COMPILE_WALL_SECONDS = 3600
COMPILE_JOBS = 4
COMPILE_MIN_AVAILABLE = 8 * 1024**3
COMPILE_TREE_RSS_LIMIT = 6 * 1024**3
COMPILE_SYSTEM_RESERVE = 2 * 1024**3
COMPILE_LOG_LIMIT = 64 * 1024**2
COMPILE_TAIL_BYTES = 32768


def compile_memory():
    host = int(next(x for x in Path('/proc/meminfo').read_text().splitlines()
                    if x.startswith('MemAvailable:')).split()[1]) * 1024
    # Respect visible cgroup-v2 ancestor limits as well as physical RAM. The
    # standard hosted VM may have no finite cgroup limit; record that explicitly.
    remaining = []
    root = Path('/sys/fs/cgroup')
    group = next((x.split(':', 2)[2] for x in Path('/proc/self/cgroup').read_text().splitlines()
                  if x.startswith('0::')), None)
    if group is not None:
        current = (root / group.lstrip('/')).resolve()
        if not current.is_relative_to(root) or not current.is_dir(): current = root  # Namespaced cgroup mount.
        while current.is_relative_to(root):
            maximum, used = current/'memory.max', current/'memory.current'
            if maximum.exists() and used.exists():
                value = maximum.read_text().strip()
                if value != 'max': remaining.append(max(0, int(value)-int(used.read_text())))
            if current == root: break
            current = current.parent
    return {'host_mem_available_bytes': host,
            'cgroup_remaining_bytes': min(remaining) if remaining else None,
            'effective_available_bytes': min([host, *remaining])}


def process_table():
    table = {}
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            data = path.read_text(); fields = data[data.rfind(')')+2:].split()
            table[int(path.parent.name)] = {'state': fields[0], 'ppid': int(fields[1]),
                'group': int(fields[2]), 'start': int(fields[19]),
                'rss': max(0, int(fields[21]))*os.sysconf('SC_PAGE_SIZE')}
        except (FileNotFoundError, ProcessLookupError, IndexError): pass
    return table


def owned_tree(table, root_pid, known):
    root_matches = root_pid not in known or root_pid not in table or table[root_pid]['start'] == known[root_pid]
    owned = {pid: row for pid, row in table.items()
             if known.get(pid) == row['start'] or (root_matches and (pid == root_pid or row['group'] == root_pid))}
    # A Linux subreaper owns grandchildren orphaned by exited compiler wrappers.
    # run_compile requires no pre-existing direct child and starts no unrelated
    # work concurrently, so newly adopted direct children belong to this build.
    owned.update({pid: row for pid, row in table.items() if row['ppid'] == os.getpid()})
    changed = True
    while changed:
        previous = len(owned)
        owned.update({pid: row for pid, row in table.items() if row['ppid'] in owned})
        changed = len(owned) != previous
    known.update({pid: row['start'] for pid, row in owned.items()})
    return owned


def set_subreaper(enabled):
    libc = ctypes.CDLL(None, use_errno=True)
    previous = ctypes.c_int()
    if libc.prctl(37, ctypes.byref(previous), 0, 0, 0) != 0:  # PR_GET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'Cannot inspect child-subreaper state')
    if libc.prctl(36, int(enabled), 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'Cannot set child-subreaper state')
    return bool(previous.value)


def compiler_diagnostic(out, file_identity, *, include_hash=True):
    path = Path(out)/'compile.log'
    need(path.parent.resolve() == Path(out).resolve(), 'Compiler log escaped output directory', 'build_evidence_failure')
    try:
        fd = os.open(path, os.O_RDONLY|os.O_NOFOLLOW)
    except OSError as error:
        raise GateError('build_evidence_failure', 'Cannot safely open compiler log') from error
    # Validation, tail selection and hashing all use one opened descriptor.
    # Replacing the path before open fails identity; replacing it after open
    # cannot redirect the bytes read or hashed below.
    with os.fdopen(fd, 'rb') as stream:
        stat = os.fstat(stream.fileno())
        need(statmod.S_ISREG(stat.st_mode) and (stat.st_dev, stat.st_ino) == file_identity,
             'Compiler log identity changed', 'build_evidence_failure')
        stream.seek(max(0, stat.st_size-COMPILE_TAIL_BYTES))
        tail = stream.read(COMPILE_TAIL_BYTES)
        digest_value = None
        if include_hash:
            stream.seek(0); hasher = hashlib.sha256()
            for block in iter(lambda: stream.read(1024*1024), b''): hasher.update(block)
            need(os.fstat(stream.fileno()).st_size == stat.st_size,
                 'Compiler log changed during final hash', 'build_evidence_failure')
            digest_value = hasher.hexdigest()
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', tail.decode('utf-8', errors='replace'))
    text = ''.join(c for c in text if c in '\n\t' or ord(c) >= 32)
    counters = re.findall(r'\[\s*([\d,]+)\s*/\s*([\d,]+)\s*\]', text)
    return {'source': 'compile.log', 'scope': 'Fixed public-source compiler subprocess only; no model has run',
            'total_bytes': stat.st_size, 'sha256': digest_value, 'tail_max_input_bytes': COMPILE_TAIL_BYTES,
            'tail_truncated': stat.st_size > COMPILE_TAIL_BYTES, 'tail': text,
            'last_action_progress': {'completed': int(counters[-1][0].replace(',', '')),
                                    'total': int(counters[-1][1].replace(',', ''))} if counters else None}


def run_compile(command, sdk, out, *, wall_seconds=COMPILE_WALL_SECONDS,
                sample_seconds=15.0, poll_seconds=.25, cleanup_seconds=5.0,
                resource_profile='compiler'):
    """Bound and reap the complete Linux compiler tree, including new sessions."""
    # The optional diagnostic uses this same ownership/cleanup implementation,
    # with a smaller fixed profile. Existing compiler callers keep every limit.
    need(resource_profile in ('compiler', 'diagnostic'), 'Unknown supervisor profile')
    jobs, minimum, rss_limit, reserve, maximum_wall = (
        (COMPILE_JOBS, COMPILE_MIN_AVAILABLE, COMPILE_TREE_RSS_LIMIT,
         COMPILE_SYSTEM_RESERVE, COMPILE_WALL_SECONDS) if resource_profile == 'compiler'
        else (2, 5 * 1024**3, 2 * 1024**3, 1024**3, 180))
    need(0 < wall_seconds <= maximum_wall and 0 < cleanup_seconds <= 5,
         'Compiler budget cannot be expanded')
    out = Path(out)
    memory = compile_memory()
    report = {'started': False, 'passed': False, 'jobs': jobs, 'resource_profile': resource_profile,
        'wall_budget_seconds': wall_seconds, 'minimum_available_bytes': minimum,
        'tree_rss_watchdog_bytes': rss_limit, 'system_reserve_bytes': reserve,
        'memory_before': memory, 'cpu_affinity_count': len(os.sched_getaffinity(0)),
        'sampled_peak_tree_rss_bytes': 0, 'peak_process_count': 0, 'samples': [],
        'rss_sum_may_double_count_shared_pages': True, 'cleanup_verified': False}
    if memory['effective_available_bytes'] < minimum or report['cpu_affinity_count'] < jobs:
        report.update(classification='build_resource_blocked', reason=f'{resource_profile} requires at least {minimum // 1024**3} GiB available and {jobs} schedulable CPUs')
        return report
    need(not any(p['ppid'] == os.getpid() for p in process_table().values()),
         'Compiler supervisor must exclusively own its child processes', 'build_supervision_failure')
    path = out/'compile.log'
    fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600)
    st = os.fstat(fd); identity = (st.st_dev, st.st_ino)
    child = None; known = {}; signals = {}; cancelled = []; started = time.monotonic()
    previous_subreaper = None
    def interrupted(number, frame): cancelled.append(number)
    def live_owned():
        rows = owned_tree(process_table(), child.pid, known)
        # Reap adopted descendants individually; leave Popen's child to Popen.
        for pid, row in rows.items():
            if row['state'] == 'Z' and pid != child.pid:
                try: os.waitpid(pid, os.WNOHANG)
                except ChildProcessError: pass
        return {pid: row for pid, row in rows.items() if row['state'] != 'Z'}
    def terminate_owned():
        rows = live_owned()
        if rows:
            try: os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            for pid in rows:
                try: os.kill(pid, signal.SIGTERM)
                except ProcessLookupError: pass
        until = time.monotonic()+cleanup_seconds
        while time.monotonic() < until and live_owned(): time.sleep(.05)
        rows = live_owned()
        if rows:
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            for pid in rows:
                try: os.kill(pid, signal.SIGKILL)
                except ProcessLookupError: pass
        until = time.monotonic()+cleanup_seconds
        while time.monotonic() < until and live_owned(): time.sleep(.05)
        report['surviving_process_count'] = len(live_owned())
        report['cleanup_verified'] = report['surviving_process_count'] == 0
        if child.poll() is None and report['cleanup_verified']: child.wait(timeout=1)
    try:
        previous_subreaper = set_subreaper(True)
        for number in (signal.SIGINT, signal.SIGTERM):
            signals[number] = signal.getsignal(number); signal.signal(number, interrupted)
        with os.fdopen(fd, 'wb') as log:
            child = subprocess.Popen(command, cwd=sdk, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                preexec_fn=lambda: os.sched_setaffinity(0, set(sorted(os.sched_getaffinity(0))[:jobs])))
            report['started'] = True
            last_sample = -sample_seconds
            while True:
                elapsed = time.monotonic()-started
                rows = live_owned(); rss = sum(row['rss'] for row in rows.values())
                memory = compile_memory()
                report['sampled_peak_tree_rss_bytes'] = max(report['sampled_peak_tree_rss_bytes'], rss)
                report['peak_process_count'] = max(report['peak_process_count'], len(rows))
                if elapsed-last_sample >= sample_seconds:
                    # A bounded hash-bound compiler-only tail is retained at end;
                    # live logs expose just action/resource counters, never text.
                    progress = compiler_diagnostic(out, identity, include_hash=False)
                    sample = {'elapsed_seconds': round(elapsed, 3), 'tree_rss_bytes': rss,
                        'processes': len(rows), 'available_bytes': memory['effective_available_bytes'],
                        'log_bytes': progress['total_bytes'], 'actions': progress['last_action_progress']}
                    report['samples'] = (report['samples']+[sample])[-240:]
                    print(json.dumps({'hosted_compile_progress': sample}), flush=True)
                    last_sample = elapsed
                if cancelled: report.update(classification='build_cancelled', stop_reason='signal', signal=cancelled[0]); break
                if elapsed >= wall_seconds: report.update(classification='build_timeout', stop_reason='wall_timeout'); break
                if rss > rss_limit: report.update(classification='build_resource_failure', stop_reason='process_tree_rss'); break
                if memory['effective_available_bytes'] < reserve:
                    report.update(classification='build_resource_failure', stop_reason='system_memory_reserve'); break
                if path.stat().st_size > COMPILE_LOG_LIMIT:
                    report.update(classification='build_output_limit', stop_reason='compiler_log_size'); break
                code = child.poll()
                if code is not None:
                    report.update(exit_code=code, classification='build_passed' if code == 0 else 'build_compile_failure')
                    if live_owned(): report.update(classification='build_supervision_failure', stop_reason='descendants_survived_compiler')
                    break
                time.sleep(poll_seconds)
    except BaseException as error:
        report.update(classification='build_supervision_failure', error=f'{type(error).__name__}: {error}')
    finally:
        cleanup_errors = []
        def record_cleanup(error):
            cleanup_errors.append(f'{type(error).__name__}: {error}'[:1000])
        try:
            if child is not None:
                try:
                    terminate_owned()
                except BaseException as error:
                    record_cleanup(error)
                    report['cleanup_verified'] = False
                    # Even if /proc inspection fails, a still-running direct
                    # child has its own original process group. Kill that group
                    # before a bounded second attempt at descendant inspection.
                    try:
                        if child.poll() is None:
                            try: os.killpg(child.pid, signal.SIGKILL)
                            except ProcessLookupError: pass
                            child.wait(timeout=cleanup_seconds)
                    except BaseException as fallback_error: record_cleanup(fallback_error)
                    try: terminate_owned()
                    except BaseException as retry_error: record_cleanup(retry_error)
                report['exit_code'] = child.poll()
            else:
                report['cleanup_verified'] = True
                try: os.close(fd)
                except OSError: pass
        except BaseException as error:
            record_cleanup(error)
            report['cleanup_verified'] = False
        finally:
            # Restoration is independent of cleanup and evidence success.
            for number, original in signals.items():
                try: signal.signal(number, original)
                except BaseException as error: record_cleanup(error)
            if previous_subreaper is not None:
                try: set_subreaper(previous_subreaper)
                except BaseException as error: record_cleanup(error)
            report['wall_seconds'] = time.monotonic()-started
            try: report['diagnostic'] = compiler_diagnostic(out, identity)
            except BaseException as error:
                report['diagnostic_error'] = f'{type(error).__name__}: {error}'[:1000]
                report['classification'] = 'build_evidence_failure'
            if cancelled: report.update(classification='build_cancelled', stop_reason='signal', signal=cancelled[0])
            if cleanup_errors or not report['cleanup_verified']:
                report['classification'] = 'build_cleanup_failure'
                report['cleanup_errors'] = cleanup_errors
    report['passed'] = report['classification'] == 'build_passed' and report['cleanup_verified'] and report['exit_code'] == 0
    return report


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

def build(a):
    package_identity = verify_recipe_sources()
    sdk = a.sdk.resolve(); out = a.out.resolve()
    need(not out.exists(), 'Use a fresh hosted quality build directory')
    out.mkdir(parents=True)
    status = {'classification': 'build_pending', 'build_succeeded': False, 'inference_run': False,
              'diagnostic_cleanup_required': True}
    write(out/'build-status.json', status)
    try:
        android = load(a.android_receipt)
        need(android['sdk_commit'] == SDK_PIN and android['litert_commit'] == LITERT_PIN,
             'Android build source pin mismatch')
        need(sha(a.patch) == android['reviewed_patch_sha256'], 'Reviewed patch digest differs from compiled Android receipt')
        need(sha(sdk/'WORKSPACE') == android['workspace_after_api30_sha256'], 'Android API30 workspace overlay changed')
        # Reconstruct the expected source tree in a temporary worktree so extra
        # tracked/untracked modifications cannot hide behind the patch digest.
        expected = out/'expected-sdk'
        subprocess.run(['git', '-C', str(sdk), 'worktree', 'add', '--detach', str(expected), SDK_PIN],
                       check=True, env=dict(os.environ, GIT_LFS_SKIP_SMUDGE='1'))
        subprocess.run(['git', '-C', str(expected), 'apply', str(a.patch.resolve())], check=True)
        overlays = {'WORKSPACE': checked_overlay,
                    'experimental/native_audio_owner_jni_20261006/BUILD': checked_owner_overlay}
        need(set(android['build_overlays']) == set(overlays), 'Unreviewed Android source overlay')
        for relative, apply in overlays.items():
            path = expected/relative
            need(sha(path) == android['build_overlays'][relative]['before_sha256'], 'Overlay source changed')
            path.write_text(apply(path.read_text()))
            need(sha(path) == android['build_overlays'][relative]['after_sha256'], 'Overlay result differs')
        need(sdk_snapshot(sdk) == sdk_snapshot(expected), 'SDK source tree differs from the reviewed Android patch')
        subprocess.run(['git', '-C', str(sdk), 'worktree', 'remove', '--force', str(expected)], check=True)
        installed = sdk/PACKAGE
        need(not installed.exists(), 'Hosted probe package already exists; stale build refused')
        shutil.copytree(HERE/'native', installed)
        pins = load(HERE/'host-prebuilts.json')
        for pin in pins:
            pointer = subprocess.check_output(['git', '-C', str(sdk), 'show', SDK_PIN+':'+pin['path']], text=True)
            need(f'oid sha256:{pin["sha256"]}\nsize {pin["bytes"]}\n' in pointer, 'Host prebuilt Git-LFS pointer changed')
            path = sdk/pin['path']
            with path.open('rb') as stream: prefix = stream.read(42)
            if prefix.startswith(b'version https://git-lfs.github.com/spec/v1'):
                path.unlink()  # Replace only the confirmed tiny upstream pointer.
            download(pin, path)
        before = sdk_snapshot(sdk)
        write(out/'source-snapshot.json', before)
        need(sha(a.bazel) == BAZEL_SHA, 'Bazel executable identity changed')
        java_home = Path(os.environ['JAVA_HOME'])
        version = subprocess.check_output([str(java_home/'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
        need('version "21' in version, 'JDK21 required')
        cc = shutil.which('clang'); cxx = shutil.which('clang++')
        need(bool(cc and cxx), 'Host clang/clang++ required')
        command = [str(a.bazel.resolve()), '--batch', f'--output_user_root={a.bazel_root.resolve()}',
            f'--server_javabase={java_home}', '--host_jvm_args=-Xmx2048m', 'build', '-c', 'opt',
            '--config=linux', '--jobs=4', '--local_resources=cpu=4', '--local_resources=memory=6144',
            '--remote_executor=', '--remote_cache=', '--noremote_upload_local_results',
            '--repo_env=ANDROID_NDK_HOME=',
            f'--repo_env=CC={cc}', f'--repo_env=CXX={cxx}', *[f'//{PACKAGE}:{t}' for t in TARGETS]]
        # Independent diagnostic provenance; a missing guard never changes
        # the existing compiler/quality acceptance result.
        status['build_command_sha256'] = digest(command)
        try:
            diagnostic_dir = HERE/'encoder-replay'
            policy = load(diagnostic_dir/'reviewed-source-policy.json')
            expected_policy = (diagnostic_dir/'reviewed-source-policy.sha256').read_text().strip()
            need(digest(policy) == expected_policy, 'Encoder diagnostic policy changed')
            sys.path.insert(0, str(diagnostic_dir))
            from hosted_capture import begin_guard, github_context
            guard = begin_guard(sdk, command, github_context(), policy,
                                out/'diagnostic-compile-guard.private.json')
            status['diagnostic_compile_guard_sha256'] = digest(guard)
            status['diagnostic_guard_available'] = True
        except Exception as diagnostic_error:
            status['diagnostic_guard_available'] = False
            status['diagnostic_guard_error'] = str(diagnostic_error)[:1000]
        # Build has its own bounded job; no inference or model download runs
        # while this compiler subprocess is alive. Batch mode exits its JVM.
        status['compile'] = run_compile(command, sdk, out)
        write(out/'build-status.json', status)
        need(status['compile']['passed'], 'Hosted compiler did not complete within its bounded resources',
             status['compile']['classification'])
        need(sdk_snapshot(sdk) == before, 'SDK source changed during host compilation')
        binaries = {t: describe(sdk/'bazel-bin'/PACKAGE/t) for t in TARGETS}
        dynamic = {}
        for target in TARGETS:
            listing = subprocess.check_output(['ldd', str(sdk/'bazel-bin'/PACKAGE/target)], text=True)
            need('not found' not in listing, 'Host runtime library is missing')
            for line in listing.splitlines():
                match = re.search(r'(?:=>\s+)?(/\S+)\s+\(', line)
                if match:
                    path = Path(match[1]).resolve(); dynamic[str(path)] = describe(path)
        status.update(dynamic_libraries=dynamic, classification='build_passed', build_succeeded=True,
            sdk=str(sdk), package=PACKAGE, targets=TARGETS, binaries=binaries,
            source_snapshot_sha256=digest(before), recipe_manifest_sha256=package_identity,
            android_source_receipt_sha256=sha(a.android_receipt), reviewed_patch_sha256=sha(a.patch),
            bazel_sha256=BAZEL_SHA, host_prebuilts={p['path']: {k:p[k] for k in ('bytes','sha256')} for p in pins},
            compile_log_sha256=sha(out/'compile.log'), java=version.strip(),
            host_compiler=subprocess.check_output([cc, '--version'], text=True).strip(),
            host_cpu=host_cpu_metadata(),
            compilation_exited_before_quality=True)
    except Exception as e:
        status.update(classification=e.classification if isinstance(e, GateError) else 'build_failure', error=str(e))
        write(out/'build-status.json', status)
        raise
    write(out/'build-status.json', status)
    return status


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk','android-receipt','patch','bazel','bazel-root','out'): p.add_argument('--'+name, type=Path, required=True)
    a = p.parse_args()
    try: print(json.dumps(build(a), indent=2))
    except Exception as e: print(str(e), file=sys.stderr); return 2
    return 0
if __name__ == '__main__': sys.exit(main())
