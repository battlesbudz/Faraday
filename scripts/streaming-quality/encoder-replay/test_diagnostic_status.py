"""Public evidence tests: standard library only, synthetic data, no model access."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import diagnostic_guard as guard
import diagnostic_status as status
import hosted_capture as capture

CONTEXT = {'repository': 'battlesbudz/Faraday', 'run_id': 123, 'run_attempt': 2,
           'head_sha': 'a'*40, 'source_commit': 'b'*40}
REPORT = {'started': True, 'passed': False, 'exit_code': 2, 'resource_profile': 'diagnostic',
          'classification': 'build_compile_failure', 'cleanup_verified': True,
          'surviving_process_count': 0, 'wall_seconds': 4.1, 'sampled_peak_tree_rss_bytes': 512}


class PublicStatusTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def create(self): return status.Status(self.root, CONTEXT)
    def read(self): return status.read(self.root/status.NAME, CONTEXT)

    def test_failure_preserves_stage_and_exact_identity_after_cleanup(self):
        owner = self.create()
        child = status.Status(self.root, CONTEXT, owner.invocation_id)
        child.phase('dependencies')
        child.failed(FileNotFoundError('/private/credential-file'))
        result = owner.finalize(REPORT, True)
        self.assertEqual('dependencies', result['phase'])
        self.assertEqual('missing_file', result['error']['code'])
        self.assertEqual(CONTEXT, result['context'])
        self.assertEqual(2, result['child']['exit_code'])
        self.assertEqual(4100, result['resources']['wall_milliseconds'])
        self.assertEqual('complete', result['state'])
        self.assertTrue(result['cleanup']['verified'])
        self.assertNotIn('/private', json.dumps(result))
        self.assertEqual(result, self.read())

    def test_no_raw_strings_are_exported_for_any_exception_kind(self):
        secret = 'sk-secret PASSWORD=value /home/private/key C:\\Users\\person\\key\nTOKEN=value\x00\ud800'
        errors = [ValueError(secret), KeyError(secret), FileNotFoundError(secret),
                  PermissionError(secret), RuntimeError(secret),
                  subprocess.CalledProcessError(7, [secret], output=secret, stderr=secret),
                  subprocess.TimeoutExpired(secret, 10, output=secret),
                  UnicodeDecodeError('utf8', b'\xff', 0, 1, secret),
                  json.JSONDecodeError(secret, secret, 0), ValueError('X'*1000000)]
        for error in errors:
            with self.subTest(kind=type(error).__name__):
                record = status.classify(error)
                encoded = json.dumps(record, ensure_ascii=True)
                self.assertLess(len(encoded), 512)
                for private in ('sk-secret', 'PASSWORD', '/home/private', 'Users', 'TOKEN', '\\ud800'):
                    self.assertNotIn(private, encoded)

    def test_known_prefix_has_fixed_detail_without_arbitrary_suffix(self):
        record = status.classify(ValueError('Duplicate producer for output /home/private\nTOKEN=secret'))
        self.assertEqual('duplicate_producer', record['code'])
        self.assertNotIn('TOKEN', json.dumps(record))

    def test_exception_string_conversion_is_never_called(self):
        class Evil(ValueError):
            def __str__(self): raise AssertionError('must not stringify')
        self.assertEqual('validation_failed', status.classify(Evil(object()))['code'])

    def test_duplicates_and_foreign_producers_are_rejected(self):
        owner = self.create()
        with self.assertRaises(FileExistsError): self.create()
        for context, invocation in [(dict(CONTEXT, run_attempt=3), owner.invocation_id),
                                    (CONTEXT, '0'*32)]:
            with self.assertRaises(ValueError): status.Status(self.root, context, invocation)
        document = copy.deepcopy(owner.value); document['producer'] = 'foreign'
        with self.assertRaises(ValueError): status.validate(document)

    def test_duplicate_json_keys_are_rejected(self):
        owner = self.create()
        (self.root/status.NAME).write_text(json.dumps(owner.value)[:-1] + ',"producer":"foreign"}')
        with self.assertRaises(ValueError): self.read()

    def test_malformed_or_leaking_receipts_fail_validation(self):
        owner = self.create()
        mutations = [lambda x: x.update(secret='private'),
                     lambda x: x.update(phase='/home/private'),
                     lambda x: x['context'].update(run_attempt=True),
                     lambda x: x['context'].update(repository='foreign/repository'),
                     lambda x: x['context'].update(source_commit='a'*40+'\n'),
                     lambda x: x['resources'].update(classification='secret'),
                     lambda x: x['resources'].update(stop_reason='secret'),
                     lambda x: x['resources'].update(wall_milliseconds=float('nan')),
                     lambda x: x['child'].update(exit_code=True),
                     lambda x: x['cleanup'].update(verified=True),
                     lambda x: x.update(outcome='failed', error={'class':'internal','code':'internal_error',
                           'detail':'private','location':None})]
        for mutate in mutations:
            document = copy.deepcopy(owner.value); mutate(document)
            with self.subTest(mutation=mutate), self.assertRaises((ValueError, TypeError)):
                status.validate(document)

    def test_oversized_invalid_unicode_symlink_and_fifo_are_rejected(self):
        path = self.root/status.NAME
        for data in (b'X'*(status.LIMIT+1), b'\xff'):
            path.write_bytes(data)
            with self.assertRaises((ValueError, UnicodeError)): self.read()
        path.unlink(); target = self.root/'target'; target.write_text('{}'); path.symlink_to(target)
        with self.assertRaises(OSError): self.read()
        path.unlink(); os.mkfifo(path)
        with self.assertRaises(ValueError): self.read()

    def test_foreign_progress_is_replaced_with_failed_original_identity(self):
        owner = self.create()
        data = copy.deepcopy(owner.value); data['context']['run_id'] = 999
        status.write(owner.path, data)
        result = owner.finalize(REPORT, True)
        self.assertEqual(CONTEXT, result['context'])
        self.assertEqual('invalid_receipt', result['error']['code'])
        self.assertEqual('failed', result['outcome'])

    def test_test_runner_retains_first_failure_cause_without_assertion_values(self):
        owner = self.create(); owner.phase('tests')
        class Failing(unittest.TestCase):
            def runTest(self): self.assertEqual('secret API_KEY', '/home/private')
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, guard.run_tests(owner, unittest.TestSuite([Failing()])))
        result = self.read()
        self.assertEqual('tests', result['phase'])
        self.assertEqual('test_assertion_failed', result['error']['code'])
        self.assertEqual('test_diagnostic_status.py', result['error']['location']['module'])
        self.assertEqual(Failing.runTest.__code__.co_firstlineno, result['error']['location']['line'])
        self.assertNotIn('API_KEY', json.dumps(result))

    def test_stale_parent_failure_preserves_latest_child_phase(self):
        owner = self.create(); child = status.Status(self.root, CONTEXT, owner.invocation_id)
        child.phase('query')
        owner.failed(code='supervisor_interrupted', kind='supervision')
        self.assertEqual('query', self.read()['phase'])

    def test_deeply_nested_receipt_is_rejected_with_bounded_read(self):
        (self.root/status.NAME).write_text('['*2000 + '0' + ']'*2000)
        with self.assertRaises(ValueError): self.read()

    def test_resource_projection_never_exports_raw_supervisor_metadata(self):
        owner = self.create(); owner.failed(code='tests_failed', kind='subprocess_exit')
        result = owner.finalize(dict(REPORT, error='secret', reason='private', samples=['PASSWORD'],
                                    command=['/home/private'], diagnostic={'tail':'API_KEY=secret'}), True)
        encoded = json.dumps(result)
        for private in ('secret','private','PASSWORD','API_KEY'):
            self.assertNotIn(private, encoded)
        self.assertLess(len(encoded.encode()), status.LIMIT)

    def test_no_started_child_on_resource_refusal(self):
        owner = self.create()
        result = owner.finalize({'started':False, 'passed':False, 'classification':'build_resource_blocked'}, True)
        self.assertEqual('resource_blocked', result['error']['code'])
        self.assertFalse(result['child']['started'])
        self.assertTrue(result['cleanup']['verified'])

    def test_uncertain_cleanup_cannot_become_success(self):
        owner = self.create(); owner.passed()
        result = owner.finalize(dict(REPORT, passed=True, exit_code=0, classification='build_passed',
                                    cleanup_verified=False, surviving_process_count=1), False)
        self.assertEqual('uncertain', result['state'])
        self.assertEqual('failed', result['outcome'])
        self.assertEqual('cleanup_uncertain', result['error']['code'])

    def test_success_needs_child_terminal_status_and_verified_supervision(self):
        owner = self.create()
        result = owner.finalize(dict(REPORT, passed=True, exit_code=0, classification='build_passed'), True)
        self.assertEqual('missing_terminal_status', result['error']['code'])

    def test_successful_child_and_cleanup_are_recorded_separately(self):
        owner = self.create(); child = status.Status(self.root, CONTEXT, owner.invocation_id)
        child.phase('packaging'); child.passed()
        self.assertEqual('pending', self.read()['state'])
        self.assertFalse(self.read()['cleanup']['verified'])
        result = owner.finalize(dict(REPORT, passed=True, exit_code=0, classification='build_passed'), True)
        self.assertEqual('passed', result['outcome']); self.assertEqual('complete', result['state'])

    def test_supervisor_exception_retains_pending_receipt_and_latch(self):
        with patch.object(guard, 'run_compile', side_effect=RuntimeError('private')):
            with self.assertRaises(RuntimeError): guard.supervise([], self.root, CONTEXT)
        public = self.read(); latch = json.loads((self.root/guard.LATCH).read_text())
        self.assertEqual('supervisor_interrupted', public['error']['code'])
        self.assertEqual('pending', public['state']); self.assertEqual('pending', latch['state'])
        self.assertFalse(public['cleanup']['verified'])
        with self.assertRaises(ValueError): guard.verify_cleanup(self.root, CONTEXT)

    def test_sigkill_leaves_atomic_pending_evidence(self):
        # Kill before any child launches; no orphan or resource-heavy work.
        script = """import sys,time
