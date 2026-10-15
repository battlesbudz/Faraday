"""Real small processes only; no Bazel, compiler or model execution."""
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import patch

import diagnostic_guard as guard
import build_probes
import run_quality
from common import GateError
from hosted_capture import public_graph
from trace_compiler_inputs import source_record

CONTEXT = {'repository':'battlesbudz/Faraday','run_id':123,'run_attempt':2,
           'head_sha':'a'*40,'source_commit':'b'*40}
MEMORY = {'host_mem_available_bytes':12*1024**3,'cgroup_remaining_bytes':None,
          'effective_available_bytes':12*1024**3}


class DiagnosticOwnershipTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_code(self, code, *, memory=MEMORY):
        with patch.object(build_probes,'compile_memory',return_value=dict(memory)), contextlib.redirect_stdout(io.StringIO()):
            return guard.supervise([sys.executable,'-c',code],self.root,CONTEXT,
                                   wall_seconds=2,cleanup_seconds=.15)

    def test_success_has_same_run_cleanup_proof(self):
        result=self.run_code("print('diagnostic complete')")
        self.assertTrue(result['passed'])
        self.assertEqual(2,result['jobs'])
        self.assertEqual(2*1024**3,result['tree_rss_watchdog_bytes'])
        self.assertEqual(5*1024**3,result['minimum_available_bytes'])
        self.assertEqual(1024**3,result['system_reserve_bytes'])
        self.assertEqual(result,guard.verify_cleanup(self.root,CONTEXT))
        with self.assertRaisesRegex(ValueError,'different run'):
            guard.verify_cleanup(self.root,dict(CONTEXT,run_attempt=3))

    def test_ordinary_capture_failure_stays_optional_after_cleanup(self):
        result=self.run_code('raise SystemExit(7)')
        self.assertFalse(result['passed'])
        self.assertEqual(7,result['exit_code'])
        self.assertTrue(guard.verify_cleanup(self.root,CONTEXT)['cleanup_verified'])

    def test_escaped_descendant_prevents_capture_success_and_is_reaped(self):
        result=self.run_code('''import subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,time;from pathlib import Path;Path('orphan.pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
time.sleep(.15)
''')
        self.assertFalse(result['passed'])
        self.assertEqual('build_supervision_failure',result['classification'])
        self.assertFalse(Path('/proc/'+(self.root/'orphan.pid').read_text()).exists())
        self.assertTrue(guard.verify_cleanup(self.root,CONTEXT)['cleanup_verified'])

    def test_cancellation_reaps_term_ignoring_new_session_and_restores_handler(self):
        old=signal.getsignal(signal.SIGTERM)
        result=self.run_code('''import os,signal,subprocess,sys,time
subprocess.Popen([sys.executable,'-c',"import os,signal,time;from pathlib import Path;Path('orphan.pid').write_text(str(os.getpid()));signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)"],start_new_session=True)
time.sleep(.15)
os.kill(os.getppid(),signal.SIGTERM)
time.sleep(60)
''')
        self.assertFalse(result['passed'])
        self.assertEqual('build_cancelled',result['classification'])
        self.assertEqual(old,signal.getsignal(signal.SIGTERM))
        self.assertFalse(Path('/proc/'+(self.root/'orphan.pid').read_text()).exists())
        self.assertTrue(guard.verify_cleanup(self.root,CONTEXT)['cleanup_verified'])

    def test_resource_refusal_starts_no_child_and_allows_model_admission(self):
        result=self.run_code('raise AssertionError("must not start")',
                             memory=dict(MEMORY,effective_available_bytes=4*1024**3))
        self.assertFalse(result['started'])
        self.assertFalse(result['passed'])
        self.assertEqual('build_resource_blocked',guard.verify_cleanup(self.root,CONTEXT)['classification'])

    def test_interrupted_supervisor_leaves_pending_latch_and_blocks_model_verification(self):
        with patch.object(guard,'run_compile',side_effect=RuntimeError('interrupted')):
            with self.assertRaisesRegex(RuntimeError,'interrupted'):
                guard.supervise([],self.root,CONTEXT)
        (self.root/'build-status.json').write_text(json.dumps({
            'build_succeeded':True,'compilation_exited_before_quality':True,'diagnostic_cleanup_required':True}))
        with patch('hosted_capture.github_context',return_value=CONTEXT), patch.object(run_quality,'verify_recipe_sources') as recipe:
            with self.assertRaisesRegex(ValueError,'pending or uncertain'):
                run_quality.verify_build(self.root)
            recipe.assert_not_called()

    def test_uncertain_cleanup_never_clears_latch(self):
        report={'started':True,'passed':False,'cleanup_verified':False,
                'surviving_process_count':1,'resource_profile':'diagnostic'}
        with patch.object(guard,'run_compile',return_value=report):
            guard.supervise([],self.root,CONTEXT)
        with self.assertRaisesRegex(ValueError,'pending or uncertain'):
            guard.verify_cleanup(self.root,CONTEXT)

    def test_changed_receipt_never_authorizes_model(self):
        self.run_code('pass')
        (self.root/guard.REPORT).write_text('{}')
        with self.assertRaisesRegex(ValueError,'receipt changed'):
            guard.verify_cleanup(self.root,CONTEXT)

    def test_missing_cleanup_contract_cannot_bypass_model_gate(self):
        (self.root/'build-status.json').write_text(json.dumps({
            'build_succeeded':True,'compilation_exited_before_quality':True}))
        with self.assertRaisesRegex(GateError,'cleanup admission contract missing'):
            run_quality.verify_build(self.root)

    def test_diagnostic_profile_cannot_borrow_the_compiler_wall_budget(self):
        with self.assertRaisesRegex(GateError,'budget cannot be expanded'):
            build_probes.run_compile([],self.root,self.root,wall_seconds=181,resource_profile='diagnostic')


class PublicSourcePathTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.execroot=self.root/'output_base/execroot/sdk';self.sdk=self.root/'sdk'
        self.execroot.mkdir(parents=True);self.sdk.mkdir()
        self.repositories=self.root/'output_base/external';self.repositories.mkdir()
        (self.execroot/'external').mkdir()

    def graph(self,item):
        return {'sources':[item],'object_count':0,'origin_counts':{},'response_file_sha256':{},
                'dependency_file_sha256':{},'required_action_outputs':{}}

    def test_private_absolute_source_is_rejected_without_disclosing_path(self):
        private=self.root/'private-note.h';private.write_text('private fixture')
        with self.assertRaisesRegex(ValueError,'outside reviewed') as error:
            source_record(str(private),self.execroot,self.sdk)
        self.assertNotIn(str(private),str(error.exception))

    def test_symlink_to_private_source_is_rejected(self):
        private=self.root/'private-note.h';private.write_text('private fixture')
        (self.execroot/'input.h').symlink_to(private)
        with self.assertRaisesRegex(ValueError,'outside reviewed'):
            source_record('input.h',self.execroot,self.sdk)

    def test_verified_sdk_source_has_only_relative_public_path(self):
        source=self.sdk/'public.h';source.write_text('public fixture')
        item=source_record(str(source),self.execroot,self.sdk)
        public=public_graph(self.graph(item))
        self.assertEqual('public.h',public['source_inputs'][0]['path'])
        self.assertNotIn(str(self.root),json.dumps(public))
        self.assertNotIn('resolved_path',json.dumps(public))

    def test_public_projection_rejects_unrecognized_absolute_path(self):
        item={'path':'/home/runner/private-note.h','origin':'sdk_or_generated','bytes':1,'sha256':'a'*64}
        with self.assertRaisesRegex(ValueError,'private source path'):
            public_graph(self.graph(item))

    def test_parent_traversal_is_rejected_before_source_read(self):
        with self.assertRaisesRegex(ValueError,'escapes reviewed'):
            source_record('../private-note.h',self.execroot,self.sdk)

    def test_real_bazel_repository_directory_and_alias_are_normalized(self):
        repo=self.repositories/'litert';repo.mkdir();header=repo/'public.h';header.write_text('public fixture')
        (self.execroot/'external/litert').symlink_to(repo,target_is_directory=True)
        for name in ['external/litert/public.h',str(header)]:
            item=source_record(name,self.execroot,self.sdk)
            self.assertEqual('litert',item['origin'])
            self.assertEqual('external/litert/public.h',item['path'])
            self.assertNotIn(str(self.root),json.dumps(public_graph(self.graph(item))))

    def test_bazel_repository_alias_cannot_point_to_private_directory(self):
        private=self.root/'private';private.mkdir();(private/'secret.h').write_text('private fixture')
        (self.repositories/'litert').mkdir()
        (self.execroot/'external/litert').symlink_to(private,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'escaped its verified root'):
            source_record('external/litert/secret.h',self.execroot,self.sdk)

    def test_bazel_repository_alias_cannot_impersonate_another_repository(self):
        (self.repositories/'litert').mkdir()
        other=self.repositories/'other';other.mkdir();(other/'public.h').write_text('other fixture')
        (self.execroot/'external/litert').symlink_to(other,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'escaped its verified root'):
            source_record('external/litert/public.h',self.execroot,self.sdk)


if __name__ == '__main__':
    unittest.main()
