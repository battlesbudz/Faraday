import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from repair_sampler_dependencies import TARGETS as SAMPLER_INPUTS, PROVIDER, TOOL_SHA256

from build_android_sdk import SDK_PIN, LITERT_PIN, checked_owner_overlay, verify_reviewed_sources
from package_android_aar import OWNER, ROOTS, sha256, write_archive, validate_sdk_exports
import test_android_package as fixtures
from validate_artifact import validate, check_stl, HERE, ROOT
from producer_identity import workflow_identity


class ArtifactProvenanceContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.patch = self.root/'PATCH.diff'; self.patch.write_bytes(b'reviewed patch')
        self.manifest = self.root/'reviewed-source.json'
        self.manifest.write_text(json.dumps({'sdk_commit':SDK_PIN,'litert_commit':LITERT_PIN,
            'patch_sha256':sha256(self.patch)}))
        self.aar = self.root/'sdk.aar'; self.prov = self.root/'sdk.provenance.json'
        self.identity = {'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_REPOSITORY':'owner/repo'}
        self.classes = fixtures.AndroidPackagingContracts.classes()
        self.native = {name:name.encode() for name in ROOTS}
        # Tiny archives exercise provenance transport separately from actual ELF
        # derivation, which the producer runs on both pinned upstream binaries.
        fake_outputs = {name: hashlib.sha256(self.native[name]).hexdigest() for name in SAMPLER_INPUTS}
        override = patch('validate_artifact.SAMPLER_OUTPUTS', fake_outputs)
        override.start(); self.addCleanup(override.stop)
        self.provenance = {
            'source':{'sdk_commit':SDK_PIN,'litert_commit':LITERT_PIN,
                      'reviewed_patch_sha256':sha256(self.patch), 'reviewed_source_sha256':sha256(self.manifest),
                      'native_api':30,'ndk_revision':'28.1.13356709','workflow_identity':self.identity.copy()},
            'owner_inventory_sha256':sha256(HERE/'native-owner-production.json'),
            'page_auditor_sha256':sha256(ROOT/'scripts/check_page_sizes.py'),
            'sdk_jni_exports':OWNER['sdk_jni_exports'],'owner_jni_exports':OWNER['owner_jni_exports'],
            'explicit_dlopen_roots':ROOTS, 'classes_sha256':hashlib.sha256(self.classes).hexdigest(),
            'native_sha256':{n:hashlib.sha256(v).hexdigest() for n,v in self.native.items()},
            'native_dependencies':{n:['libc.so'] for n in self.native},
            'ndk_api30_system_libraries':['libc.so']}
        self.provenance['source'].update(
            sampler_dependency_recipe_sha256={name:sha256(HERE/name) for name in
                ['repair_sampler_dependencies.py','verify_sampler_derivation.py','patchelf-tool.json']},
            sampler_dependency_tool=json.loads((HERE/'patchelf-tool.json').read_text()),
            sampler_dependency_contract={'passed':True,'check_count':24})
        self.provenance['sampler_dependency_derivations'] = {}
        self.provenance['sampler_modifications_notice_sha256'] = sha256(HERE/'SAMPLER-DEPENDENCY-NOTICE.md')
        for name, input_sha in SAMPLER_INPUTS.items():
            self.provenance['native_dependencies'][name] = [PROVIDER,'libc.so']
            self.provenance['sampler_dependency_derivations'][name] = dict(
                input_sha256=input_sha, output_sha256=fake_outputs[name], provider=PROVIDER,
                tool_sha256=TOOL_SHA256, original_needed=['libc.so'], derived_needed=[PROVIDER,'libc.so'],
                original_relro_unchanged=True, original_runtime_sections_unchanged=True,
                symbol_abi_unchanged=True, new_metadata_load_read_only=True, relocation_targets_verified=1)
        self.write()

    def write(self):
        self.aar.unlink(missing_ok=True)
        entries = {'classes.jar':self.classes,'assets/litert-lm-source-provenance.json':json.dumps(self.provenance).encode()}
        entries['assets/litert-lm-sampler-modifications.md'] = (HERE/'SAMPLER-DEPENDENCY-NOTICE.md').read_bytes()
        entries.update({'jni/arm64-v8a/'+n:v for n,v in self.native.items()})
        write_archive(self.aar,entries)
        self.prov.write_text(json.dumps(dict(self.provenance,aar_sha256=sha256(self.aar))))

    def validate(self, **kwargs):
        return validate(self.aar,self.prov,self.manifest,self.patch,identity=self.identity,**kwargs)

    def test_exact_bytes_and_run_identity_pass(self):
        result = self.validate(expected_aar=sha256(self.aar),expected_provenance=sha256(self.prov))
        self.assertEqual(result['sha256'],sha256(self.aar))

    def test_sampler_hardening_or_dependency_receipt_cannot_be_omitted(self):
        name = next(iter(SAMPLER_INPUTS))
        for field, bad in [('new_metadata_load_read_only',False),('provider','wrong.so'),
                           ('input_sha256','0'*64),('relocation_targets_verified',0)]:
            receipt = self.provenance['sampler_dependency_derivations'][name]
            old = receipt[field]; receipt[field] = bad; self.write()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError,'Sampler dependency or hardening'):
                self.validate()
            receipt[field] = old

    def test_sampler_tool_and_recipe_must_match_consumer_source(self):
        self.provenance['source']['sampler_dependency_recipe_sha256']['repair_sampler_dependencies.py'] = '0'*64
        self.write()
        with self.assertRaisesRegex(ValueError,'Sampler dependency recipe'):
            self.validate()

    def test_partial_retry_validates_original_producer_provenance(self):
        consumer=dict(self.identity,GITHUB_RUN_ATTEMPT='2')
        expected=workflow_identity(consumer,'1')
        result=validate(self.aar,self.prov,self.manifest,self.patch,
            expected_aar=sha256(self.aar),expected_provenance=sha256(self.prov),identity=expected)
        self.assertEqual(result['sha256'],sha256(self.aar))
        with self.assertRaisesRegex(ValueError,'exact workflow'):
            validate(self.aar,self.prov,self.manifest,self.patch,identity=workflow_identity(consumer))

    def test_source_receipt_is_bound_to_exact_bytes_and_embedded_source(self):
        source=self.root/'source-receipt.json';source.write_text(json.dumps(self.provenance['source']))
        result=self.validate(source_receipt=source,expected_source_receipt=sha256(source))
        self.assertEqual(sha256(source),result['source_receipt_sha256'])
        with self.assertRaisesRegex(ValueError,'source receipt SHA256'):
            self.validate(source_receipt=source,expected_source_receipt='0'*64)
        source.write_text(json.dumps(dict(self.provenance['source'],extra='tampered')))
        with self.assertRaisesRegex(ValueError,'differs from AAR'):
            self.validate(source_receipt=source,expected_source_receipt=sha256(source))
        with self.assertRaisesRegex(ValueError,'requires its exact file'):
            self.validate(expected_source_receipt=sha256(source))

    def test_wrong_producer_digest_rejected(self):
        for key in ['expected_aar','expected_provenance']:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError,'Producer SHA256'):
                self.validate(**{key:'0'*64})

    def test_stale_run_attempt_and_source_commit_rejected(self):
        for key in self.identity:
            saved = self.provenance['source']['workflow_identity'][key]
            self.provenance['source']['workflow_identity'][key] = 'wrong'
            self.write()
            with self.subTest(key=key), self.assertRaisesRegex(ValueError,'exact workflow'):
                self.validate()
            self.provenance['source']['workflow_identity'][key] = saved

    def test_tampered_aar_rejected(self):
        self.aar.write_bytes(self.aar.read_bytes()+b'tamper')
        with self.assertRaisesRegex(ValueError,'AAR checksum'):
            self.validate()

    def test_tampered_external_receipt_rejected(self):
        p=json.loads(self.prov.read_text()); p['extra']='tamper'; self.prov.write_text(json.dumps(p))
        with self.assertRaisesRegex(ValueError,'Embedded and external'):
            self.validate()

    def test_changed_patch_and_reviewed_manifest_rejected(self):
        self.patch.write_bytes(b'new patch')
        with self.assertRaisesRegex(ValueError,'Reviewed source'):
            self.validate()

    def test_session_export_missing_rejected(self):
        self.provenance['sdk_jni_exports']=[s for s in OWNER['sdk_jni_exports'] if 'nativeSessionAwaitIdle' not in s]
        self.write()
        with self.assertRaisesRegex(ValueError,'checked lifecycle ABI'):
            validate_sdk_exports(self.provenance['sdk_jni_exports'])
        with self.assertRaisesRegex(ValueError,'Session/Conversation'):
            self.validate()

    def test_native_payload_tamper_and_missing_closure_rejected(self):
        name=ROOTS[0]; self.native[name]=b'changed'; self.write()
        with self.assertRaisesRegex(ValueError,'Native bytes changed'):
            self.validate()
        self.native[name]=name.encode(); self.provenance['native_dependencies'][name]=['libmissing.so']; self.write()
        with self.assertRaisesRegex(ValueError,'Unresolved'):
            self.validate()

    def test_wrong_abi_is_rejected(self):
        self.native['../x86_64/libfake.so']=b'fake'; self.write()
        with self.assertRaisesRegex(ValueError,'Unexpected ABI'):
            self.validate()

    def test_stl_is_never_platform_or_implicitly_selected(self):
        self.provenance['ndk_api30_system_libraries'].append('libc++_shared.so');self.write()
        with self.assertRaisesRegex(ValueError,'platform library'):
            self.validate()
        check_stl(self.aar,self.root)  # No shared STL in the SDK needs no intervention.
        self.native['libc++_shared.so']=b'NDK28'; self.write()
        p=self.root/'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
        p.parent.mkdir(parents=True);p.write_bytes(b'NDK27')
        with self.assertRaisesRegex(ValueError,'collides'):
            check_stl(self.aar,self.root)

    def test_android_owner_overlay_preserves_test_and_host(self):
        source='    name = "libnative_audio_owner_jni.so",\n    linkstatic = False,\n# Deliberately separate test library:\n    linkstatic = False,\n'
        result=checked_owner_overlay(source)
        self.assertIn('"@platforms//os:android": True',result)
        self.assertIn('"//conditions:default": False',result)
        self.assertTrue(result.endswith('# Deliberately separate test library:\n    linkstatic = False,\n'))
        with self.assertRaises(ValueError): checked_owner_overlay(result)
        with self.assertRaises(ValueError): checked_owner_overlay(source.replace('linkstatic = False','linkstatic = True'))

    def test_source_manifest_rejects_drift_and_unsafe_paths(self):
        p=self.root/'source.kt';p.write_bytes(b'exact')
        m={'files':{'source.kt':sha256(p)}};verify_reviewed_sources(self.root,m)
        p.write_bytes(b'changed')
        with self.assertRaises(ValueError):verify_reviewed_sources(self.root,m)
        with self.assertRaises(ValueError):verify_reviewed_sources(self.root,{'files':{'../outside':'a'*64}})


if __name__=='__main__':unittest.main()
