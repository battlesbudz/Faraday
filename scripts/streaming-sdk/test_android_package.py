import io
from pathlib import Path
import tempfile
import hashlib
import struct
import unittest
import zipfile

from build_android_sdk import checked_overlay, checked_owner_overlay, verify_owner_sources
from package_android_aar import OWNER, ROOTS, dependency_closure, index_libraries, inspect_classes, library_name, write_archive, validate_owner_exports, validate_sdk_exports


class AndroidPackagingContracts(unittest.TestCase):
    def test_closure_keeps_explicit_dlopen_roots_and_transitive_non_system_libraries(self):
        available = {n: n for n in ['libjni.so', 'libgpu.so', 'libprovider.so', 'libc++_shared.so']}
        needs = {'libjni.so': ['libprovider.so', 'libc.so'], 'libgpu.so': ['libc++_shared.so'],
                 'libprovider.so': ['libc.so'], 'libc++_shared.so': ['libm.so', 'libdl.so']}
        selected, _ = dependency_closure(['libjni.so', 'libgpu.so'], available,
                                         {'libc.so', 'libm.so', 'libdl.so'}, needs.__getitem__)
        self.assertEqual(set(available), set(selected))

    def test_missing_dependency_is_a_failure(self):
        with self.assertRaisesRegex(ValueError, 'Missing Android runtime'):
            dependency_closure(['libjni.so'], {'libjni.so': 'libjni.so'}, {'libc.so'}, lambda _: ['libmissing.so'])

    def test_cyclic_native_dependencies_terminate(self):
        a = {'liba.so': 'liba.so', 'libb.so': 'libb.so'}
        selected, _ = dependency_closure(['liba.so'], a, set(), lambda p: ['libb.so' if p == 'liba.so' else 'liba.so'])
        self.assertEqual(2, len(selected))

    def test_unsafe_or_pathful_library_names_are_rejected(self):
        for name in ['../liba.so', '/system/lib64/libc.so', 'lib.so/evil', 'libfoo.so.1', 'a.so', '']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                library_name(name)

    def test_conflicting_same_name_bytes_are_never_pick_first(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp) / 'a', Path(temp) / 'b'
            a.mkdir(); b.mkdir()
            (a / 'liba.so').write_bytes(b'one'); (b / 'liba.so').write_bytes(b'two')
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                index_libraries([a / 'liba.so', b / 'liba.so'])

    def test_identical_duplicate_library_candidates_are_allowed(self):
        with tempfile.TemporaryDirectory() as temp:
            a = Path(temp) / 'liba.so'; a.write_bytes(b'same')
            self.assertEqual({'liba.so': a}, index_libraries([a, a]))

    @staticmethod
    def class_bytes(methods=None, major=61):
        methods = methods or {}
        pool = bytearray()
        for name, descriptor in methods.items():
            for value in [name, descriptor]:
                encoded = value.encode()
                pool += b'\x01' + len(encoded).to_bytes(2, 'big') + encoded
        data = bytearray(b'\xca\xfe\xba\xbe\x00\x00' + major.to_bytes(2, 'big'))
        data += (1 + 2 * len(methods)).to_bytes(2, 'big') + pool
        data += struct.pack('>HHHHH', 0x21, 0, 0, 0, 0)  # access/this/super/interfaces/fields
        data += len(methods).to_bytes(2, 'big')
        for index, _ in enumerate(methods):
            data += struct.pack('>HHHH', 0x109, 1 + 2 * index, 2 + 2 * index, 0)
        data += b'\x00\x00'
        return bytes(data)

    @classmethod
    def classes(cls, extra=None, major=61):
        entries = {n: cls.class_bytes(OWNER['sdk_jni_methods'] if n.endswith('/LiteRtLmJni.class') else None, major=major) for n in OWNER['sdk_classes']}
        for name in OWNER['owner_classes']:
            methods = OWNER['owner_jni_methods'] if name.endswith('/NativeAudioOwnerJni.class') else None
            entries[name] = cls.class_bytes(methods, major)
        entries.update(extra or {})
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as z:
            for name, data in entries.items():
                z.writestr(name, data)
        return output.getvalue()

    def test_expected_java17_production_class_contract(self):
        inspect_classes(self.classes())

    def test_host_test_or_native_payload_cannot_leak_into_classes(self):
        for name in ['FooTest.class', 'SomeSmokeKt.class', 'libjni.so', 'NativeAudioOwnerTestJni.class', 'NativeOwnerJniHarnessKt.class', 'FixtureLease.class']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                inspect_classes(self.classes({name: b'fake'}))

    def test_newer_bytecode_and_incomplete_sdk_rejected(self):
        with self.assertRaises(ValueError):
            inspect_classes(self.classes(major=65))
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w'):
            pass
        with self.assertRaises(ValueError):
            inspect_classes(output.getvalue())

    def test_normal_owner_is_an_explicit_root_and_test_library_is_rejected(self):
        self.assertIn('libnative_audio_owner_jni.so', ROOTS)
        with self.assertRaisesRegex(ValueError, 'Test owner library'):
            library_name('libnative_audio_owner_jni_test.so')
        with self.assertRaises(ValueError):
            dependency_closure(['libjni.so'], {'libjni.so':'x'}, set(),
                               lambda _: ['libnative_audio_owner_jni_test.so'])

    def test_owner_exports_require_exact_reviewed_methods_and_no_fake_export(self):
        good = set(OWNER['owner_jni_exports'])
        validate_owner_exports(good)
        with self.assertRaises(ValueError): validate_owner_exports(set(list(good)[1:]))
        with self.assertRaises(ValueError): validate_owner_exports(good | {'Java_com_google_ai_edge_litertlm_NativeAudioOwnerTestJni_nativeTestArm'})

    def test_pre_timing_owner_abi_is_rejected(self):
        methods = {name: descriptor for name, descriptor in OWNER['owner_jni_methods'].items()
                   if name not in {'nativeTimingHandshake', 'nativeTimingSnapshot'}}
        self.assertEqual(6, len(methods))
        self.assertEqual('()[J', OWNER['owner_jni_methods']['nativeTimingHandshake'])
        self.assertEqual('(J)[J', OWNER['owner_jni_methods']['nativeTimingSnapshot'])
        with self.assertRaises(ValueError):
            inspect_classes(self.classes({'com/google/ai/edge/litertlm/NativeAudioOwnerJni.class':
                                          self.class_bytes(methods)}))
        with self.assertRaises(ValueError):
            validate_owner_exports({'Java_com_google_ai_edge_litertlm_NativeAudioOwnerJni_' + name
                                    for name in methods})

    def test_test_method_hidden_in_allowed_owner_class_is_rejected(self):
        name = 'com/google/ai/edge/litertlm/NativeAudioOwner$Companion.class'
        with self.assertRaisesRegex(ValueError, 'Test/fake method'):
            inspect_classes(self.classes({name:self.class_bytes({'createForLifecycleTest':'()V'})}))

    def test_native_descriptor_drift_is_rejected(self):
        name = 'com/google/ai/edge/litertlm/NativeAudioOwnerJni.class'
        methods = dict(OWNER['owner_jni_methods'], nativeAppend='(J)V')
        with self.assertRaisesRegex(ValueError, 'reviewed static native methods'):
            inspect_classes(self.classes({name:self.class_bytes(methods)}))

    def test_unknown_owner_class_and_truncated_class_are_rejected(self):
        name = 'com/google/ai/edge/litertlm/NativeAudioUnreviewed.class'
        with self.assertRaisesRegex(ValueError, 'explicit production allowlist'):
            inspect_classes(self.classes({name:self.class_bytes()}))
        name = 'com/google/ai/edge/litertlm/NativeAudioOwnerJni.class'
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            inspect_classes(self.classes({name:self.class_bytes()[:8]}))

    def test_exact_owner_source_hashes_and_kotlin_path_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root/'owner.cc').write_text('reviewed'); (root/'Owner.kt').write_text('class Owner')
            inventory = {'owner_source_sha256':{'owner.cc':hashlib.sha256(b'reviewed').hexdigest()},
                         'kotlin_sources':['Owner.kt']}
            _, sources = verify_owner_sources(root, inventory)
            self.assertEqual([root/'Owner.kt'], sources)
            (root/'owner.cc').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'source changed'): verify_owner_sources(root, inventory)
            inventory['owner_source_sha256'] = {}; inventory['kotlin_sources']=['../Outside.kt']
            with self.assertRaisesRegex(ValueError, 'source allowlist'): verify_owner_sources(root, inventory)

    def test_api30_overlay_is_exact_and_cannot_double_apply(self):
        old = 'before\nandroid_ndk_repository(name = "androidndk")\nafter\n'
        patched = checked_overlay(old)
        self.assertIn('api_level = 30', patched)
        with self.assertRaises(ValueError):
            checked_overlay(patched)
        with self.assertRaises(ValueError):
            checked_overlay(old + old)

    def test_packaging_is_deterministic_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp) / 'a.zip', Path(temp) / 'b.zip'
            write_archive(a, {'b': b'2', 'a': b'1'})
            write_archive(b, {'a': b'1', 'b': b'2'})
            self.assertEqual(a.read_bytes(), b.read_bytes())
            with self.assertRaises(FileExistsError):
                write_archive(a, {'other': b'3'})


if __name__ == '__main__':
    unittest.main()
