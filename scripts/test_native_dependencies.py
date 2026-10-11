"""Regression checks for APK native dependency validation."""
import io
import unittest
import zipfile

from check_asr_apk import check_dependencies, check_retired_moonshine, RETIRED_MOONSHINE_LIBRARIES


def needed(*libraries):
    return '\n'.join(f' 0x0000000000000001 (NEEDED) Shared library: [{name}]'
                     for name in libraries)


class NativeDependencyTests(unittest.TestCase):
    def test_missing_cpp_runtime_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, r'libsherpa-onnx-jni.*libc\+\+_shared'):
            check_dependencies({'libsherpa-onnx-jni.so': needed('libc++_shared.so', 'liblog.so')})

    def test_bundled_cpp_runtime_and_android_dependencies_pass(self):
        check_dependencies({
            'libsherpa-onnx-jni.so': needed('libc++_shared.so', 'liblog.so'),
            'libc++_shared.so': needed('libc.so', 'libm.so', 'libdl.so'),
        })

    def test_missing_transitive_dependency_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'libhelper.so'):
            check_dependencies({
                'libsherpa-onnx-jni.so': needed('libc++_shared.so'),
                'libc++_shared.so': needed('libhelper.so'),
            })

    def test_private_platform_library_is_not_assumed_available(self):
        with self.assertRaisesRegex(AssertionError, 'libprivate.so'):
            check_dependencies({'libvoice.so': needed('libprivate.so')})


class MoonshineRetirementTests(unittest.TestCase):
    def check_archive(self, entries):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        with zipfile.ZipFile(io.BytesIO(output.getvalue())) as archive:
            check_retired_moonshine(archive)

    def test_shared_sherpa_runtime_and_current_classes_are_not_retired(self):
        self.check_archive({"lib/arm64-v8a/libonnxruntime.so": b"shared ORT",
                            "lib/arm64-v8a/libsherpa-onnx-jni.so": b"Sherpa",
                            "classes.dex": b"Lcom/k2fsa/sherpa/onnx/OfflineRecognizer;"})

    def test_each_retired_native_runtime_is_rejected_in_every_abi(self):
        for abi in ("arm64-v8a", "x86_64", "armeabi-v7a"):
            for library in RETIRED_MOONSHINE_LIBRARIES:
                with self.subTest(abi=abi, library=library):
                    with self.assertRaisesRegex(AssertionError, "Retired Moonshine library"):
                        self.check_archive({f"lib/{abi}/{library}": b"stale native library"})

    def test_retired_sdk_is_rejected_in_secondary_dex(self):
        for dex in ("classes.dex", "classes2.dex", "classes12.dex"):
            with self.subTest(dex=dex):
                with self.assertRaisesRegex(AssertionError, "Retired Moonshine SDK"):
                    self.check_archive({dex: b"dex\x00Lai/moonshine/voice/Transcriber;"})


if __name__ == '__main__':
    unittest.main()
