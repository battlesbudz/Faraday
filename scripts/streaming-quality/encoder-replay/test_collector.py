import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import capsule_writer as w
from hash_bazel_action_metadata import action_metadata
from hosted_capture import derive_query_command, verify_original_source, policy_path, collect


HERE=Path(__file__).resolve().parent


class CapsuleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.probe=self.root/'probe';shutil.copyfile('/bin/true',self.probe)
        self.license=self.root/'LICENSE';self.license.write_text('Synthetic test notice\n')
        self.policy={'origins':{},'source_archives':{},'source_notice':'Synthetic unit-test capsule; never published.\n'}
        self.files=[]
        archives={}
        for name in ('OouraFFT-v1.0-original.tar.gz','eigen-ea13a98-original.tar.gz'):
            source=self.root/name
            with tarfile.open(source,'w:gz') as t:
                data=b'Synthetic source fixture\n';member=tarfile.TarInfo('package/source.txt');member.size=len(data);t.addfile(member,io.BytesIO(data))
            archives['sources/'+name]=w.file_identity(source)['sha256']
        archive_policy=patch.object(w,'SOURCE_ARCHIVES',archives);archive_policy.start();self.addCleanup(archive_policy.stop)
        for origin in ('sdk','fft2d','eigen_archive'):
            dest='licenses/'+origin+'/LICENSE'
            self.policy['origins'][origin]={'licenses':{dest:{'identity':w.file_identity(self.license)}},
                                          'public':{'pin':'synthetic-test','upstream_url':'https://example.com/source'}}
            self.files.append({'capsule_path':dest,'source_path':str(self.license),'identity':w.file_identity(self.license),'kind':'license','origin':origin})
        self.files.append({'capsule_path':'bin/pinned_encoder_probe','source_path':str(self.probe),'identity':w.file_identity(self.probe),'kind':'probe','origin':'sdk'})
        for name,origin in [('OouraFFT-v1.0-original.tar.gz','fft2d'),('eigen-ea13a98-original.tar.gz','eigen_archive')]:
            p=self.root/name;pin=w.file_identity(p);dest='sources/'+name
            self.policy['source_archives'][dest]={'identity':pin,'origin':origin}
            self.files.append({'capsule_path':dest,'source_path':str(p),'identity':pin,'kind':'source_archive','origin':origin})
        bindings={'repository':'battlesbudz/Faraday','run_id':1,'run_attempt':1,'head_sha':'1'*40,'source_commit':'2'*40}
        for key in ('build_receipt_sha256','source_snapshot_sha256','recipe_manifest_sha256','android_source_receipt_sha256',
                    'reviewed_patch_sha256','build_command_sha256','bazel_sha256'):bindings[key]='3'*64
        bindings['policy_sha256']=w.digest(self.policy)
        proof={key:True for key in ('source_unchanged','build_completed','no_compilation_during_query','compiler_actions_complete',
                                  'dependency_closure_complete','licenses_complete','system_libraries_excluded','link_arguments_match_actions')}
        self.capture={'schema_version':1,'provenance_epoch':'same_run_post_build','complete':True,'blockers':[],
                      'bindings':bindings,'proof':proof,'files':self.files,'probe_identity':w.file_identity(self.probe),
                      'runtime_aliases':{},'system_libraries':[],'used_origins':['sdk','fft2d','eigen_archive'],
                      'compiler_actions':{'complete':True,'blockers':[],'required_output_count':1,'actions':[
                          {'mnemonic':'CppLink','arguments_sha256':'a'*64,'tool_executable':{'sha256':'b'*64},
                           'outputs':{'fixture/probe':w.file_identity(self.probe)}}]},
                      'header_input_graph':{'required_action_outputs':{'fixture/probe':w.file_identity(self.probe)}},'host_cpu':{'fixture_only':True}}

    def validate(self):return w.validate_capture(self.capture,self.policy,w.digest(self.policy))
    def archive(self,name='test.tar.gz'):return w.write_capsule(self.capture,self.policy,w.digest(self.policy),self.root/name)

    def test_current_repository_binding_is_exact_after_rename(self):
        bindings = dict(self.capture['bindings'], repository='battlesbudz/Faraday')
        w.validate_bindings(bindings)
        for repository in ('battlesbudz/Jarvis-OS-V2', 'other/Faraday',
                           'battlesbudz/Other', 'battlesbudz/Faraday-extra'):
            with self.subTest(repository=repository), self.assertRaisesRegex(ValueError, 'Unexpected repository'):
                w.validate_bindings(dict(bindings, repository=repository))

    def test_archive_is_exact_regular_allowlisted_and_deterministic(self):
        a=self.archive('one.tar.gz');b=self.archive('two.tar.gz')
        self.assertEqual(a['archive'],b['archive'])
        with tarfile.open(self.root/'one.tar.gz') as t:
            self.assertEqual(set(t.getnames()),{x['capsule_path'] for x in self.files}|{'CAPSULE-RECEIPT.json','SOURCE-NOTICE.txt'})
            self.assertTrue(all(x.isfile() and x.mode==0o644 for x in t.getmembers()))
            for item in self.files:
                self.assertEqual(hashlib.sha256(t.extractfile(item['capsule_path']).read()).hexdigest(),item['identity']['sha256'])
            public=t.extractfile('CAPSULE-RECEIPT.json').read().decode()
            self.assertNotIn(str(self.root),public)
            self.assertFalse(json.loads(public)['local_replay_performed'])

    def test_reviewed_policy_hash_cannot_change(self):
        with self.assertRaisesRegex(ValueError,'policy identity'):w.validate_capture(self.capture,self.policy,'0'*64)

    def test_current_graph_cannot_be_claimed_as_historical_build(self):
        self.capture['provenance_epoch']='local_current_graph_only'
        with self.assertRaisesRegex(ValueError,'Historical/current-query'):self.validate()

    def test_missing_action_proof_blocks_writer(self):
        self.capture['proof']['compiler_actions_complete']=False
        with self.assertRaisesRegex(ValueError,'Missing proof'):self.validate()

    def test_missing_concrete_action_metadata_blocks_writer(self):
        self.capture['compiler_actions']['actions']=[]
        with self.assertRaisesRegex(ValueError,'action-output'):self.validate()

    def test_extra_binding_cannot_leak_data(self):
        self.capture['bindings']['unreviewed_payload']='private bytes'
        with self.assertRaisesRegex(ValueError,'bindings'):self.validate()

    def test_invalid_attempt_blocks_writer(self):
        self.capture['bindings']['run_attempt']=False
        with self.assertRaisesRegex(ValueError,'run identity'):self.validate()

    def test_unknown_origin_blocks_writer(self):
        self.files[0]['origin']='unknown'
        with self.assertRaisesRegex(ValueError,'Unknown source'):self.validate()

    def test_compiled_origin_requires_notice(self):
        self.capture['files']=[x for x in self.files if x['capsule_path']!='licenses/sdk/LICENSE']
        with self.assertRaisesRegex(ValueError,'notices omitted'):self.validate()

    def test_original_source_is_required(self):
        self.capture['files']=[x for x in self.files if x['origin']!='eigen_archive']
        with self.assertRaises(ValueError):self.validate()

    def test_unreviewed_source_archive_hash_is_rejected(self):
        next(x for x in self.files if x['kind']=='source_archive')['identity']={'bytes':1,'sha256':'0'*64}
        with self.assertRaisesRegex(ValueError,'source archive'):self.validate()

    def test_model_payload_kind_is_rejected(self):
        self.files[0]['kind']='model'
        with self.assertRaisesRegex(ValueError,'Unexpected payload kind'):self.validate()

    def test_path_traversal_is_rejected(self):
        self.files[0]['capsule_path']='licenses/../private'
        with self.assertRaisesRegex(ValueError,'Unsafe capsule'):self.validate()

    def test_source_symlink_is_rejected(self):
        link=self.root/'link';link.symlink_to(self.probe)
        next(x for x in self.files if x['kind']=='probe')['source_path']=str(link)
        with self.assertRaisesRegex(ValueError,'regular physical file'):self.validate()

    def test_duplicate_alias_is_rejected(self):
        self.files.append(copy.deepcopy(self.files[0]))
        with self.assertRaisesRegex(ValueError,'Duplicate'):self.validate()

    def test_system_library_cannot_enter_payload(self):
        pin=w.file_identity(self.probe)
        self.capture['runtime_aliases']['libc.so.6']={'origin':'sdk','identity':pin}
        self.files.append({'capsule_path':'lib/libc.so.6','source_path':str(self.probe),'identity':pin,'kind':'runtime_elf','origin':'sdk'})
        with self.assertRaisesRegex(ValueError,'System or unsafe'):self.validate()

    def test_unmapped_runtime_alias_is_rejected(self):
        self.files.append({'capsule_path':'lib/librogue.so','source_path':str(self.probe),'identity':w.file_identity(self.probe),'kind':'runtime_elf','origin':'sdk'})
        with self.assertRaisesRegex(ValueError,'Unknown runtime alias'):self.validate()

    def test_missing_runtime_alias_is_rejected(self):
        self.capture['runtime_aliases']['libmissing.so']={'origin':'sdk','identity':w.file_identity(self.probe)}
        with self.assertRaisesRegex(ValueError,'Incomplete runtime'):self.validate()

    def test_budget_limit_is_enforced_before_copy(self):
        with patch.object(w,'LIMIT',1024):
            with self.assertRaises(ValueError):self.archive()
        self.assertFalse((self.root/'test.tar.gz').exists())

    def test_changed_bytes_never_promote_archive(self):
        self.probe.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'Source size changed'):self.archive()
        self.assertFalse((self.root/'test.tar.gz').exists())

    def test_existing_output_is_not_overwritten(self):
        path=self.root/'test.tar.gz';path.write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError,'Fresh capsule'):self.archive()
        self.assertEqual(path.read_bytes(),b'keep')

    def test_long_bazel_alias_round_trips_without_symlink(self):
        alias='libexternal_'+('long_component_'*9)+'.so';pin=w.file_identity(self.probe)
        self.capture['runtime_aliases'][alias]={'origin':'sdk','identity':pin}
        self.files.append({'capsule_path':'lib/'+alias,'source_path':str(self.probe),'identity':pin,'kind':'runtime_elf','origin':'sdk'})
        self.archive()
        with tarfile.open(self.root/'test.tar.gz') as t:self.assertTrue(t.getmember('lib/'+alias).isfile())


