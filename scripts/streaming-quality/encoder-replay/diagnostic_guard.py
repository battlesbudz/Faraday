#!/usr/bin/env python3
"""Own the complete optional diagnostic process tree before model admission.

The latch is written before any child starts. A cancelled or killed supervisor
cannot accidentally authorize the next workload: only its checked empty-tree
receipt clears the latch. Capture failure itself remains optional after cleanup.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from build_probes import run_compile
from capsule_writer import digest, file_identity, need
from hosted_capture import github_context
from diagnostic_status import Status, Parser, build_dir_from_argv, validate_context, read as read_status

LATCH = 'diagnostic-cleanup.json'
REPORT = 'diagnostic-supervisor/report.private.json'


def save(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.'+path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True, allow_nan=False)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def read(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024**2,
         'Missing or unsafe diagnostic cleanup evidence')
    return json.loads(path.read_text())


def clean_report(report):
    if report.get('started') is False:
        return report.get('classification') == 'build_resource_blocked' or report.get('cleanup_verified') is True
    return (report.get('started') is True and report.get('cleanup_verified') is True
            and report.get('surviving_process_count') == 0)


def verify_cleanup(build_dir, context):
    build_dir = Path(build_dir)
    latch = read(build_dir/LATCH)
    need(latch.get('schema_version') == 1 and latch.get('context') == context,
         'Diagnostic cleanup belongs to a different run/source/attempt')
    need(latch.get('state') == 'complete' and latch.get('cleanup_verified') is True,
         'Diagnostic process cleanup is still pending or uncertain')
    report_path = build_dir/REPORT
    report = read(report_path)
    need(file_identity(report_path)['sha256'] == latch.get('report_sha256'),
         'Diagnostic cleanup receipt changed')
    need(report.get('resource_profile') == 'diagnostic' and clean_report(report),
         'Diagnostic process tree was not positively cleared')
    return report


def supervise(command, build_dir, context, *, wall_seconds=180, cleanup_seconds=5, status=None):
    build_dir = Path(build_dir)
    need(build_dir.is_dir() and not (build_dir/LATCH).exists(), 'Fresh diagnostic cleanup latch required')
    status = status or Status(build_dir, context)
    status.phase('preflight')
    save(build_dir/LATCH, {'schema_version': 1, 'context': context,
                         'state': 'pending', 'cleanup_verified': False})
    out = build_dir/'diagnostic-supervisor'
    out.mkdir()
    # Any exception before a complete supervisor receipt leaves the latch
    # pending. This includes SIGKILL and failures while inspecting /proc.
    try:
        report = run_compile(command, build_dir, out, wall_seconds=wall_seconds,
                             cleanup_seconds=cleanup_seconds, resource_profile='diagnostic')
    except BaseException:
        status.failed(code='supervisor_interrupted', kind='supervision')
        raise
    save(build_dir/REPORT, report)
    safe = clean_report(report)
    save(build_dir/LATCH, {'schema_version': 1, 'context': context,
                         'state': 'complete' if safe else 'uncertain', 'cleanup_verified': safe,
                         'report_sha256': file_identity(build_dir/REPORT)['sha256']})
    status.finalize(report, safe)
    return report


def run_tests(status, suite=None):
    """Keep full unittest output private, retain the first bounded error record."""
    class Result(unittest.TextTestResult):
        def retain(self, error):
            current = read_status(status.path, status.context, status.invocation_id)
            if current['outcome'] == 'pending':
                status.failed(error[1])

        def addError(self, test, error):
            self.retain(error); super().addError(test, error)

        def addFailure(self, test, error):
            self.retain(error); super().addFailure(test, error)

    if suite is None:
        suite = unittest.defaultTestLoader.discover(str(HERE), pattern='test_*.py')
    result = unittest.TextTestRunner(verbosity=2, resultclass=Result).run(suite)
    return 0 if result.wasSuccessful() else 1


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    status = None
    try:
        build_dir = build_dir_from_argv(argv)
        try:
            context = validate_context(github_context())
        except Exception:
            status = Status(build_dir, None)
            raise
        parser = Parser(description=__doc__)
        parser.add_argument('--child', action='store_true', help=argparse.SUPPRESS)
        parser.add_argument('--tests', action='store_true', help=argparse.SUPPRESS)
        parser.add_argument('--status-instance', help=argparse.SUPPRESS)
        for name in ('build-dir', 'guard', 'policy', 'out', 'repository-cache'):
            parser.add_argument('--'+name, type=Path, required=True)
        parser.add_argument('--expected-policy-sha256', required=True)
        # The outer producer writes before parsing the complete argument list.
        # A child already has the parent's receipt, which remains pending even
        # if this child's argument parsing fails.
        if '--child' not in argv and '--tests' not in argv:
            status = Status(build_dir, context)
        args = parser.parse_args(argv)
        if args.child or args.tests:
            need(not (args.child and args.tests), 'One diagnostic child role required')
            need(args.status_instance is not None, 'Parent diagnostic status required')
            status = Status(build_dir, context, args.status_instance)
            if args.tests:
                status.phase('tests')
                return run_tests(status)
            # Both tests and the collector remain descendants of the unchanged
            # outer full-tree supervisor. Neither child owns the cleanup latch.
            status.phase('tests')
            forwarded = [value for value in argv if value != '--child']
            tests = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--tests', *forwarded])
            if tests.returncode:
                if read_status(status.path, context, status.invocation_id)['outcome'] == 'pending':
                    status.failed(code='tests_failed', kind='subprocess_exit')
                return tests.returncode
            status.phase('guarded_metadata')
            child = subprocess.run([sys.executable, str(HERE/'hosted_capture.py'), *forwarded])
            if read_status(status.path, context, status.invocation_id)['outcome'] == 'pending':
                status.failed(code='collector_exit', kind='subprocess_exit')
            return child.returncode
        need(args.status_instance is None, 'Only diagnostic children resume a receipt')
        forwarded = []
        for name in ('build-dir', 'guard', 'policy', 'out', 'repository-cache'):
            forwarded += ['--'+name, str(getattr(args, name.replace('-', '_')).resolve())]
        forwarded += ['--expected-policy-sha256', args.expected_policy_sha256,
                      '--status-instance', status.invocation_id]
        report = supervise([sys.executable, str(Path(__file__).resolve()), '--child', *forwarded],
                           args.build_dir, context, status=status)
        print(json.dumps(status.value, sort_keys=True))
        return 0 if report['passed'] and status.value['outcome'] == 'passed' else 2
    except Exception as error:
        # Full detail stays in the existing private outer log for child errors.
        # Console/public JSON contains only controlled error codes and locations.
        if status is not None:
            if status.value['context'] is not None and status.value['outcome'] != 'failed':
                status.failed(error)
            print(json.dumps(status.value, sort_keys=True))
        else:
            print(json.dumps({'diagnostic_passed': False, 'error_code': 'invalid_receipt'}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
