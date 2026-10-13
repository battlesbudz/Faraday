"""Bounded public progress/failure metadata; never export exception text or logs.

This receipt is observational, not model-admission authority. Only the guard's
existing hash-bound cleanup latch and private supervisor receipt admit work.
Children may update pending progress; only their supervisor finalizes cleanup.
"""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import uuid

NAME = 'diagnostic-status.json'
LIMIT = 8192
PRODUCER = 'encoder_replay_diagnostic'
PHASES = ('arguments', 'preflight', 'tests', 'guarded_metadata', 'dependencies',
          'source_graph', 'query', 'action_binding', 'licenses', 'packaging')
CLASSES = ('argument', 'identity', 'validation', 'missing_file', 'permission',
           'invalid_json', 'invalid_unicode', 'missing_field', 'subprocess_exit',
           'subprocess_timeout', 'resource', 'supervision', 'internal', 'receipt', 'test_assertion')
DETAILS = {
    'invalid_arguments': 'Diagnostic command arguments were rejected.',
    'identity_unavailable': 'Current run/source/attempt identity was unavailable or invalid.',
    'validation_failed': 'The current phase rejected its input or provenance.',
    'missing_file': 'A required file in the current phase was absent.',
    'permission_denied': 'A required file or operation was not accessible.',
    'invalid_json': 'A required metadata file was not valid JSON.',
    'invalid_unicode': 'A required text file could not be decoded.',
    'missing_field': 'A required metadata field was absent.',
    'subprocess_exit': 'A phase subprocess returned a nonzero exit status.',
    'subprocess_timeout': 'A phase subprocess exceeded its existing deadline.',
    'tests_failed': 'The diagnostic unit-test subprocess failed.',
    'test_assertion_failed': 'A diagnostic unit-test assertion failed.',
    'collector_exit': 'The collector exited without a terminal status.',
    'internal_error': 'The current phase raised an unclassified exception.',
    'supervisor_interrupted': 'The supervisor did not produce a complete cleanup receipt.',
    'supervisor_stopped': 'The supervisor stopped the diagnostic process tree.',
    'cleanup_uncertain': 'Full-tree cleanup was not positively verified.',
    'resource_blocked': 'Existing diagnostic resource admission refused to start a child.',
    'invalid_receipt': 'The diagnostic progress or supervisor receipt was invalid.',
    'missing_terminal_status': 'The diagnostic child did not record a terminal status.',
    'guard_binding': 'The successful build did not bind this same-run precompile guard.',
    'source_changed': 'A guarded source snapshot changed.',
    'file_identity': 'A guarded file identity did not match.',
    'source_graph_unresolved': 'The source/object/link graph was incomplete.',
    'query_failed': 'The bounded metadata query failed.',
    'duplicate_producer': 'An action output had duplicate producers.',
    'missing_producer': 'A required built output had no captured action producer.',
    'tool_not_guarded': 'A compiler/link tool was outside the precompile allowlist.',
    'policy_changed': 'The reviewed source/license policy identity changed.',
}
# Only these literal known messages/prefixes select more specific public codes.
# The original strings, suffixes, command arguments and exception repr never leave.
KNOWN = {
    'Build did not bind this precompile guard': 'guard_binding',
    'SDK source changed across build': 'source_changed',
    'SDK source changed during diagnostic query': 'source_changed',
    'Unresolved source/object/link graph': 'source_graph_unresolved',
    'Bounded metadata query failed': 'query_failed',
    'Compiler/link tool is outside the precompile allowlist': 'tool_not_guarded',
    'Compiler/link tool was not guarded before compilation': 'tool_not_guarded',
    'Reviewed source/license policy changed': 'policy_changed',
    'Reviewed policy identity mismatch': 'policy_changed',
}
PREFIXES = {'File identity mismatch: ': 'file_identity',
            'Duplicate producer for output ': 'duplicate_producer',
            'Missing action producers for required output(s): ': 'missing_producer'}
