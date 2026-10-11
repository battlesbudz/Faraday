"""Fresh reconstructed build/front-end checks, distinct from lost historical tests."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from build_sherpa import source_fingerprint
from sherpa_jni_profile import APP_SYMBOLS

ROOT = Path(__file__).resolve().parents[1]


class SmartTurnBuildContractTest(unittest.TestCase):
    def test_fingerprint_changes_for_profile_source_name_and_source_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); sources = root / 'app/src/main/cpp/smartturn'
            sources.mkdir(parents=True); (root / 'scripts').mkdir()
            qnn = root / 'app/src/main/cpp/whisperqnn'; qnn.mkdir(); (qnn / 'decoder.cc').write_text('qnn')
            profile = root / 'scripts/sherpa_jni_profile.py'; profile.write_text('profile')
            source = sources / 'frontend.cc'; source.write_text('a')
            first = source_fingerprint(root); self.assertEqual(first, source_fingerprint(root))
            source.write_text('b'); second = source_fingerprint(root); self.assertNotEqual(first, second)
            source.rename(sources / 'renamed.cc'); third = source_fingerprint(root); self.assertNotEqual(second, third)
            profile.write_text('changed'); fourth = source_fingerprint(root); self.assertNotEqual(third, fourth)
            (qnn / 'decoder.cc').write_text('qnn2'); self.assertNotEqual(fourth, source_fingerprint(root))

    def test_missing_native_source_is_not_a_valid_cache_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises((FileNotFoundError, ValueError)): source_fingerprint(Path(directory))

    def test_bridge_symbols_runtime_and_cache_inputs_remain_narrow(self):
        expected = {'Java_com_battlesbudz_jarvis_v2_voice_smartturn_SmartTurnNative_' + name
                    for name in ('create', 'prepare', 'infer', 'cancel', 'close')}
        self.assertEqual(set(APP_SYMBOLS), expected)
        contract = set(json.loads((ROOT / 'scripts/sherpa_required_symbols.json').read_text()))
        self.assertEqual({s for s in contract if 'SmartTurn' in s}, expected)
        self.assertIn('app/src/main/cpp/smartturn', (ROOT / 'app/build.gradle.kts').read_text())
        self.assertIn("'app/src/main/cpp/smartturn/**'", (ROOT / '.github/workflows/android.yml').read_text())
        self.assertIn('-keep class com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnNative { *; }',
                      (ROOT / 'app/proguard-rules.pro').read_text())
        build = (ROOT / 'scripts/build_sherpa.py').read_text()
        self.assertIn('onnxruntime-android-1.27.1.zip', build)
        self.assertIn('-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384', build)
        self.assertIn('-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON', build)

    def test_manifest_model_identity_matches_both_runtime_guards(self):
        manifest = json.loads((ROOT / 'third_party/smart-turn/manifest.json').read_text())
        model = manifest['model']
        for filename in ('SmartTurnModelSpec.kt', 'NativeSmartTurnBackend.kt'):
            source = (ROOT / 'app/src/main/java/com/battlesbudz/jarvis/v2/voice/smartturn' / filename).read_text()
            self.assertIn(model['sha256'], source)
            self.assertIn('8_679_182', source)
        self.assertFalse(manifest['distribution']['weights_in_git_or_apk'])
        self.assertFalse(manifest['runtime']['new_runtime_library'])

    def test_pinned_notice_hashes_and_binary_distribution_copies_match(self):
        manifest = json.loads((ROOT / 'third_party/smart-turn/manifest.json').read_text())
        for name, expected in manifest['licenses'].items():
            source = (ROOT / 'third_party/smart-turn' / name).read_bytes()
            self.assertEqual(expected, hashlib.sha256(source).hexdigest())
            self.assertEqual(source, (ROOT / 'app/src/main/assets/licenses/smart-turn' / name).read_bytes())
        card = (ROOT / manifest['model']['model_card_file']).read_bytes()
        self.assertEqual(manifest['model']['model_card_sha256'], hashlib.sha256(card).hexdigest())
        self.assertIn(b'license: bsd-2-clause', card)
        self.assertEqual((ROOT / 'third_party/smart-turn/NOTICE.md').read_bytes(),
                         (ROOT / 'app/src/main/assets/licenses/smart-turn/NOTICE.md').read_bytes())

    def test_frozen_reference_goldens_against_actual_cpp(self):
        path = ROOT / 'scripts/smart-turn/verify_frontend.py'
        spec = importlib.util.spec_from_file_location('smart_turn_frontend_check', path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        result = module.verify(ROOT)
        self.assertEqual(len(result['cases']), 8)
        self.assertTrue(all(case['passed'] for case in result['cases']))
        self.assertTrue(result['nonfinite_input_rejected'])
