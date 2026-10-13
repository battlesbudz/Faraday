"""Fixed hosted probe profiles with complete Linux child-tree cleanup.

Compiler/diagnostic policies are unchanged. Their ownership primitives are reused,
never their log collector: model stdout/stderr stay separate and out of receipts.
"""
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time

from build_probes import compile_memory, owned_tree, process_table, set_subreaper
from common import GateError, load, need, verify

GIB = 1024**3
MIB = 1024**2
BUDGET = dict(wall_seconds=240, cpu_seconds=240, address_space_bytes=4*GIB,
              rss_watchdog_bytes=3584*MIB, minimum_mem_available_bytes=5*GIB,
              system_reserve_bytes=GIB, cpu_threads=1, cpu_soft_limit_seconds=239,
              maximum_regular_output_file_bytes=128*MIB)
FULL_E2B_PROFILE = 'hosted_full_e2b_context640_control'
FULL_E2B_BUDGET = dict(BUDGET, address_space_bytes=6*GIB,
                     rss_watchdog_bytes=4*GIB, minimum_mem_available_bytes=6*GIB)
CLEANUP_LATCH = 'model-process-cleanup.json'
ACTIVE_LOCK = 'model-process-active.json'
# A failed observation, cleanup, or receipt write poisons this orchestrator too.
# Selecting a different output directory must not bypass uncertain ownership.
_CLEANUP_UNCERTAIN = False
_POLL_SECONDS = .05
_CLEANUP_SECONDS = 5.0


def available():
    """Effective headroom: the minimum of host and visible cgroup ancestors."""
    return compile_memory()['effective_available_bytes']


def _save(path, value):
    temporary = path.with_name(path.name+'.pending')
    with temporary.open('x') as target:
        target.write(json.dumps(value, indent=2)+'\n')
        target.flush()
        os.fsync(target.fileno())
    temporary.replace(path)


def verify_cleanup(root):
    need(not _CLEANUP_UNCERTAIN, 'Earlier model cleanup/evidence is uncertain', 'model_cleanup_failure')
    need(not (Path(root)/ACTIVE_LOCK).exists(),
         'Another or interrupted model supervisor owns this build', 'model_cleanup_failure')
    path = Path(root)/CLEANUP_LATCH
    if path.exists():
        try:
            need(not path.is_symlink(), 'Unsafe model cleanup latch', 'model_cleanup_failure')
            value = load(path)
            need(value.get('schema_version') == 1 and value.get('cleanup_verified') is True,
                 'Earlier model tree has not been proven reaped', 'model_cleanup_failure')
        except (OSError, ValueError) as error:
            raise GateError('model_cleanup_failure', 'Unreadable model cleanup latch') from error
    need(not path.with_name(path.name+'.pending').exists(),
         'Incomplete model cleanup receipt', 'model_cleanup_failure')


def _limits(budget):
    resource.setrlimit(resource.RLIMIT_AS, (budget['address_space_bytes'],)*2)
    resource.setrlimit(resource.RLIMIT_CPU, (budget['cpu_soft_limit_seconds'], budget['cpu_seconds']))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (budget['maximum_regular_output_file_bytes'],)*2)
    # Inherited by descendants, even native libraries that ignore thread hints.
    os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})


def run(command, directory, *, cleanup_root=None):
    """Unchanged resource amounts for reassembly/frontend/encoder prerequisites."""
    return _run(command, directory, BUDGET, 'prerequisite', cleanup_root)


def run_full_e2b(binary, request, directory, *, binary_identity, cleanup_root):
    """Only the hash-bound Conversation probe can select the fixed larger profile.

    There is no caller-selected resource override or command/profile argument.
    Source, model, runtime and full request identities are also checked by the gate.
    """
    binary, request, directory = Path(binary), Path(request), Path(directory)
    need(binary.name == 'native_conversation_quality_probe', 'Control requires the Conversation probe')
    verify(binary, binary_identity)
    value = load(request)
    need(value.get('native_binary_sha256') == binary_identity['sha256'] and
         value.get('mode') in ('projected_null', 'raw') and directory.name == value['mode'] and
         value.get('case') == 'transcribe' and value.get('context_tokens') == 640 and
         value.get('max_output_tokens') == 64 and value.get('audio_embedding_tap') is True and
         value.get('resource_profile') == FULL_E2B_PROFILE,
         'Control request/profile mismatch')
    return _run([str(binary), str(request), str(directory/'result.json')], directory,
                FULL_E2B_BUDGET, FULL_E2B_PROFILE, cleanup_root)


def _owned_rows(table, root_pid, known):
    # Before Popen can reap a fast-exiting leader, bind its start identity while
    # it is still our direct child. If observation missed that identity, use
    # only tracked identities and exclusive subreaper descendants; never guess
    # that a subsequently reused PID/process group is the missing leader.
    leader = table.get(root_pid)
    if root_pid not in known and leader is not None and leader['ppid'] == os.getpid():
        known[root_pid] = leader['start']
    return owned_tree(table, root_pid if root_pid in known else -1, known)