MODULES = {'diagnostic_guard.py', 'hosted_capture.py', 'diagnostic_status.py',
           'capsule_writer.py', 'hash_bazel_action_metadata.py',
           'trace_compiler_inputs.py', 'replay_artifact_inventory.py',
           'test_collector.py', 'test_diagnostic_guard.py', 'test_diagnostic_status.py',
           'test_source_normalization.py'}
CLASSIFICATIONS = {'pending', 'invalid', 'build_resource_blocked', 'build_passed',
                   'build_compile_failure', 'build_supervision_failure', 'build_cancelled',
                   'build_timeout', 'build_resource_failure', 'build_output_limit',
                   'build_evidence_failure', 'build_cleanup_failure'}
STOP_REASONS = {None, 'signal', 'wall_timeout', 'process_tree_rss', 'system_memory_reserve',
                'compiler_log_size', 'descendants_survived_compiler'}


def require(condition):
    if not condition:
        raise ValueError('Invalid diagnostic status receipt')


def integer(value, minimum=0, maximum=2**63-1):
    return type(value) is int and minimum <= value <= maximum


def validate_context(context):
    require(type(context) is dict and set(context) == {
        'repository', 'run_id', 'run_attempt', 'head_sha', 'source_commit'})
    require(context['repository'] == 'battlesbudz/Faraday')
    for key in ('run_id', 'run_attempt'):
        require(integer(context[key], 1))
    for key in ('head_sha', 'source_commit'):
        require(type(context[key]) is str and re.fullmatch('[0-9a-f]{40}', context[key]) is not None)
    return context


def error_record(code, kind, error=None):
    require(code in DETAILS and kind in CLASSES)
    location = None
    if error is not None:
        tb = error.__traceback__
        while tb:
            source = Path(tb.tb_frame.f_code.co_filename)
            # Only this reviewed directory supplies public source locations.
            if source.parent == Path(__file__).resolve().parent and source.name in MODULES:
                location = {'module': source.name, 'line': tb.tb_lineno}
            tb = tb.tb_next
    return {'class': kind, 'code': code, 'detail': DETAILS[code], 'location': location}


def classify(error):
    if isinstance(error, ArgumentError): code, kind = 'invalid_arguments', 'argument'
    elif isinstance(error, FileNotFoundError): code, kind = 'missing_file', 'missing_file'
    elif isinstance(error, PermissionError): code, kind = 'permission_denied', 'permission'
    elif isinstance(error, UnicodeError): code, kind = 'invalid_unicode', 'invalid_unicode'
    elif isinstance(error, json.JSONDecodeError): code, kind = 'invalid_json', 'invalid_json'
    elif isinstance(error, KeyError): code, kind = 'missing_field', 'missing_field'
    elif isinstance(error, subprocess.TimeoutExpired): code, kind = 'subprocess_timeout', 'subprocess_timeout'
    elif isinstance(error, subprocess.CalledProcessError): code, kind = 'subprocess_exit', 'subprocess_exit'
    elif isinstance(error, AssertionError): code, kind = 'test_assertion_failed', 'test_assertion'
    elif isinstance(error, ValueError):
        code, kind = 'validation_failed', 'validation'
        # Read a bounded existing string; do not invoke arbitrary __str__ code.
        message = error.args[0] if error.args and type(error.args[0]) is str else ''
        if len(message) <= 4096:
            code = KNOWN.get(message, code)
            for prefix, specific in PREFIXES.items():
                if message.startswith(prefix): code = specific; break
    else: code, kind = 'internal_error', 'internal'
    return error_record(code, kind, error)


