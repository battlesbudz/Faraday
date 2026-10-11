"""Weight-free runtime pins, architecture and dependency isolation checks."""
from pathlib import Path
import re
import struct
import tempfile
import unittest
from prepare_whisper_qnn import FILES, HOST, DSP, ASSET_SUFFIX, asset_name, check_elf, prepare
from sherpa_jni_profile import QNN_SYMBOLS
ROOT = Path(__file__).resolve().parents[1]

class WhisperQnnPackageTest(unittest.TestCase):
    def elf(self, machine=183, alignment=16384):
        data = bytearray(120); data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, machine); struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
        return data
    def test_host_architecture_and_16k_segments_fail_closed(self):
        check_elf(self.elf(), 183)
        for blob in (b'not an elf', self.elf(machine=62), self.elf(alignment=4096)):
            with self.assertRaises((ValueError, IndexError, struct.error)): check_elf(blob, 183)
        check_elf(self.elf(machine=164), 164)
    def test_wrong_archive_pin_is_rejected_before_output_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); p = root/'p.aar'; r = root/'r.aar'; out = root/'out'
            p.write_bytes(b'bad provider'); r.write_bytes(b'bad runtime'); out.mkdir(); (out/'keep').write_text('retained')
            with self.assertRaisesRegex(ValueError, 'checksum'): prepare(p, r, out)
            self.assertEqual((out/'keep').read_text(), 'retained')
    def test_runtime_and_kotlin_pins_are_identical_and_narrow(self):
        source = (ROOT/'app/src/main/java/com/battlesbudz/jarvis/v2/voice/whisper/WhisperQnnRuntime.kt').read_text()
        actual = {n:(int(b),h) for n,b,h in re.findall(r'Library\("([^"]+)", (\d+), "([0-9a-f]{64})"\)', source)}
        self.assertEqual(actual, FILES); self.assertEqual(set(FILES), set(HOST) | {DSP})
        self.assertNotIn('libonnxruntime.so', FILES); self.assertNotIn('libQnnHtpPrepare.so', FILES)
        self.assertIn('part.setReadOnly()', source); self.assertIn('GZIPInputStream(context.assets.open(', source)
        self.assertIn('supportedSoc(Build.SOC_MODEL)', source)
        self.assertIn(f'private const val ASSET_SUFFIX = "{ASSET_SUFFIX}"', source)
        self.assertIn('${spec.name}$ASSET_SUFFIX', source)
        for name in FILES:
            self.assertFalse(asset_name(name).endswith('.gz'))
            self.assertEqual(asset_name(name), name + '.gz.bin')
    def test_exports_and_native_sources_are_build_inputs(self):
        import json
        self.assertTrue(set(QNN_SYMBOLS) <= set(json.loads((ROOT/'scripts/sherpa_required_symbols.json').read_text())))
        build = (ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('inputs.dir(rootProject.file("app/src/main/cpp/whisperqnn"))', build)
        self.assertIn('com.qualcomm.qti:onnxruntime-android-qnn:2.6.0@aar', build)
        self.assertIn('com.qualcomm.qti:qnn-runtime:2.50.0@aar', build)
        self.assertNotIn('com.microsoft.onnxruntime:onnxruntime-android', build)
        self.assertNotIn('pickFirst', build)
