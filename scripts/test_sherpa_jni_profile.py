import tempfile
import unittest
from pathlib import Path
from sherpa_jni_profile import configure, JNI_SOURCES, UPSTREAM_SOURCES, APP_SOURCES, APP_SYMBOLS, UPSTREAM_EXPORTS, APP_EXPORTS


class SherpaProfileTest(unittest.TestCase):
    def test_retain_speaker_stream_and_tts_but_drop_unused_wrappers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jni = root / 'sherpa-onnx/jni'
            jni.mkdir(parents=True)
            for name in (*UPSTREAM_SOURCES, 'offline-tts.cc'):
                (jni / name).touch()
            path = jni / 'CMakeLists.txt'
            path.write_text('set(sources\n  ' + '\n  '.join(sorted(UPSTREAM_SOURCES)) + '\n)\nif(SHERPA_ONNX_ENABLE_TTS)\nlist(APPEND sources offline-tts.cc)\nendif()\n')
            exports = jni / 'sherpa-onnx-symbols.lds'
            exports.write_text(UPSTREAM_EXPORTS)
            configure(root)
            result = path.read_text()
            self.assertEqual(exports.read_text(), APP_EXPORTS)
            for name in APP_SOURCES:
                self.assertIn(name, result)
                self.assertTrue((jni / name).is_file())
            for symbol in APP_SYMBOLS:
                self.assertIn(symbol + ';', exports.read_text())
            self.assertIn('local: *;', exports.read_text())
            self.assertIn('online-stream.cc', result)
            self.assertIn('offline-tts.cc', result)
            self.assertNotIn('online-recognizer.cc', result)
            self.assertNotIn('keyword-spotter.cc', result)
            configure(root)
            self.assertEqual(result, path.read_text())
            path.write_text(result.replace('common.cc', 'unknown.cc'))
            with self.assertRaises(ValueError):
                configure(root)

    def test_unexpected_export_pattern_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); jni = root / 'sherpa-onnx/jni'; jni.mkdir(parents=True)
            for name in (*UPSTREAM_SOURCES, 'offline-tts.cc'): (jni / name).touch()
            (jni / 'CMakeLists.txt').write_text('set(sources\n  ' + '\n  '.join(sorted(UPSTREAM_SOURCES)) + '\n)\nlist(APPEND sources offline-tts.cc)\n')
            (jni / 'sherpa-onnx-symbols.lds').write_text('{ global: *; };')
            with self.assertRaises(ValueError): configure(root)
