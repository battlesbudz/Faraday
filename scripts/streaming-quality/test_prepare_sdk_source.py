"""Source-receipt substitution and overlay tests; no compiler/model/network."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import prepare_sdk_source as p
from common import GateError, sha


class SourcePreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.patch = self.root/'reviewed.diff'; self.patch.write_text('reviewed source patch\n')
        self.inventory = p.load(p.SDK_HELPERS/'native-owner-production.json')
        self.manifest = {'sdk_commit':p.SDK_PIN,'litert_commit':p.LITERT_PIN,
                         'patch_sha256':sha(self.patch),'files':{}}
        self.reviewed = self.root/'reviewed.json'
        self.reviewed.write_text(json.dumps(self.manifest))
        self.receipt = {'sdk_commit':p.SDK_PIN,'litert_commit':p.LITERT_PIN,
            'reviewed_patch_sha256':sha(self.patch),'reviewed_source_sha256':sha(self.reviewed),
            'owner_inventory_sha256':sha(p.SDK_HELPERS/'native-owner-production.json'),
            'native_owner_target':self.inventory['normal_target'],'native_api':30,
            'ndk_revision':'28.1.13356709','bazel':copy.deepcopy(p.android.BAZEL),
            'production_kotlin_source_sha256':dict.fromkeys(self.inventory['kotlin_sources'],'0'*64)}

    def check(self): return p.validate_inputs(self.receipt,self.patch,self.reviewed)

    def test_matching_reviewed_input_pins(self):
        self.assertEqual(self.check(),self.manifest)

    def test_changed_patch_rejected_before_checkout(self):
        self.patch.write_text('changed source patch\n')
        with self.assertRaisesRegex(GateError,'patch differs'):self.check()

    def test_changed_reviewed_inventory_rejected(self):
        self.reviewed.write_text(json.dumps(dict(self.manifest,files={'extra.cc':'0'*64})))
        with self.assertRaisesRegex(GateError,'inventory differs'):self.check()

    def test_wrong_source_or_toolchain_pins(self):
        for key,value in [('sdk_commit','0'*40),('litert_commit','0'*40),
                          ('owner_inventory_sha256','0'*64),('native_api',29),
                          ('ndk_revision','27.2.12479018'),('native_owner_target','//fake:owner')]:
            with self.subTest(key=key):
                altered=dict(self.receipt,**{key:value})
                with self.assertRaises((GateError,ValueError)):
                    p.validate_inputs(altered,self.patch,self.reviewed)

    def test_bazel_location_or_hash_substitution_rejected(self):
        self.receipt['bazel']['url']='https://example.invalid/untrusted'
        with self.assertRaisesRegex(GateError,'Bazel pin'):self.check()

    def test_missing_or_extra_production_source_rejected(self):
        self.receipt['production_kotlin_source_sha256'].pop(next(iter(self.inventory['kotlin_sources'])))
        with self.assertRaisesRegex(GateError,'source set differs'):self.check()

    def test_binary_payload_rejected_even_with_matching_digest(self):
        self.patch.write_text('GIT binary patch\n')
        self.manifest['patch_sha256']=sha(self.patch)
        self.reviewed.write_text(json.dumps(self.manifest))
        self.receipt.update(reviewed_patch_sha256=sha(self.patch),reviewed_source_sha256=sha(self.reviewed))
        with self.assertRaisesRegex(GateError,'Only reviewed source'):self.check()

    def test_existing_sdk_refused_without_subprocess_or_download(self):
        receipt=self.root/'receipt.json';receipt.write_text(json.dumps(self.receipt))
        sdk=self.root/'sdk';sdk.mkdir()
        args=SimpleNamespace(android_receipt=receipt,patch=self.patch,reviewed_source=self.reviewed,
                             sdk=sdk,bazel=self.root/'bazel')
        with patch.object(p,'run') as run,patch.object(p,'download') as download:
            with self.assertRaisesRegex(GateError,'fresh SDK'):p.prepare(args)
        run.assert_not_called();download.assert_not_called()

    def overlay_fixture(self):
        sdk=self.root/'sdk';sdk.mkdir()
        workspace=sdk/'WORKSPACE';workspace.write_text('android_ndk_repository(name = "androidndk")\n')
        owner=sdk/'experimental/native_audio_owner_jni_20261006/BUILD'
        owner.parent.mkdir(parents=True)
        owner.write_text('cc_binary(\n    name = "libnative_audio_owner_jni.so",\n    linkstatic = False,\n)\n# Deliberately separate test library:\n')
        kotlin=sdk/'Normal.kt';kotlin.write_text('production source\n')
        import hashlib
        overlays={str(file.relative_to(sdk)):{'before_sha256':sha(file),
            'after_sha256':hashlib.sha256(transform(file.read_text()).encode()).hexdigest()}
            for file,transform in [(workspace,p.android.checked_overlay),(owner,p.android.checked_owner_overlay)]}
        receipt={'production_kotlin_source_sha256':{'Normal.kt':sha(kotlin)},'build_overlays':overlays,
                 'workspace_after_api30_sha256':overlays['WORKSPACE']['after_sha256']}
        return sdk,kotlin,receipt

    def test_exact_overlays_and_production_bytes(self):
        sdk,kotlin,receipt=self.overlay_fixture()
        with patch.object(p.android,'verify_reviewed_sources'),patch.object(p.android,'verify_owner_sources',return_value=({},[kotlin])):
            p.apply_verified_sources(sdk,receipt,{})
        self.assertIn('api_level = 30',(sdk/'WORKSPACE').read_text())
        self.assertEqual(sha(sdk/'WORKSPACE'),receipt['workspace_after_api30_sha256'])

    def test_overlay_or_kotlin_substitution_rejected(self):
        sdk,kotlin,receipt=self.overlay_fixture()
        with patch.object(p.android,'verify_reviewed_sources'),patch.object(p.android,'verify_owner_sources',return_value=({},[kotlin])):
            bad=copy.deepcopy(receipt);bad['production_kotlin_source_sha256']['Normal.kt']='0'*64
            with self.assertRaisesRegex(GateError,'Kotlin bytes'):p.apply_verified_sources(sdk,bad,{})
            bad=copy.deepcopy(receipt);bad['build_overlays']['WORKSPACE']['before_sha256']='0'*64
            with self.assertRaisesRegex(GateError,'Overlay input'):p.apply_verified_sources(sdk,bad,{})
            bad=copy.deepcopy(receipt);bad['build_overlays']['unexpected']={}
            with self.assertRaisesRegex(GateError,'Unreviewed source overlay'):p.apply_verified_sources(sdk,bad,{})


if __name__=='__main__':unittest.main()