class MetadataTests(unittest.TestCase):
    def test_unlisted_tool_is_rejected_before_reading_it(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            graph={'artifacts':[{'id':1,'execPath':'output.o'}],
                   'actions':[{'mnemonic':'CppCompile','arguments':['/unknown/tool'],'outputIds':[1]}]}
            with self.assertRaisesRegex(ValueError,'precompile allowlist'):
                action_metadata(graph,root,approved_tool_paths={str(Path('/bin/true').resolve())})

    def test_static_system_runtime_linking_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'output.o').write_bytes(b'object')
            graph={'artifacts':[{'id':1,'execPath':'output.o'}],
                   'actions':[{'mnemonic':'CppLink','arguments':['/bin/true','-static-libstdc++'],'outputIds':[1]}]}
            with self.assertRaisesRegex(ValueError,'Static system-runtime'):
                action_metadata(graph,root,{'output.o':w.file_identity(root/'output.o')})

    def test_policy_symlink_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'escape').symlink_to('/bin/true')
            record={'root':'source','path':'escape','identity':w.file_identity('/bin/true')}
            with self.assertRaisesRegex(ValueError,'escaped'):
                policy_path(record,{'source':root})

    def test_modified_corresponding_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);archive=root/'source.tar.gz';source=root/'original';source.mkdir();(source/'source.txt').write_bytes(b'changed')
            with tarfile.open(archive,'w:gz') as t:
                member=tarfile.TarInfo('package/source.txt');member.size=8;t.addfile(member,io.BytesIO(b'original'))
            with self.assertRaisesRegex(ValueError,'source changed'):
                verify_original_source(archive,source)

    def test_unguarded_historical_build_never_queries_or_packages(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);build=root/'build';build.mkdir();policy={};context={}
            guard={'epoch':'before_same_run_build','context':context,'policy_sha256':w.digest(policy)}
            (root/'guard.json').write_text(json.dumps(guard))
            (build/'build-status.json').write_text(json.dumps({'build_succeeded':True,'compilation_exited_before_quality':True}))
            with patch('hosted_capture.query_metadata') as query:
                with self.assertRaisesRegex(ValueError,'precompile guard'):
                    collect(root,root,build,root/'guard.json',context,policy,w.digest(policy),root/'out',root)
                query.assert_not_called()

    def test_production_original_source_hashes_are_fixed(self):
        self.assertEqual(w.SOURCE_ARCHIVES['sources/OouraFFT-v1.0-original.tar.gz'],
                         '5f4dabc2ae21e1f537425d58a49cdca1c49ea11db0d6271e2a4b27e9697548eb')
        self.assertEqual(w.SOURCE_ARCHIVES['sources/eigen-ea13a98-original.tar.gz'],
                         '35c6126e246585d9cf6600b65471582c2701aae64b784a6fd19168a90cfc841e')

    def test_query_preserves_build_configuration_and_disables_fetch(self):
        build=['/known/bazel','--batch','--host_jvm_args=-Xmx2048m','build','-c','opt','--config=linux',
               '--repo_env=CC=/usr/bin/clang','//experimental/hosted_audio_quality_20261006:pinned_encoder_probe','//other:probe']
        query=derive_query_command(build)
        self.assertIn('aquery',query);self.assertIn('--nofetch',query);self.assertIn('--include_param_files',query)
        self.assertIn('--host_jvm_args=-Xmx768m',query);self.assertNotIn('build',query);self.assertNotIn('//other:probe',query)
        self.assertIn('--repo_env=CC=/usr/bin/clang',query);self.assertIn('--config=linux',query)

    def test_missing_required_action_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'output.o').write_bytes(b'object')
            graph={'artifacts':[{'id':1,'execPath':'output.o'}],
                   'actions':[{'mnemonic':'CppCompile','arguments':['/bin/true','-O2'],'outputIds':[1]}]}
            with self.assertRaisesRegex(ValueError,'Missing action producers'):
                action_metadata(graph,root,{'output.o':w.file_identity(root/'output.o'),'missing.o':w.file_identity(root/'output.o')})

    def test_wrapper_is_rejected_even_with_complete_output(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'output.o').write_bytes(b'object');(root/'wrapper').write_text('#!/bin/sh\ntrue\n')
            graph={'artifacts':[{'id':1,'execPath':'output.o'}],
                   'actions':[{'mnemonic':'CppCompile','arguments':['wrapper'],'outputIds':[1]}]}
            with self.assertRaisesRegex(ValueError,'wrapper rejected'):
                action_metadata(graph,root,{'output.o':w.file_identity(root/'output.o')})

    def test_expanded_response_arguments_must_match_existing_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'output.o').write_bytes(b'object');(root/'link.params').write_text('-O2\n')
            graph={'artifacts':[{'id':1,'execPath':'output.o'}],
                   'actions':[{'mnemonic':'CppCompile','arguments':['/bin/true','@link.params'],'outputIds':[1],
                               'paramFiles':[{'execPath':'link.params','arguments':['-O3']}]}]}
            with self.assertRaisesRegex(ValueError,'arguments differ'):
                action_metadata(graph,root,{'output.o':w.file_identity(root/'output.o')})


if __name__=='__main__':unittest.main()
