"""Lightweight tests only: no build, download, model load, or inference."""
import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import patch

import bounded_exec
import build_probes
import common
from common import GateError, write, sha
import export_evidence
import prepare_inputs
import run_quality


def result(mode):
    return dict(interface='native_cpp_conversation', mode=mode, case='transcribe',
        sdk_commit=common.SDK_PIN, litert_workspace_pin=common.LITERT_PIN,
        bundle_sha256=common.BUNDLE['sha256'], producer_sha256=common.PRODUCER,
        manifest_sha256='a'*64, pcm_sha256=common.PCM_SHA, projected_tokens_sha256=common.ROWS_SHA,
        native_binary_sha256='b'*64, native_source_snapshot_sha256='c'*64,
        context_tokens=640, max_output_tokens=64, resource_profile=bounded_exec.FULL_E2B_PROFILE,
        execution_passed=True, fresh_process=True, fresh_conversation=True, checked_drain_delete=True,
        tool_dispatch_count=0, automatic_tool_calling=False, full_bundle_hash_reverified_before_launch=True,
        initial_tokens=0, final_tokens=95, decode_tokens=10, prefill_tokens=85,
        response={'role':'model','content':[{'type':'text','text':'Roses are red, violets are blue.'}]},
        text='Roses are red, violets are blue.', tool_calls=[], decode_at_budget=False,
        audio_embedding_tap=dict(calls=1, bitwise_equal=True, valid_tokens=77, bytes_compared=473088))