from pathlib import Path
import diagnostic_guard as guard
context = %r
def stop(*args, **kwargs):
    Path(sys.argv[1], 'ready').write_text('yes')
    time.sleep(20)
guard.run_compile = stop
guard.supervise([], Path(sys.argv[1]), context)
""" % CONTEXT
        env = dict(os.environ, PYTHONPATH=str(Path(guard.__file__).parent))
        child = subprocess.Popen([sys.executable, '-c', script, str(self.root)], env=env,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            until = time.monotonic()+5
            while not (self.root/'ready').exists() and time.monotonic()<until:
                time.sleep(.01)
            self.assertTrue((self.root/'ready').exists())
            child.kill(); child.wait(timeout=2)
            self.assertEqual('pending', self.read()['state'])
            with self.assertRaises(ValueError): guard.verify_cleanup(self.root, CONTEXT)
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=2)


class EarlyCaptureFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def argv(self):
        return ['--build-dir',str(self.root),'--policy',str(self.root/'policy.json'),
                '--guard',str(self.root/'guard.json'),'--out',str(self.root/'out'),
                '--repository-cache',str(self.root/'cache'),'--expected-policy-sha256','a'*64]

    def run_main(self, module, argv):
        stream = io.StringIO()
        with patch.object(module, 'github_context', return_value=CONTEXT), contextlib.redirect_stdout(stream), contextlib.redirect_stderr(stream):
            result = module.main(argv)
        self.assertEqual(2, result)
        self.assertNotIn(str(self.root), stream.getvalue())
        return status.read(self.root/status.NAME, CONTEXT)

    def test_missing_build_status_is_retained_before_collect(self):
        with patch.object(capture, 'collect') as collect:
            value = self.run_main(capture, self.argv())
            collect.assert_not_called()
        self.assertEqual('guarded_metadata', value['phase'])
        self.assertEqual('missing_file', value['error']['code'])

    def test_invalid_arguments_are_retained_without_raw_values(self):
        value = self.run_main(capture, ['--build-dir',str(self.root),'--private=TOKEN\nsecret'])
        self.assertEqual('arguments', value['phase'])
        self.assertEqual('invalid_arguments', value['error']['code'])

    def test_malformed_internal_argument_is_retained_before_full_parse(self):
        value = self.run_main(capture, [*self.argv(), '--status-instance'])
        self.assertEqual('arguments', value['phase'])
        self.assertEqual('invalid_arguments', value['error']['code'])

    def test_guard_invalid_arguments_are_retained_before_supervision(self):
        with patch.object(guard, 'run_compile') as compile:
            value = self.run_main(guard, ['--build-dir',str(self.root)])
            compile.assert_not_called()
        self.assertEqual('invalid_arguments', value['error']['code'])
        self.assertFalse(value['cleanup']['verified'])

    def test_existing_output_is_preflight_failure(self):
        (self.root/'out').mkdir()
        value = self.run_main(capture, self.argv())
        self.assertEqual('preflight', value['phase'])
        self.assertEqual('validation_failed', value['error']['code'])

    def test_invalid_identity_is_explicit_and_never_fabricated(self):
        with patch.object(capture, 'github_context', side_effect=KeyError('SECRET')), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2, capture.main(self.argv()))
        value = status.read(self.root/status.NAME)
        self.assertIsNone(value['context']); self.assertEqual('identity_unavailable', value['error']['code'])
        with self.assertRaises(ValueError): status.read(self.root/status.NAME, CONTEXT)

    def test_tests_failure_stays_in_tests_phase_and_skips_collector(self):
        owner = status.Status(self.root, CONTEXT)
        argv = ['--child', '--status-instance', owner.invocation_id, *self.argv()]
        with patch.object(guard, 'subprocess') as subprocess_mock:
            subprocess_mock.run.return_value.returncode = 2
            value = self.run_main(guard, argv)
            self.assertEqual(1, subprocess_mock.run.call_count)
        self.assertEqual('tests', value['phase']); self.assertEqual('tests_failed', value['error']['code'])


if __name__ == '__main__': unittest.main()
