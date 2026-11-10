import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import verify_packaged_samplers
import verify_packaged_encoder_assets
from bind_release_receipt import quality_receipt, QUALITY_FILES, bind, main, ROOT
from package_android_aar import sha256
from producer_identity import workflow_identity
sys.path.insert(0,str(ROOT/'scripts/streaming-quality'))
import common as quality_common
import run_quality
from test_quality import result as native_result


class QualityEvidenceBinding(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)/'jarvis-streaming-quality-evidence-123-1';self.root.mkdir()
        self.identity={'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_REPOSITORY':'battlesbudz/Faraday'}
        self.quality_identity=dict(self.identity)
        self.patch='b'*64
        snapshot={'files':{'source.cc':'c'*64}}
        digest=hashlib.sha256(json.dumps(snapshot,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        summary={'passed':True,'classification':'passed','stage':'finished','reviewed_patch_sha256':self.patch,
            'source_snapshot_sha256':digest,'android_full_model_proven':False,'jni_full_model_proven':False}
        self.data={name:{} for name in QUALITY_FILES}
        self.data.update({'build/source-snapshot.json':snapshot,'run/summary.json':summary,
            'build/diagnostic-status.json':{'schema_version':1,'producer':'encoder_replay_diagnostic',
                'invocation_id':'1'*32,'context':{'repository':'battlesbudz/Faraday','run_id':123,'run_attempt':1,
                    'head_sha':'f'*40,'source_commit':'a'*40},'state':'complete','phase':'packaging',
                'outcome':'passed','error':None,'child':{'started':True,'exit_code':0},
                'cleanup':{'verified':True,'surviving_process_count':0},
                'resources':{'classification':'build_passed','stop_reason':None,'wall_milliseconds':1000,
                    'peak_tree_rss_bytes':1024},'quality_acceptance_unchanged':True},
            'build/build-status.json':{'build_succeeded':True,'compilation_exited_before_quality':True,
                'reviewed_patch_sha256':self.patch,'source_snapshot_sha256':digest},
            'run/comparison.json':{'passed':True,'native_pair_passed':True,'documentation_example_match':True}})
        contract=quality_common.ENCODER_ACCEPTANCE_CONTRACT
        summary['acceptance_contract']=contract
        build=self.data['build/build-status.json']
        build['binaries']={name:{'bytes':1,'sha256':'d'*64} for name in run_quality.TARGETS}
        model_pins=json.loads((ROOT/'scripts/streaming-quality/model-structure.json').read_text())
        projected={'bytes':473088,'sha256':'e'*64}
        inputs={'acceptance_contract':contract,'bundle':quality_common.BUNDLE,'public_wav':quality_common.WAV,
            'models':{name:{k:pin[k] for k in ('bytes','sha256')} for name,pin in model_pins.items()},
            'source_snapshot_sha256':digest,'projected':projected,'prerequisite_binding_sha256':'f'*64,
            'pcm':{'bytes':196884,'sha256':quality_common.PCM_SHA},
            'mel':{'bytes':157184,'sha256':quality_common.MEL_SHA},
            'wav':{'bytes':196942,'sha256':'6438b41f257e31bfdd94148bd9d805a7845ed27a5dfd57d774f1c0ff3ef3cb7b'}}
        self.data['run/input-identity.json']=inputs
        self.data['run/frontend.json']={'passed':True,'decode_pcm_bitwise':True,
            'encoded_and_pcm_mel_bitwise':True,'mel_fixture_bitwise':True,
            'binary':build['binaries']['native_frontend_quality_probe'],**{k:inputs[k] for k in ('pcm','mel','wav')}}
        self.data['run/encoder-oracle.json']={'passed':True,'acceptance_contract':contract,
            'post_adapter_valid_rows_bitwise':True,'eoa_bitwise':True,'complete_pinned_native_receipts':True,
            'stateful_counts_and_masks_match':True,'static_prefix_mask_match':True,
            'cache_state_all_layers_checked':False,'state_output_count':98,'pcm_samples':49221,
            'mel_frames':307,'audio_rows':77,'embedding_width':1536,'projected_rows':projected,
            'historical_projected_rows':{'bytes':473088,'sha256':quality_common.ROWS_SHA},
            'historical_reference_role':'fingerprint_diagnostic_only','complete_reference_hash_match':False,
            'state_output_checks':{'all_emitted_outputs_valid':True,'state_transition_invariants_valid':True,
                'emitted_output_count':131,'cache_state_reference_equivalence_proven':False},
            'binary':build['binaries']['pinned_encoder_probe']}
        for name in ('reassembly','frontend','encoder-stateful','encoder-static','encoder-adapter','encoder-eoa','raw','projected_null'):
            self.data[f'run/{name}/process.json']={'classification':'passed','status':'completed','exit_code':0,
                'execution_started':True,'cleanup_verified':True}
        self.write(bind=True)

    def write(self,bind=False):
        if bind:
            def pin(value):
                data=json.dumps(value).encode()
                return hashlib.sha256(data).hexdigest()
            inputs=self.data['run/input-identity.json']
            for name,field in (('frontend','frontend_receipt_sha256'),('encoder-oracle','oracle_receipt_sha256')):
                inputs[field]=pin(self.data[f'run/{name}.json'])
            lanes={}
            for lane in ('raw','projected_null'):
                value=native_result(lane)
                value.update(run_quality.lane_identity(lane,self.data['build/build-status.json'],inputs))
                self.data[f'run/{lane}/result.json']=value
                lanes[lane]={'classification':'passed','result_sha256':pin(value),
                    'process_sha256':pin(self.data[f'run/{lane}/process.json'])}
            self.data['run/summary.json']['lanes']=lanes
            self.data['run/comparison.json']=run_quality.final_comparison(
                self.data['run/raw/result.json'],self.data['run/projected_null/result.json'])
        files={}
        for name,value in self.data.items():
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value))
            files[name]={'bytes':p.stat().st_size,'sha256':sha256(p)}
        self.index={'summary':self.data['run/summary.json'],'files':files,'audio_weights_activations_uploaded':False,
            'ci':{k:self.quality_identity[k] for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_SHA')}}
        (self.root/'EVIDENCE-INDEX.json').write_text(json.dumps(self.index))

    def test_exact_successful_receipt_passes(self):
        self.assertTrue(quality_receipt(self.root,self.identity,self.patch)['passed'])

    def test_old_missing_or_failed_contract_cannot_be_promoted(self):
        original=copy.deepcopy(self.data)
        for name in ('run/summary.json','run/input-identity.json','run/encoder-oracle.json','run/comparison.json'):
            for contract in (None,'historical_fixed_hash_v0'):
                self.data=copy.deepcopy(original)
                self.data[name]['acceptance_contract']=contract; self.write()
                with self.subTest(name=name),self.assertRaisesRegex(ValueError,'contract'):
                    quality_receipt(self.root,self.identity,self.patch)
        self.data=original

    def test_oracle_invariant_and_fingerprint_fields_are_required(self):
        original=copy.deepcopy(self.data)
        changes=[('passed',False),('post_adapter_valid_rows_bitwise',False),('eoa_bitwise',False),
            ('complete_pinned_native_receipts',False),('stateful_counts_and_masks_match',False),
            ('static_prefix_mask_match',False),('state_output_count',97),('audio_rows',76),
            ('cache_state_all_layers_checked',True),('complete_reference_hash_match',True),
            ('historical_reference_role','release_oracle'),('state_output_checks',{})]
        for field,value in changes:
            self.data=copy.deepcopy(original); self.data['run/encoder-oracle.json'][field]=value
            self.write(bind=True)
            with self.subTest(field=field),self.assertRaises(ValueError):
                quality_receipt(self.root,self.identity,self.patch)
        self.data=original

    def test_cross_bound_input_runtime_and_state_provenance_is_required(self):
        original=copy.deepcopy(self.data)
        changes=[('run/input-identity.json','models',{}),('run/input-identity.json','projected',{}),
            ('run/input-identity.json','pcm',{}),('run/input-identity.json','prerequisite_binding_sha256',''),
            ('run/input-identity.json','oracle_receipt_sha256','0'*64),
            ('run/input-identity.json','frontend_receipt_sha256','0'*64),
            ('run/frontend.json','binary',{}),('run/encoder-oracle.json','binary',{}),
            ('run/encoder-static/process.json','cleanup_verified',False),
            ('run/encoder-adapter/process.json','execution_started',False),
            ('run/projected_null/process.json','cleanup_verified',False)]
        for name,field,value in changes:
            self.data=copy.deepcopy(original); self.data[name][field]=value; self.write()
            with self.subTest(name=name,field=field),self.assertRaises(ValueError):
                quality_receipt(self.root,self.identity,self.patch)
        self.data=original

    def test_new_bool_and_integer_facts_reject_json_numeric_aliases(self):
        original=copy.deepcopy(self.data)
        mutations=[('state_output_checks','all_emitted_outputs_valid',1),
            ('state_output_checks','state_transition_invariants_valid',1),
            ('state_output_checks','cache_state_reference_equivalence_proven',0),
            ('state_output_checks','emitted_output_count',131.0),
            ('projected_rows','bytes',473088.0),('historical_projected_rows','bytes',473088.0)]
        mutations += [(None,key,float(value)) for key,value in {'state_output_count':98,
            'pcm_samples':49221,'mel_frames':307,'audio_rows':77,'embedding_width':1536}.items()]
        for parent,field,value in mutations:
            self.data=copy.deepcopy(original); oracle=self.data['run/encoder-oracle.json']
            (oracle[parent] if parent else oracle)[field]=value
            self.write(bind=True)
            with self.subTest(parent=parent,field=field),self.assertRaises(ValueError):
                quality_receipt(self.root,self.identity,self.patch)
        for name in ('pcm','mel','wav'):
            self.data=copy.deepcopy(original)
            self.data['run/input-identity.json'][name]['bytes']=float(self.data['run/input-identity.json'][name]['bytes'])
            self.write(bind=True)
            with self.subTest(input=name),self.assertRaisesRegex(ValueError,'Pinned complete input'):
                quality_receipt(self.root,self.identity,self.patch)
        self.data=original

    def test_green_comparison_cannot_hide_changed_lane_or_wrong_transcript(self):
        original=copy.deepcopy(self.data)
        for field,value in [('projected_tokens_sha256',quality_common.ROWS_SHA),('decode_tokens',0),
                            ('checked_drain_delete',False),('text','Thank you')]:
            self.data=copy.deepcopy(original)
            for lane in ('raw','projected_null'):
                receipt=self.data[f'run/{lane}/result.json']; receipt[field]=value
                if field=='text': receipt['response']['content'][0]['text']=value
                self.data['run/summary.json']['lanes'][lane]['result_sha256']=hashlib.sha256(json.dumps(receipt).encode()).hexdigest()
            self.write()
            with self.subTest(field=field),self.assertRaises(ValueError):
                quality_receipt(self.root,self.identity,self.patch)
        self.data=original

    def test_optional_capture_failure_with_checked_cleanup_preserves_quality_gate(self):
        status=self.data['build/diagnostic-status.json'];status['outcome']='failed'
        status['error']={'class':'missing_file','code':'missing_file',
            'detail':'A required file in the current phase was absent.','location':None}
        status['child']['exit_code']=2;status['resources']['classification']='build_compile_failure';self.write()
        self.assertTrue(quality_receipt(self.root,self.identity,self.patch)['passed'])

    def test_wrong_producer_or_uncertain_diagnostic_cleanup_rejected(self):
        status=self.data['build/diagnostic-status.json'];status['context']['run_attempt']=99;self.write()
        with self.assertRaisesRegex(ValueError,'exact quality producer'):
            quality_receipt(self.root,self.identity,self.patch)
        status['context']['run_attempt']=1;status['state']='uncertain';status['cleanup']['verified']=False;self.write()
        with self.assertRaisesRegex(ValueError,'exact quality producer'):
            quality_receipt(self.root,self.identity,self.patch)

    def test_missing_receipt_fails(self):
        del self.data['run/frontend.json'];self.write()
        with self.assertRaisesRegex(ValueError,'Missing'):quality_receipt(self.root,self.identity,self.patch)

    def test_changed_receipt_bytes_fail(self):
        (self.root/'run/frontend.json').write_text('{"tampered":true}')
        with self.assertRaisesRegex(ValueError,'bytes changed'):quality_receipt(self.root,self.identity,self.patch)

    def test_resource_failure_never_counts_as_pass(self):
        for mode in ['resource_constrained','not_run','model_failure']:
            self.data['run/summary.json']['classification']=mode;self.write()
            with self.subTest(mode=mode),self.assertRaisesRegex(ValueError,'did not explicitly pass'):
                quality_receipt(self.root,self.identity,self.patch)

    def test_successful_diagnostic_pair_never_rescues_failed_strict_oracle(self):
        self.data['run/summary.json'].update(passed=False,classification='numerical_failure',stage='native_encoder_oracle')
        self.data['run/encoder-oracle.json']={'passed':False,'complete_reference_hash_match':False}
        self.write()
        with self.assertRaisesRegex(ValueError,'did not explicitly pass'):
            quality_receipt(self.root,self.identity,self.patch)

    def test_diagnostic_export_cannot_be_bound_as_strict_quality(self):
        self.data['run/diagnostic-pair.json']={'strict_gate_passed':False,'native_pair_passed':True}
        self.write()
        with self.assertRaisesRegex(ValueError,'Missing or unexpected'):
            quality_receipt(self.root,self.identity,self.patch)

    def test_failed_native_lane_or_semantic_reference_rejected(self):
        self.data['run/raw/process.json']['exit_code']=1;self.write()
        with self.assertRaisesRegex(ValueError,'inference did not complete'):quality_receipt(self.root,self.identity,self.patch)
        self.data['run/raw/process.json']['exit_code']=0;self.data['run/comparison.json']['documentation_example_match']=False;self.write()
        with self.assertRaisesRegex(ValueError,'semantic reference'):quality_receipt(self.root,self.identity,self.patch)

    def test_foreign_run_or_unsupported_android_claim_rejected(self):
        self.identity['GITHUB_RUN_ID']='456'
        with self.assertRaisesRegex(ValueError,'run/attempt'):quality_receipt(self.root,self.identity,self.patch)
        self.identity['GITHUB_RUN_ID']='123';self.data['run/summary.json']['android_full_model_proven']=True;self.write()
        with self.assertRaisesRegex(ValueError,'did not explicitly pass'):quality_receipt(self.root,self.identity,self.patch)

    def test_snapshot_drift_rejected(self):
        self.data['build/source-snapshot.json']['extra']='changed';self.write()
        with self.assertRaisesRegex(ValueError,'snapshot changed'):quality_receipt(self.root,self.identity,self.patch)


    def test_both_apks_must_embed_exact_producer_provenance(self):
        self.check_both_apks_must_embed_exact_producer_provenance(nested=False)

    def test_exact_ci_consumer_zip_binds_both_apks_and_retains_evidence(self):
        self.check_both_apks_must_embed_exact_producer_provenance(nested=True)

    def check_both_apks_must_embed_exact_producer_provenance(self, nested):
        payloads = {name: ('test-' + name).encode() for name in verify_packaged_samplers.OUTPUTS}
        pins = {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}
        override = patch.object(verify_packaged_samplers, 'OUTPUTS', pins)
        override.start(); self.addCleanup(override.stop)
        newer=self.root.with_name('jarvis-streaming-quality-evidence-123-2');self.root.rename(newer);self.root=newer
        self.quality_identity['GITHUB_RUN_ATTEMPT']='2'
        self.data['build/diagnostic-status.json']['context']['run_attempt']=2
        inputs=self.root/'inputs';out=self.root/'out';out.mkdir()
        consumer=inputs/'jarvis-streaming-sdk-consumer';consumer.mkdir(parents=True)
        manifest=ROOT/'third_party/litert-lm-0.18.0/reviewed-source.json'
        reviewed=json.loads(manifest.read_text());self.patch=reviewed['patch_sha256']
        self.data['run/summary.json']['reviewed_patch_sha256']=self.patch
        self.data['build/build-status.json']['reviewed_patch_sha256']=self.patch
        self.write()
        p={'aar_sha256':'d'*64,'source':{'workflow_identity':self.identity,
            'reviewed_patch_sha256':self.patch,'reviewed_source_sha256':sha256(manifest)}}
        notice = (ROOT/'scripts/streaming-sdk/SAMPLER-DEPENDENCY-NOTICE.md').read_bytes()
        p.update(native_sha256=pins, sampler_dependency_derivations={
            name: {'output_sha256': digest} for name, digest in pins.items()},
            sampler_modifications_notice_sha256=hashlib.sha256(notice).hexdigest())
        prefix = 'streaming-sdk-input/' if nested else ''
        provenance_name = prefix + 'litertlm-android-0.18.0-sealed-audio-arm64.provenance.json'
        # Match upload-artifact's common-root archive, not a flattened mock.
        archive = self.root/'consumer.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('streaming-sdk-manifest.json', '{}')
            z.writestr(provenance_name, json.dumps(p))
            z.writestr(prefix + 'source-receipt.json', json.dumps(p['source']))
        with zipfile.ZipFile(archive) as z:
            z.extractall(consumer)
        provenance = consumer/provenance_name
        source_receipt = consumer/prefix/'source-receipt.json'
        self.data['build/build-status.json']['android_source_receipt_sha256']=sha256(source_receipt)
        self.write()
        apks=inputs/'jarvis-os-v2-release-apk';apks.mkdir()
        for name in ('app-release.apk','app-compact.apk'):
            with zipfile.ZipFile(apks/name,'w') as z:
                z.writestr('assets/litert-lm-source-provenance.json',json.dumps({k:v for k,v in p.items() if k!='aar_sha256'}))
                z.writestr('assets/litert-lm-sampler-modifications.md',notice)
                for library, payload in payloads.items(): z.writestr('lib/arm64-v8a/'+library,payload)
                for asset in verify_packaged_encoder_assets.source_contract()['assets']:
                    z.writestr(asset, (ROOT/'app/src/main'/asset).read_bytes())
        consumer=dict(self.identity,GITHUB_RUN_ATTEMPT='3')
        encoder_report = verify_packaged_encoder_assets.report(
            [apks/name for name in verify_packaged_encoder_assets.APK_NAMES], self.identity)
        encoder_report_path = apks/verify_packaged_encoder_assets.REPORT_NAME
        encoder_report_path.write_text(json.dumps(encoder_report))
        original_provenance = provenance.read_bytes()
        for malformed in ([], {'source': []}):
            provenance.write_text(json.dumps(malformed))
            with self.assertRaisesRegex(ValueError, 'must be objects'):
                bind(inputs,out,self.root,'d'*64,sha256(provenance),workflow_identity(consumer,'1'),
                     workflow_identity(consumer,'2'),sha256(source_receipt),consumer)
        provenance.write_bytes(original_provenance)
        # The binder must delegate before opening any APK payload itself, so the
        # size limits in the shared verifier cannot be preceded by an unbounded read.
        with patch('bind_release_receipt.verify_packaged_samplers',
                   side_effect=ValueError('bounded_verifier_first')) as verifier, \
                patch('bind_release_receipt.zipfile.ZipFile',
                      side_effect=AssertionError('unbounded APK read before verifier')):
            with self.assertRaisesRegex(ValueError, 'bounded_verifier_first'):
                bind(inputs,out,self.root,'d'*64,sha256(provenance),workflow_identity(consumer,'1'),
                     workflow_identity(consumer,'2'),sha256(source_receipt),consumer)
            verifier.assert_called_once()
        # A successful sampler gate cannot conceal a missing pre-upload encoder
        # report; the final receipt gate must require and recompute both assets.
        encoder_report_path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing, unsafe or oversized'):
            bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity,
                 self.quality_identity,sha256(source_receipt),consumer)
        encoder_report_path.write_text(json.dumps(encoder_report))
        with patch('bind_release_receipt.bind_encoder_assets',
                   side_effect=ValueError('encoder_gate_required')) as verifier:
            with self.assertRaisesRegex(ValueError, 'encoder_gate_required'):
                bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity,
                     self.quality_identity,sha256(source_receipt),consumer)
            verifier.assert_called_once_with(apks, consumer)
        compact = apks/'app-compact.apk'
        compact_bytes = compact.read_bytes()
        with zipfile.ZipFile(compact, 'a') as z:
            z.writestr('assets/gemma_streaming/structural-literals.bin', b'transformed')
        with self.assertRaisesRegex(ValueError, 'differ from final APKs/reviewed source'):
            bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity,
                 self.quality_identity,sha256(source_receipt),consumer)
        compact.write_bytes(compact_bytes)
        result=bind(inputs,out,self.root,'d'*64,sha256(provenance),workflow_identity(consumer,'1'),
            workflow_identity(consumer,'2'),sha256(source_receipt),consumer)
        self.assertTrue(result['passed'])
        self.assertEqual('1',result['producer_attempt'])
        self.assertEqual('jarvis-streaming-quality-evidence-123-2',result['quality_artifact_name'])
        self.assertEqual('2',result['quality_producer_attempt'])
        self.assertEqual(2,len(result['packaged_sampler_identity']))
        self.assertEqual(2,len(result['packaged_encoder_assets']['apks']))
        self.assertEqual('1',result['packaged_encoder_assets']['workflow_identity']['GITHUB_RUN_ATTEMPT'])
        for row in result['packaged_encoder_assets']['apks']:
            self.assertEqual(sha256(apks/row['apk']),row['apk_sha256'])
        self.assertTrue((out/'streaming-quality/EVIDENCE-INDEX.json').is_file())
        self.assertEqual(provenance.read_bytes(), (out/'streaming-sdk/sdk.provenance.json').read_bytes())
        self.assertEqual(source_receipt.read_bytes(), (out/'streaming-sdk/source-receipt.json').read_bytes())
        self.assertEqual(b'{}', (out/'streaming-sdk/artifact-selection.json').read_bytes())
        # A successful retry may not report quality from a different SDK producer.
        self.data['build/build-status.json']['android_source_receipt_sha256']='0'*64;self.write()
        with self.assertRaisesRegex(ValueError,'different SDK source receipt'):
            bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity,self.quality_identity,sha256(source_receipt),consumer)
        self.data['build/build-status.json']['android_source_receipt_sha256']=sha256(source_receipt);self.write()
        with zipfile.ZipFile(apks/'app-compact.apk','w') as z:
            z.writestr('assets/litert-lm-source-provenance.json','{}')
        with self.assertRaisesRegex(ValueError,'APK does not embed'):
            bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity,self.quality_identity,sha256(source_receipt),consumer)

    def test_gradle_and_workflow_remain_fail_closed(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertNotIn('implementation("com.google.ai.edge.litertlm:litertlm-android',gradle)
        self.assertIn('throw GradleException',gradle)
        self.assertIn('ndkVersion = "27.2.12479018"',gradle)
        self.assertIn('packaging.jniLibs.keepDebugSymbols.addAll(setOf(',gradle)
        for name in verify_packaged_samplers.OUTPUTS:
            self.assertIn('"**/'+name+'"',gradle)
        for dependency in ['gson:2.13.2','kotlin-reflect:2.3.21','kotlinx-coroutines-android:1.9.0']:
            self.assertIn(dependency,gradle)
        workflow=(ROOT/'.github/workflows/android.yml').read_text()
        producer=workflow.split('  build-streaming-sdk:',1)[1].split('  verify-streaming-quality:',1)[0]
        self.assertNotIn('continue-on-error',producer)
        self.assertIn('< /dev/null',producer)
        self.assertIn('timeout-minutes: 45',producer)
        self.assertIn('streaming-sdk/source-receipt.json',producer)
        self.assertNotIn('run_quality.py',producer)
        quality=workflow.split('  verify-streaming-quality:',1)[1].split('  build-release:',1)[0]
        self.assertIn('needs: build-streaming-sdk',quality)
        self.assertIn('timeout-minutes: 115',quality)
        self.assertIn('timeout-minutes: 63',quality)
        optional_diagnostics = {
            'Capture weight-free encoder runtime diagnostic',
            'Retain verified weight-free encoder runtime diagnostic',
        }
        optional_seen = set()
        for block in quality.split('\n      - '):
            first = block.splitlines()[0] if block.splitlines() else ''
            name = first.removeprefix('name: ')
            if name in optional_diagnostics:
                optional_seen.add(name)
                self.assertEqual(1, block.count('continue-on-error: true'))
            else:
                self.assertNotIn('continue-on-error', block)
        self.assertEqual(optional_diagnostics, optional_seen)
        release=workflow.split('  build-release:',1)[1].split('  prepare-upgrade-baseline:',1)[0]
        self.assertEqual(2,release.count('--require-digests --check-workflow --producer-attempt'))
        self.assertEqual(2,release.count('-PlitertLmBridgeSha256="$STREAMING_AAR_SHA256"'))
        self.assertIn('PRODUCER_ARTIFACT_ID',release)
        self.assertIn('scripts/streaming-sdk/producer_identity.py',release)
        self.assertIn('needs: build-streaming-sdk',release)
        self.assertNotIn('needs: [build-streaming-sdk, verify-streaming-quality]',release)
        self.assertIn('artifact/UNVERIFIED-CANDIDATE.txt',release)
        self.assertIn('Authoritative run:',release)
        self.assertLess(release.index('- name: Verify final sampler bytes and notices'),
                        release.index('- name: Upload unverified APK build artifacts'))
        self.assertLess(release.index('- name: Verify final encoder assets and notices'),
                        release.index('- name: Upload unverified APK build artifacts'))
        encoder_step = release.split('- name: Verify final encoder assets and notices',1)[1].split('\n      - ',1)[0]
        self.assertIn('verify_packaged_encoder_assets.py',encoder_step)
        self.assertIn('--apk artifact/app-release.apk --apk artifact/app-compact.apk',encoder_step)
        self.assertIn('--check-workflow',encoder_step)
        self.assertNotIn('continue-on-error',encoder_step)
        self.assertNotIn('if:',encoder_step)
        upload = release.split('- name: Upload unverified APK build artifacts',1)[1].split('\n      - ',1)[0]
        self.assertIn('artifact/packaged-encoder-assets.json',upload)
        self.assertIn('--apk artifact/app-release.apk --apk artifact/app-compact.apk',release)
        for section in ('publish:', 'publish-memory-test:', 'publish-pr-test:'):
            publishing=workflow.split('  '+section,1)[1].split('    steps:',1)[0]
            self.assertIn('verify-streaming-quality',publishing)
            self.assertIn('verification-receipt',publishing)
        self.assertEqual(3,workflow.count('files: dist/*.apk'))


class ConsumerEvidenceLayout(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inputs = self.root/'inputs'
        self.consumer = self.inputs/'jarvis-streaming-sdk-consumer'
        self.bundle = self.consumer/'streaming-sdk-input'
        self.bundle.mkdir(parents=True)
        self.identity = {'GITHUB_RUN_ID':'123', 'GITHUB_RUN_ATTEMPT':'1',
                         'GITHUB_SHA':'a'*40, 'GITHUB_REPOSITORY':'battlesbudz/Faraday'}
        manifest = ROOT/'third_party/litert-lm-0.18.0/reviewed-source.json'
        self.source = {'workflow_identity':self.identity.copy(),
                       'reviewed_patch_sha256':json.loads(manifest.read_text())['patch_sha256'],
                       'reviewed_source_sha256':sha256(manifest)}
        self.provenance = self.bundle/'litertlm-android-0.18.0-sealed-audio-arm64.provenance.json'
        self.receipt = self.bundle/'source-receipt.json'
        self.manifest = self.consumer/'streaming-sdk-manifest.json'
        self.manifest.write_text('{}')
        self.write_source()

    def write_source(self):
        self.receipt.write_text(json.dumps(self.source))
        self.provenance.write_text(json.dumps({'aar_sha256':'d'*64, 'source':self.source}))
        self.source_sha = sha256(self.receipt)
        self.provenance_sha = sha256(self.provenance)

    def bind(self, **kwargs):
        options = dict(inputs=self.inputs, out=self.root/'out', quality=self.root/'quality',
                       expected_aar='d'*64, expected_provenance=self.provenance_sha,
                       identity=self.identity, quality_identity=self.identity,
                       expected_source_receipt=self.source_sha, current_identity=self.identity)
        options.update(kwargs)
        return bind(**options)

    def reject(self, pattern, **kwargs):
        with patch('bind_release_receipt.verify_packaged_samplers') as verifier:
            with self.assertRaisesRegex(ValueError, pattern):
                self.bind(**kwargs)
            verifier.assert_not_called()

    def test_missing_manifest_receipt_or_provenance_rejected(self):
        for path in (self.manifest, self.receipt, self.provenance):
            with self.subTest(name=path.name):
                data = path.read_bytes(); path.unlink()
                self.reject('Missing')
                path.write_bytes(data)

    def test_duplicate_provenance_rejected_even_with_identical_bytes(self):
        shutil.copyfile(self.provenance, self.bundle/'extra.provenance.json')
        self.reject('ambiguous')

    def test_root_receipt_shadow_rejected_even_with_identical_bytes(self):
        shutil.copyfile(self.receipt, self.consumer/self.receipt.name)
        self.reject('Ambiguous')

    def test_root_provenance_shadow_rejected_even_with_identical_bytes(self):
        shutil.copyfile(self.provenance, self.consumer/self.provenance.name)
        self.reject('Ambiguous')

    def test_complete_duplicate_layout_rejected(self):
        for path in (self.receipt, self.provenance):
            shutil.copyfile(path, self.consumer/path.name)
        self.reject('Ambiguous')

    def test_receipt_and_provenance_must_be_adjacent(self):
        for path in (self.receipt, self.provenance):
            with self.subTest(name=path.name):
                other = self.consumer/path.name; path.rename(other)
                self.reject('Ambiguous')
                other.rename(path)

    def test_unrelated_nested_bundle_is_never_a_fallback(self):
        self.bundle.rename(self.consumer/'unrelated')
        self.reject('unsupported')

    def test_extra_nested_receipt_is_rejected(self):
        extra = self.bundle/'unrelated'; extra.mkdir()
        shutil.copyfile(self.receipt, extra/self.receipt.name)
        self.reject('unsupported')

    def test_deep_provenance_is_never_a_fallback(self):
        extra = self.bundle/'unrelated'; extra.mkdir()
        self.provenance.rename(extra/self.provenance.name)
        self.reject('unsupported')

    def test_file_symlinks_rejected(self):
        for path in (self.provenance, self.receipt, self.manifest):
            with self.subTest(name=path.name):
                target = self.root/path.name; path.rename(target)
                path.symlink_to(target)
                self.reject('unsafe')
                path.unlink(); target.rename(path)

    def test_internal_file_symlink_rejected(self):
        self.receipt.unlink(); self.receipt.symlink_to(self.provenance.name)
        self.reject('unsafe')

    def test_directory_symlinks_rejected(self):
        for path in (self.bundle, self.consumer, self.inputs):
            with self.subTest(name=path.name):
                target = self.root/'linked-directory'; path.rename(target)
                path.symlink_to(target, target_is_directory=True)
                self.reject('unsafe')
                path.unlink(); target.rename(path)

    def test_parent_traversal_path_rejected(self):
        self.reject('unsafe', inputs=self.inputs/'..'/'inputs')

    def test_ancestor_symlink_loop_rejected(self):
        target = self.root/'saved-inputs'; self.inputs.rename(target)
        self.inputs.symlink_to(self.inputs.name, target_is_directory=True)
        self.reject('unsafe')

    def test_nonregular_evidence_rejected(self):
        for path in (self.provenance, self.receipt, self.manifest):
            with self.subTest(name=path.name):
                data = path.read_bytes(); path.unlink(); path.mkdir()
                self.reject('unsafe')
                path.rmdir(); path.write_bytes(data)

    def test_backslash_provenance_name_rejected(self):
        self.provenance.rename(self.bundle/'..\\foreign.provenance.json')
        self.reject('unsupported')

    def test_provenance_tamper_rejected(self):
        self.provenance.write_text(self.provenance.read_text() + ' ')
        self.reject('changed SDK consumer provenance')

    def test_receipt_tamper_rejected(self):
        self.receipt.write_text(self.receipt.read_text() + ' ')
        self.reject('does not match exact producer')

    def test_receipt_digest_match_does_not_replace_source_equality(self):
        self.receipt.write_text(json.dumps(dict(self.source, foreign=True)))
        self.reject('does not match exact producer', expected_source_receipt=sha256(self.receipt))

    def test_foreign_producer_identity_rejected_even_with_matching_hashes(self):
        for key, value in (('GITHUB_RUN_ID', '456'), ('GITHUB_RUN_ATTEMPT', '2'),
                           ('GITHUB_SHA', 'e'*40), ('GITHUB_REPOSITORY', 'foreign/repo')):
            with self.subTest(key=key):
                self.source['workflow_identity'] = dict(self.identity, **{key:value})
                self.write_source()
                self.reject('not the exact reviewed producer')

    def test_foreign_quality_identity_rejected(self):
        for key, value in (('GITHUB_RUN_ID', '456'), ('GITHUB_SHA', 'e'*40),
                           ('GITHUB_REPOSITORY', 'foreign/repo')):
            with self.subTest(key=key):
                self.reject('same run/source/repository', quality_identity=dict(self.identity, **{key:value}))

    def test_wrong_expected_digests_rejected(self):
        for key, pattern in (('expected_provenance', 'changed SDK consumer provenance'),
                             ('expected_source_receipt', 'does not match exact producer'),
                             ('expected_aar', 'not the exact reviewed producer')):
            with self.subTest(key=key):
                self.reject(pattern, **{key:'0'*64})

    def test_changed_reviewed_source_or_patch_rejected(self):
        for key in ('reviewed_patch_sha256', 'reviewed_source_sha256'):
            with self.subTest(key=key):
                original = self.source[key]; self.source[key] = '0'*64
                self.write_source(); self.reject('not the exact reviewed producer')
                self.source[key] = original

    def test_layout_failure_marks_existing_release_receipt_failed(self):
        self.receipt.unlink()
        out = self.root/'out'; out.mkdir()
        (out/'receipt.json').write_text(json.dumps({'passed':True, 'existing_gate':'retained'}))
        (out/'summary.md').write_text('# Jarvis verification: PASS\n')
        argv = ['bind_release_receipt.py', '--inputs', str(self.inputs), '--out', str(out),
                '--quality-dir', str(self.root/'quality'), '--producer-attempt', '1',
                '--quality-producer-attempt', '1', '--expected-source-receipt-sha256', self.source_sha,
                '--expected-aar-sha256', 'd'*64, '--expected-provenance-sha256', self.provenance_sha]
        with patch.dict(os.environ, self.identity, clear=True), patch('sys.argv', argv):
            self.assertEqual(1, main())
        receipt = json.loads((out/'receipt.json').read_text())
        self.assertIs(False, receipt['passed'])
        self.assertEqual('retained', receipt['existing_gate'])
        self.assertIn('SDK consumer evidence layout', receipt['errors'][0])
        self.assertIn('# Jarvis verification: FAIL', (out/'summary.md').read_text())


if __name__=='__main__':unittest.main()