class IdentityTests(unittest.TestCase):
    def test_source_manifest_matches(self):
        self.assertEqual(len(common.verify_recipe_sources()), 64)

    def test_historical_model_shapes_are_structural_only(self):
        m = common.load(common.HERE/'model-structure.json')
        self.assertEqual(set(m), {'stateful','static','adapter','eoa'})
        self.assertEqual(m['stateful']['sha256'], common.PRODUCER)
        self.assertFalse(any('path' in x for x in m.values()))
        self.assertEqual(sum(x['bytes'] for x in m.values()), 207168408)

    def test_corrupt_input_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/'x'; p.write_bytes(b'bad')
            with self.assertRaises(GateError): common.verify(p, {'bytes': 3, 'sha256':'0'*64})

    def test_native_sources_pin_cpu_and_budgets(self):
        root = common.HERE/'native'
        cc = (root/'native_conversation_quality_probe.cc').read_text()
        for value in ['constexpr uint64_t kAddressLimit = 6ull * 1024 * 1024 * 1024;',
                      'limit.rlim_cur == kAddressLimit && limit.rlim_max == kAddressLimit',
                      'request.at("context_tokens") == 640',
                      'request.at("resource_profile") == "hosted_full_e2b_context640_control"',
                      'cpu.number_of_threads = 1','SetNumThreads(1)','SetMaxNumTokens(640)',
                      'SetMaxOutputTokens(64)','ThinkingConfig(false, 0)',
                      'enable_speculative_decoding = false','std::memcmp(locked->second, expected.data(), kAudioBytes)']:
            self.assertIn(value, cc)
        self.assertIn('cpu->SetNumThreads(1)', (root/'pinned_encoder_probe.cc').read_text())
        self.assertNotIn('SetNumThreads(2)', (root/'pinned_encoder_probe.cc').read_text())
        self.assertIn(r'CPU_THREADS\t1\n', (root/'pinned_encoder_probe.cc').read_text())

    def test_overlay_transformations_strict(self):
        w='android_ndk_repository(name = "androidndk")'
        self.assertIn('api_level = 30',build_probes.checked_overlay(w))
        with self.assertRaises(ValueError):build_probes.checked_overlay(build_probes.checked_overlay(w))
        owner='    name = "libnative_audio_owner_jni.so",\n    linkstatic = False,\n# Deliberately separate test library:\n    linkstatic = False,'
        out=build_probes.checked_owner_overlay(owner)
        self.assertEqual(out.count('    linkstatic = False,'),1)
        self.assertIn('"@platforms//os:android": True',out)
        self.assertIn('"//conditions:default": False',out)
        with self.assertRaises(ValueError):build_probes.checked_owner_overlay(out)

    def test_no_absolute_source_dependencies(self):
        for p in common.HERE.glob('*.py'):
            self.assertNotIn('/workspace/'+'scratch/', p.read_text())

    def test_compile_not_finished_blocks_quality(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); write(root/'build-status.json', {'build_succeeded':False})
            with self.assertRaises(GateError): run_quality.verify_build(root)

    def test_source_mismatch_cannot_be_relabelled_resource(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            args=type('Args',(),{'out':root/'run','build_dir':root/'build'})()
            with patch.object(run_quality,'verify_build',side_effect=GateError('identity_failure','changed')):
                value=run_quality.run_gate(args)
            self.assertFalse(value['passed']); self.assertEqual(value['classification'],'identity_failure')
            self.assertEqual(value['lanes'],{})

    def test_stale_run_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=type('Args',(),{'out':Path(tmp),'build_dir':Path(tmp)})()
            with self.assertRaises(GateError): run_quality.run_gate(args)


class ClassificationTests(unittest.TestCase):
    def test_explicit_resource_cases(self):
        for p in [{'status':'resource_blocked'}, {'stop_reason':'rss_watchdog_limit'},
                  {'stop_reason':'system_memory_reserve'}, {'stop_reason':'wall_timeout'},
                  {'exit_code':-signal.SIGXCPU}, {'exit_code':-signal.SIGXFSZ}]:
            self.assertEqual(run_quality.classify_process(p), 'resource_constrained')
        for message in ['std::bad_alloc','RESOURCE_EXHAUSTED: allocation failed','mmap: Cannot allocate memory','ENOMEM']:
            self.assertEqual(run_quality.classify_process({'status':'failed'},diagnostic=message),'resource_constrained')

    def test_actual_loader_mapping_failure_is_resource_evidence(self):
        diagnostic = ('litert_lm_loader.cc:299] Failed to map section: INTERNAL: '
                      '(data) != (((void *) -1)): Failed to map, error: Cannot allocate memory')
        self.assertEqual(run_quality.classify_process({'status':'failed','exit_code':2},
            {'error':'Missing per_layer_embedding_lookup_'}, diagnostic), 'resource_constrained')
        self.assertIsNotNone(run_quality.re.search(run_quality.RESOURCE_PATTERN, diagnostic,
                                                  run_quality.re.I))

    def test_map_failure_without_resource_text_remains_native_failure(self):
        self.assertEqual(run_quality.classify_process({'status':'failed'}, diagnostic=
            'litert_lm_loader.cc:299] Failed to map section: invalid file offset'),
            'native_execution_failure')

    def test_loader_assertion_not_assumed_memory(self):
        p={'status':'failed','exit_code':2}
        self.assertEqual(run_quality.classify_process(p, {'error':'Missing per_layer_embedding_lookup_'}), 'native_execution_failure')
        self.assertEqual(run_quality.classify_process({'status':'failed','exit_code':-9}), 'native_execution_failure')
        self.assertEqual(run_quality.classify_process(p, diagnostic='mmap failed'), 'native_execution_failure')

    def test_success_cannot_hide_native_failure(self):
        self.assertEqual(run_quality.classify_process({'status':'completed'}, {'execution_passed':False}), 'native_execution_failure')

    def test_missing_result_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(run_quality,'run',return_value={'status':'completed','cleanup_verified':True}):
                with self.assertRaises(GateError): run_quality.checked_process([],Path(tmp),results=True)

    def test_memory_gate_never_launches(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,GEMMA_QUALITY_COMPUTE_SLOT='confirmed_by_owner'):
            with patch.object(bounded_exec,'compile_memory',return_value={'effective_available_bytes':4*1024**3}), patch.object(bounded_exec.subprocess,'Popen') as launch:
                receipt=bounded_exec.run(['/not/executed'],Path(tmp)/'run')
                launch.assert_not_called()
            self.assertFalse(receipt['execution_started']); self.assertEqual(receipt['status'],'resource_blocked')

    def test_limits_fixed(self):
        b=bounded_exec.BUDGET
        self.assertEqual((b['address_space_bytes'],b['rss_watchdog_bytes'],b['minimum_mem_available_bytes'],b['system_reserve_bytes']),
                         (4*1024**3,3584*1024**2,5*1024**3,1024**3))
        self.assertEqual((b['wall_seconds'],b['cpu_seconds'],b['cpu_threads']),(240,240,1))
        self.assertGreater(b['maximum_regular_output_file_bytes'],103668112)
        self.assertLess(b['cpu_soft_limit_seconds'],b['cpu_seconds'])


class PairTests(unittest.TestCase):
    def test_pair_with_reference_passes_narrow_scope(self):
        value=run_quality.final_comparison(result('raw'),result('projected_null'))
        self.assertTrue(value['passed']); self.assertFalse(value['full_jni_positive_consumption_passed'])
        self.assertIn('not a new human',value['semantic_reference']['type'])

    def test_equal_wrong_transcripts_fail_reference(self):
        a,b=result('raw'),result('projected_null')
        for x in (a,b):x['text']='Thank you';x['response']['content'][0]['text']='Thank you'
        value=run_quality.final_comparison(a,b)
        self.assertFalse(value['passed']);self.assertEqual(value['classification'],'semantic_reference_mismatch')

    def test_unequal_full_response_fails_even_equal_text(self):
        a,b=result('raw'),result('projected_null');b['response']['content'][0]['text']+='extra'
        self.assertFalse(run_quality.final_comparison(a,b)['passed'])

    def test_mutated_identity_count_tap_or_lifecycle_fails(self):
        for key,value in [('pcm_sha256','other'),('manifest_sha256','other'),('native_binary_sha256','other'),
                          ('checked_drain_delete',False),('decode_at_budget',True),('final_tokens',94),
                          ('automatic_tool_calling',True),('tool_dispatch_count',1),
                          ('context_tokens',512),('max_output_tokens',65),('resource_profile','other')]:
            a,b=result('raw'),result('projected_null');b[key]=value
            self.assertFalse(run_quality.final_comparison(a,b)['passed'], key)
        for key,value in [('calls',0),('bitwise_equal',False),('valid_tokens',76),('bytes_compared',1)]:
            a,b=result('raw'),result('projected_null');b['audio_embedding_tap'][key]=value
            self.assertFalse(run_quality.final_comparison(a,b)['passed'], key)

    def test_missing_or_duplicated_lane_fails(self):
        self.assertFalse(run_quality.final_comparison({},result('projected_null'))['passed'])
        self.assertFalse(run_quality.final_comparison(result('raw'),result('raw'))['passed'])

    def test_transcript_normalization_only_case_punctuation(self):
        self.assertEqual(run_quality.normalize('Roses are red, violets are blue.'),'roses are red violets are blue')
        self.assertNotEqual(run_quality.normalize('Roses are NOT red violets are blue'),run_quality.normalize(run_quality.REFERENCE['text']))
        for suffix in (' 42', ' 🐻'):
            self.assertNotEqual(run_quality.normalize(run_quality.REFERENCE['text']+suffix),run_quality.normalize(run_quality.REFERENCE['text']))


class EvidenceTests(unittest.TestCase):
    def test_public_diagnostic_failure_survives_without_private_logs(self):
        sys.path.insert(0,str(common.HERE/'encoder-replay'))
        from diagnostic_status import Status
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);b=root/'build';r=root/'run';b.mkdir();r.mkdir()
            context={'repository':'battlesbudz/Faraday','run_id':123,'run_attempt':1,
                'head_sha':'a'*40,'source_commit':'b'*40}
            status=Status(b,context);status.phase('source_graph')
            status.failed(FileNotFoundError('/private/never-export-this'))
            (b/'diagnostic-supervisor').mkdir()
            (b/'diagnostic-supervisor/compile.log').write_text('NEVER EXPORT PRIVATE LOG')
            write(b/'diagnostic-supervisor/report.private.json',{'private':'NEVER EXPORT'})
            export_evidence.export(b,r,root/'evidence')
            names={str(p.relative_to(root/'evidence')) for p in (root/'evidence').rglob('*') if p.is_file()}
            self.assertEqual(names,{'build/diagnostic-status.json','EVIDENCE-INDEX.json'})
            public=(root/'evidence/build/diagnostic-status.json').read_text()
            self.assertNotIn('never-export',public);self.assertNotIn('NEVER EXPORT',public)
            value=json.loads(public);self.assertEqual(value['phase'],'source_graph')
            self.assertEqual(value['error']['class'],'missing_file')
            self.assertFalse(value['cleanup']['verified'])

    def test_diagnostic_extra_fields_and_oversize_fail_before_export(self):
        sys.path.insert(0,str(common.HERE/'encoder-replay'))
        from diagnostic_status import Status
        for mode in ('extra','oversize'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);b=root/'build';r=root/'run';b.mkdir();r.mkdir()
                status=Status(b,None)
                value=json.loads(status.path.read_text())
                value['unrelated_private_data']='DO NOT EXPORT' if mode=='extra' else 'x'*9000
                write(status.path,value)
                with self.assertRaises(GateError):export_evidence.export(b,r,root/'evidence')
                self.assertFalse((root/'evidence/build/diagnostic-status.json').exists())
                index=(root/'evidence/EVIDENCE-INDEX.json').read_text()
                self.assertNotIn('DO NOT EXPORT',index)
                self.assertEqual(json.loads(index)['summary']['classification'],'evidence_export_failure')

    def test_export_failure_does_not_publish_filesystem_exception_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);b=root/'build';r=root/'run';b.mkdir();r.mkdir()
            private = '/home/runner/private-token-and-user-file.json'
            with patch.object(export_evidence,'select',side_effect=PermissionError(private)):
                with self.assertRaises(GateError) as caught:
                    export_evidence.export(b,r,root/'out')
            self.assertNotIn(private,str(caught.exception))
            text=(root/'out/EVIDENCE-INDEX.json').read_text()
            self.assertNotIn(private,text)
            self.assertEqual(json.loads(text)['error_class'],'filesystem')
            self.assertEqual(json.loads(text)['stage'],'structured_receipt_export')

    def test_only_receipt_allowlist_exported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);b=root/'build';r=root/'run';b.mkdir();r.mkdir()
            write(b/'build-status.json',{'build_succeeded':False})
            write(r/'summary.json',{'passed':False,'classification':'resource_constrained'})
            (r/'inputs').mkdir();(r/'inputs/full.litertlm').write_bytes(b'NEVER UPLOAD')
            write(r/'raw-request.json',{'blob':'secret activations'})
            (r/'stdout.log').write_text('NEVER UPLOAD')
            export_evidence.export(b,r,root/'evidence')
            names={str(p.relative_to(root/'evidence')) for p in (root/'evidence').rglob('*') if p.is_file()}
            self.assertEqual(names,{'build/build-status.json','run/summary.json','EVIDENCE-INDEX.json'})

    def test_missing_run_is_not_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);export_evidence.export(root/'missing',root/'missing-run',root/'out')
            value=common.load(root/'out/EVIDENCE-INDEX.json')
            self.assertFalse(value['summary']['passed']);self.assertEqual(value['summary']['classification'],'not_run')

    def test_forbidden_data_in_receipt_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);r=root/'run';r.mkdir();write(r/'summary.json',{'blob':'forbidden'})
            with self.assertRaises(GateError): export_evidence.export(root/'b',r,root/'out')
            self.assertEqual(common.load(root/'out/EVIDENCE-INDEX.json')['summary']['classification'],'evidence_export_failure')
            self.assertFalse((root/'out/run/summary.json').exists())

    def test_symlink_receipt_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);r=root/'run';r.mkdir();write(root/'outside.json',{'passed':True})
            (r/'summary.json').symlink_to(root/'outside.json')
            with self.assertRaises(GateError): export_evidence.select(root/'b',r)

    def test_workflow_upload_target_is_evidence_only(self):
        text=(common.HERE/'hosted-steps.yml.example').read_text()
        self.assertIn('path: ${{ runner.temp }}/streaming-quality-evidence/',text)
        self.assertNotIn('continue-on-error: true',text)
        self.assertLess(text.index('build_probes.py'),text.index('run_quality.py'))
        self.assertIn('if: always()',text)