def _run(command, directory, budget, profile, cleanup_root):
    global _CLEANUP_UNCERTAIN
    need(os.environ.get('GEMMA_QUALITY_COMPUTE_SLOT') == 'confirmed_by_owner',
         'A coordinated, post-compilation compute slot is required')
    directory = Path(directory)
    cleanup_root = Path(cleanup_root) if cleanup_root is not None else directory.parent
    verify_cleanup(cleanup_root)
    directory.mkdir(parents=True, exist_ok=False)
    latch = cleanup_root/CLEANUP_LATCH
    memory = compile_memory()
    report = {'budget': dict(budget), 'resource_profile': profile, 'execution_started': False,
              'memory_before': memory, 'mem_available_before_bytes': memory['effective_available_bytes'],
              'rss_limit_is_sampled': True, 'rss_scope': 'aggregate_owned_process_tree',
              'rss_sum_may_double_count_shared_pages': True,
              'address_space_limit_scope': 'per_process_inherited_rlimit',
              'cleanup_verified': False, 'sampled_peak_rss_bytes': 0,
              'sampled_peak_address_bytes': 0, 'peak_process_count': 0}
    receipt = directory/'process.json'
    if report['mem_available_before_bytes'] < budget['minimum_mem_available_bytes']:
        report.update(status='resource_blocked', stop_reason='minimum_memory_headroom', cleanup_verified=True)
        _save(receipt, report)
        return report
    need(not any(row['ppid'] == os.getpid() for row in process_table().values()),
         'Model supervisor must exclusively own its child processes', 'model_supervision_failure')
    # Persist before launch. A supervisor SIGKILL leaves this pending, blocking
    # later quality work sharing this build directory, even a new orchestrator.
    # Exclusive creation also prevents simultaneous supervisors from sharing a
    # previously clean build. An interrupted run leaves this lock in place.
    with (cleanup_root/ACTIVE_LOCK).open('x') as active:
        active.write(json.dumps({'schema_version': 1, 'resource_profile': profile})+'\n')
    _CLEANUP_UNCERTAIN = True
    _save(latch, {'schema_version': 1, 'cleanup_verified': False, 'resource_profile': profile})
    env = dict(os.environ, OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1',
               MKL_NUM_THREADS='1', MALLOC_ARENA_MAX='2')
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(key, None)
    child = None; known = {}; signals = {}; cancelled = []; logs = []
    previous_subreaper = None
    start = time.monotonic()
    def interrupted(number, frame): cancelled.append(number)
    def live_owned():
        # Bind identity before the first poll/reap, then drain the leader.
        # Parent exit adopts descendants; the current snapshot finds/reaps them.
        if child.pid not in known: _owned_rows(process_table(), child.pid, known)
        child.poll()
        rows = _owned_rows(process_table(), child.pid, known)
        for pid, row in rows.items():
            if row['state'] == 'Z' and pid != child.pid:
                try: os.waitpid(pid, os.WNOHANG)
                except ChildProcessError: pass
        return {pid: row for pid, row in rows.items() if row['state'] != 'Z'}
    def signal_owned(number):
        # Recheck each identity immediately before kill; never signal a reused PID.
        rows = live_owned()
        for pid, row in rows.items():
            try:
                handle = os.pidfd_open(pid)
            except ProcessLookupError: continue
            try:
                current = process_table().get(pid)
                if current is not None and current['start'] == row['start']:
                    try: signal.pidfd_send_signal(handle, number)
                    except ProcessLookupError: pass
            finally: os.close(handle)
    def terminate_owned():
        signal_owned(signal.SIGTERM)
        until = time.monotonic()+_CLEANUP_SECONDS
        while time.monotonic() < until and live_owned(): time.sleep(_POLL_SECONDS)
        signal_owned(signal.SIGKILL)
        until = time.monotonic()+_CLEANUP_SECONDS
        while time.monotonic() < until and live_owned(): time.sleep(_POLL_SECONDS)
        # /proc is sampled and can miss the last fork/reparent. After Popen
        # reaps the leader, drain every adopted child and require kernel ECHILD.
        # Exclusive ownership makes waitpid(-1) safe; no unrelated child exists.
        until = time.monotonic()+_CLEANUP_SECONDS
        all_reaped = False
        while time.monotonic() < until:
            live_owned()
            if child.poll() is not None:
                while time.monotonic() < until:
                    try: pid, _ = os.waitpid(-1, os.WNOHANG)
                    except ChildProcessError:
                        all_reaped = True
                        break
                    if pid == 0: break
                if all_reaped: break
            signal_owned(signal.SIGKILL)
            time.sleep(_POLL_SECONDS)
        remaining = _owned_rows(process_table(), child.pid, known)
        report['surviving_process_count'] = sum(row['state'] != 'Z' for row in remaining.values())
        report['unreaped_zombie_count'] = sum(row['state'] == 'Z' for row in remaining.values())
        report['all_children_reaped'] = all_reaped
        report['children_reap_proof'] = 'ECHILD' if all_reaped else 'unverified'
        report['cleanup_verified'] = all_reaped and not remaining and child.poll() is not None
    try:
        previous_subreaper = set_subreaper(True)
        for number in (signal.SIGINT, signal.SIGTERM):
            signals[number] = signal.getsignal(number); signal.signal(number, interrupted)
        for name in ('stdout.log', 'stderr.log'):
            fd = os.open(directory/name, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600)
            logs.append(os.fdopen(fd, 'wb'))
        child = subprocess.Popen(list(map(str, command)), stdin=subprocess.DEVNULL, env=env,
            stdout=logs[0], stderr=logs[1], start_new_session=True, preexec_fn=lambda: _limits(budget))
        report['execution_started'] = True
        leader = process_table().get(child.pid)
        need(leader is not None and leader['ppid'] == os.getpid(),
             'Cannot establish model leader start identity', 'model_supervision_failure')
        known[child.pid] = leader['start']
        while True:
            rows = live_owned()
            rss = sum(row['rss'] for row in rows.values())
            virtual = 0
            for pid, row in rows.items():
                try:
                    data = Path(f'/proc/{pid}/stat').read_text()
                    fields = data[data.rfind(')')+2:].split()
                    if int(fields[19]) == row['start']: virtual += int(fields[20])
                except (FileNotFoundError, ProcessLookupError): pass
            report['sampled_peak_rss_bytes'] = max(report['sampled_peak_rss_bytes'], rss)
            report['sampled_peak_address_bytes'] = max(report['sampled_peak_address_bytes'], virtual)
            report['peak_process_count'] = max(report['peak_process_count'], len(rows))
            reason = None
            if cancelled: reason = 'signal'
            elif time.monotonic()-start >= budget['wall_seconds']: reason = 'wall_timeout'
            elif rss > budget['rss_watchdog_bytes']: reason = 'rss_watchdog_limit'
            elif available() < budget['system_reserve_bytes']: reason = 'system_memory_reserve'
            if reason:
                report.update(status='failed', stop_reason=reason)
                break
            code = child.poll()
            if code is not None:
                report.update(exit_code=code, stop_reason=None, status='completed' if code == 0 else 'failed')
                if live_owned(): report.update(status='failed', stop_reason='descendants_survived_probe')
                break
            time.sleep(_POLL_SECONDS)
    except BaseException as error:
        # Error type only: exception messages can contain model/subprocess text.
        report.update(status='failed', stop_reason='supervision_failure', supervision_error=type(error).__name__)
    finally:
        cleanup_errors = []
        def record_cleanup(error): cleanup_errors.append(type(error).__name__)
        try:
            if child is not None:
                try: terminate_owned()
                except BaseException as error:
                    record_cleanup(error)
                    report['cleanup_verified'] = False
                    # When /proc is unavailable, the live direct child still has
                    # its original private group. Kill it, then retry ownership.
                    try:
                        if child.poll() is None:
                            try: os.killpg(child.pid, signal.SIGKILL)
                            except ProcessLookupError: pass
                            child.wait(timeout=_CLEANUP_SECONDS)
                    except BaseException as fallback_error: record_cleanup(fallback_error)
                    try: terminate_owned()
                    except BaseException as retry_error: record_cleanup(retry_error)
                report['exit_code'] = child.poll()
            else:
                report['cleanup_verified'] = True
                report['surviving_process_count'] = 0
                report['unreaped_zombie_count'] = 0
                report['all_children_reaped'] = True
                report['children_reap_proof'] = 'no_launch'
        except BaseException as error:
            record_cleanup(error); report['cleanup_verified'] = False
        finally:
            for stream in logs:
                try: stream.close()
                except BaseException as error: record_cleanup(error)
            for number, original in signals.items():
                try: signal.signal(number, original)
                except BaseException as error: record_cleanup(error)
            if previous_subreaper is not None:
                try: set_subreaper(previous_subreaper)
                except BaseException as error: record_cleanup(error)
            report['wall_seconds'] = time.monotonic()-start
            if cancelled: report.update(status='failed', stop_reason='signal', signal=cancelled[0])
            if child is not None and 'supervision_error' in report:
                cleanup_errors.append('ObservationFailure')
            if cleanup_errors or not report['cleanup_verified']:
                report.update(status='failed', execution_stop_reason=report.get('stop_reason'),
                              stop_reason='cleanup_failure', cleanup_verified=False,
                              cleanup_errors=cleanup_errors)
    # If either write fails, the pending latch and in-process poison remain.
    _save(receipt, report)
    if report['cleanup_verified']:
        _save(latch, {'schema_version': 1, 'cleanup_verified': True, 'resource_profile': profile})
        (cleanup_root/ACTIVE_LOCK).unlink()
        _CLEANUP_UNCERTAIN = False
    return report