def validate(value, context=None, invocation_id=None):
    require(type(value) is dict and set(value) == {
        'schema_version', 'producer', 'invocation_id', 'context', 'state', 'phase',
        'outcome', 'error', 'child', 'cleanup', 'resources', 'quality_acceptance_unchanged'})
    require(type(value['schema_version']) is int and value['schema_version'] == 1)
    require(value['producer'] == PRODUCER)
    require(type(value['invocation_id']) is str and re.fullmatch('[0-9a-f]{32}', value['invocation_id']) is not None)
    if invocation_id is not None: require(value['invocation_id'] == invocation_id)
    if value['context'] is not None: validate_context(value['context'])
    if context is not None: require(value['context'] == validate_context(context))
    require(value['state'] in ('pending', 'complete', 'uncertain'))
    require(value['phase'] in PHASES and value['outcome'] in ('pending', 'passed', 'failed'))
    require(value['quality_acceptance_unchanged'] is True)
    error = value['error']
    if error is not None:
        require(type(error) is dict and set(error) == {'class', 'code', 'detail', 'location'})
        require(error['class'] in CLASSES and error['code'] in DETAILS)
        require(error['detail'] == DETAILS[error['code']])
        if error['location'] is not None:
            loc = error['location']
            require(type(loc) is dict and set(loc) == {'module', 'line'})
            require(loc['module'] in MODULES and integer(loc['line'], 1, 100000))
    child = value['child']; cleanup = value['cleanup']; resources = value['resources']
    require(type(child) is dict and set(child) == {'started', 'exit_code'})
    require(child['started'] is None or type(child['started']) is bool)
    require(child['exit_code'] is None or integer(child['exit_code'], -255, 255))
    require(type(cleanup) is dict and set(cleanup) == {'verified', 'surviving_process_count'})
    require(type(cleanup['verified']) is bool)
    require(cleanup['surviving_process_count'] is None or integer(cleanup['surviving_process_count'], 0, 1000000))
    require(type(resources) is dict and set(resources) == {
        'classification', 'stop_reason', 'wall_milliseconds', 'peak_tree_rss_bytes'})
    require(resources['classification'] in CLASSIFICATIONS and resources['stop_reason'] in STOP_REASONS)
    require(resources['wall_milliseconds'] is None or integer(resources['wall_milliseconds'], 0, 86400000))
    require(resources['peak_tree_rss_bytes'] is None or integer(resources['peak_tree_rss_bytes']))
    require(value['state'] != 'complete' or (cleanup['verified'] is True and value['outcome'] != 'pending'))
    require(not cleanup['verified'] or value['state'] == 'complete')
    require(not cleanup['verified'] or child['started'] is False or cleanup['surviving_process_count'] == 0)
    require(value['outcome'] != 'passed' or error is None)
    require(value['outcome'] != 'failed' or error is not None)
    require(value['context'] is not None or (value['outcome'] == 'failed' and not cleanup['verified']))
    require(len(json.dumps(value, ensure_ascii=True, allow_nan=False).encode()) <= LIMIT)
    return value


def no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value); value[key] = item
    return value


def read(path, context=None, invocation_id=None):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
        data = stream.read(LIMIT + 1); require(len(data) <= LIMIT)
    try:
        return validate(json.loads(data, object_pairs_hook=no_duplicates), context, invocation_id)
    except (TypeError, KeyError, RecursionError):
        raise ValueError('Invalid diagnostic status receipt') from None