class OrchestrationTests(unittest.TestCase):
    def exercise(self, failure_mode=None, failure_kind='resource_constrained'):
        from test_diagnostic_pair import ContinuationTests
        out,calls,_,summary=ContinuationTests.exercise(self,legacy=False,
            lane_error=failure_kind if failure_mode else None)
        if not failure_mode:
            self.assertTrue(summary['passed'],summary)
            self.assertEqual(calls,['reassembly','frontend','encoder-stateful','encoder-static','encoder-adapter','encoder-eoa','projected_null','raw'])
            a=common.load(out/'projected_null-request.json'); b=common.load(out/'raw-request.json')
            self.assertEqual(a['pcm_sha256'],b['pcm_sha256']); self.assertEqual(a['manifest_sha256'],b['manifest_sha256'])
            self.assertIn('projected_audio',a['message']['content'][1]); self.assertNotIn('projected_audio',b['message']['content'][1])
            self.assertFalse(summary['android_full_model_proven']); self.assertFalse(summary['jni_full_model_proven'])
        else:
            self.assertFalse(summary['passed']); self.assertEqual(summary['classification'],failure_kind)
            self.assertNotIn('raw',calls)
    def test_order_and_same_complete_pcm(self): self.exercise()
    def test_resource_failure_stops_without_retry(self): self.exercise('projected_null')
    def test_uncertain_cleanup_stops_before_raw(self): self.exercise('projected_null','model_cleanup_failure')


if __name__=='__main__':unittest.main()