def write(path, value, *, fresh=False):
    validate(value)
    data = (json.dumps(value, sort_keys=True, ensure_ascii=True, allow_nan=False) + '\n').encode()
    require(len(data) <= LIMIT)
    fd, temporary = tempfile.mkstemp(prefix='.' + NAME, dir=Path(path).parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        if fresh: os.link(temporary, path)  # Atomic exclusive publication, including dangling symlinks.
        else: os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


class Status:
    def __init__(self, build_dir, context, invocation_id=None):
        self.path = Path(build_dir) / NAME
        if invocation_id is not None:
            self.value = read(self.path, context, invocation_id)
            require(self.value['state'] == 'pending' and self.value['outcome'] == 'pending')
        else:
            if context is not None: validate_context(context)
            self.value = {'schema_version': 1, 'producer': PRODUCER, 'invocation_id': uuid.uuid4().hex,
                'context': context, 'state': 'pending', 'phase': 'arguments',
                'outcome': 'pending' if context is not None else 'failed',
                'error': None if context is not None else error_record('identity_unavailable', 'identity'),
                'child': {'started': None, 'exit_code': None},
                'cleanup': {'verified': False, 'surviving_process_count': None},
                'resources': {'classification': 'pending', 'stop_reason': None,
                              'wall_milliseconds': None, 'peak_tree_rss_bytes': None},
                'quality_acceptance_unchanged': True}
            write(self.path, self.value, fresh=True)
        self.context = context; self.invocation_id = self.value['invocation_id']

    def update(self, **fields):
        # Reject duplicate/foreign/tampered producers rather than overwrite them.
        self.value = read(self.path, self.context, self.invocation_id)
        self.value.update(fields); write(self.path, self.value)

    def phase(self, phase):
        require(phase in PHASES and self.value['state'] == 'pending')
        self.update(phase=phase)

    def failed(self, error=None, *, code=None, kind=None):
        self.update(outcome='failed', error=classify(error) if code is None else error_record(code, kind))

    def passed(self):
        self.update(outcome='passed', error=None)

    def finalize(self, report, cleanup_verified):
        # Reload the child's last atomic progress before projection. Invalid or
        # foreign progress is not published; replace with our original identity.
        try: self.value = read(self.path, self.context, self.invocation_id)
        except (OSError, ValueError, TypeError, KeyError):
            self.value.update(outcome='failed', error=error_record('invalid_receipt', 'receipt'))
        classification = report.get('classification')
        classification = classification if type(classification) is str and classification in CLASSIFICATIONS else 'invalid'
        stop = report.get('stop_reason')
        stop = stop if type(stop) in (str, type(None)) and stop in STOP_REASONS else None
        started = report.get('started') if type(report.get('started')) is bool else None
        code = report.get('exit_code'); code = code if integer(code, -255, 255) else None
        count = report.get('surviving_process_count'); count = count if integer(count, 0, 1000000) else None
        peak = report.get('sampled_peak_tree_rss_bytes'); peak = peak if integer(peak) else None
        wall = report.get('wall_seconds')
        wall = round(wall * 1000) if type(wall) in (int, float) and 0 <= wall <= 86400 else None
        safe = cleanup_verified is True and (started is False or count == 0)
        self.value.update(state='complete' if safe else 'uncertain',
            child={'started': started, 'exit_code': code},
            cleanup={'verified': safe, 'surviving_process_count': count},
            resources={'classification': classification, 'stop_reason': stop,
                       'wall_milliseconds': wall, 'peak_tree_rss_bytes': peak})
        if not safe: error = error_record('cleanup_uncertain', 'supervision')
        elif classification == 'build_resource_blocked': error = error_record('resource_blocked', 'resource')
        elif classification == 'invalid': error = error_record('invalid_receipt', 'receipt')
        elif classification not in ('build_passed', 'build_compile_failure'): error = error_record('supervisor_stopped', 'supervision')
        elif self.value['outcome'] == 'failed': error = self.value['error']
        elif self.value['outcome'] == 'pending': error = error_record('missing_terminal_status', 'receipt')
        elif not (report.get('passed') is True and code == 0): error = error_record('collector_exit', 'subprocess_exit')
        else: error = None
        self.value.update(outcome='failed' if error else 'passed', error=error)
        write(self.path, self.value)
        return self.value


class ArgumentError(ValueError):
    pass


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ArgumentError('Diagnostic arguments rejected')


def build_dir_from_argv(argv):
    """Locate only a unique existing destination before full argument parsing."""
    found = []
    for index, value in enumerate(argv):
        if value == '--build-dir' and index + 1 < len(argv): found.append(argv[index + 1])
        elif value.startswith('--build-dir='): found.append(value.split('=', 1)[1])
    if len(found) != 1: raise ArgumentError('Unique build directory required')
    directory = Path(found[0]).resolve(strict=True)
    if not directory.is_dir(): raise ArgumentError('Existing build directory required')
    return directory


def instance_from_argv(argv):
    """Recognize a single internal resume ID without parsing arbitrary values."""
    found = []
    for index, value in enumerate(argv):
        if value == '--status-instance' and index + 1 < len(argv): found.append(argv[index + 1])
        elif value.startswith('--status-instance='): found.append(value.split('=', 1)[1])
    if len(found) == 1 and re.fullmatch('[0-9a-f]{32}', found[0]): return found[0]
    return None
