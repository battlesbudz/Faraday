import copy
import gzip
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import verify_packaged_encoder_assets as target


class PackagedEncoderAssets(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.contract = target.source_contract()
        self.entries = {name: (target.ROOT / 'app/src/main' / name).read_bytes()
                        for name in self.contract['assets']}
        self.apk = self.root / target.APK_NAMES[0]
        self.identity = {'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                         'GITHUB_SHA': 'a' * 40, 'GITHUB_REPOSITORY': 'battlesbudz/Jarvis-OS-V2'}

    def write(self, compression=zipfile.ZIP_STORED, extra=()):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w', compression) as archive:
                for name, data in [*self.entries.items(), *extra]:
                    archive.writestr(name, data)

    def verify(self):
        return target.verify(self.apk)

    def pair(self):
        self.write()
        compact = self.root / target.APK_NAMES[1]
        shutil.copyfile(self.apk, compact)
        result = target.report([self.apk, compact], self.identity)
        (self.root / target.REPORT_NAME).write_text(json.dumps(result))
        return result

    def test_stored_and_zip_compressed_exact_assets_pass(self):
        for mode in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(mode=mode):
                self.write(mode)
                result = self.verify()
                self.assertTrue(result['passed'])
                self.assertEqual(target.file_sha256(self.apk), result['apk_sha256'])
                literal = result['assets'][target.PREFIX + target.LITERALS_NAME]
                self.assertEqual(target.DECODED_SHA256, literal['decoded_sha256'])
                self.assertEqual(target.DECODED_BYTES, literal['decoded_bytes'])

    def test_each_missing_runtime_asset_or_notice_fails(self):
        original = dict(self.entries)
        for name in original:
            with self.subTest(name=name):
                self.entries = {n: data for n, data in original.items() if n != name}
                self.write()
                with self.assertRaisesRegex(ValueError, 'encoder APK paths'): self.verify()

    def test_aapt_decoded_gz_and_ambiguous_aliases_fail(self):
        name = target.PREFIX + target.LITERALS_NAME
        compressed = self.entries.pop(name)
        decoded = gzip.decompress(compressed)
        self.assertEqual(target.DECODED_SHA256, target.digest(decoded))
        for alias, data in [('structural-literals.bin', decoded),
                            ('structural-literals.bin.gz', compressed)]:
            with self.subTest(alias=alias):
                self.entries[target.PREFIX + alias] = data; self.write()
                with self.assertRaisesRegex(ValueError, 'legacy, transformed'): self.verify()
                del self.entries[target.PREFIX + alias]
        self.entries[name] = compressed
        for alias in (target.PREFIX + 'structural-literals.bin',
                      target.PREFIX + 'structural-literals.bin.gz',
                      'assets/elsewhere/' + target.LITERALS_NAME,
                      'assets/gemma_streaming/../' + target.LITERALS_NAME,
                      'assets/GEMMA_STREAMING/' + target.LITERALS_NAME,
                      'assets\\gemma_streaming\\' + target.LITERALS_NAME):
            with self.subTest(alias=alias):
                self.write(extra=[(alias, compressed)])
                with self.assertRaisesRegex(ValueError, 'ambiguous encoder APK paths'): self.verify()

    def test_same_size_payload_or_notice_tampering_fails(self):
        original = dict(self.entries)
        for name, payload in original.items():
            with self.subTest(name=name):
                self.entries = dict(original)
                self.entries[name] = bytes([payload[0] ^ 1]) + payload[1:]
                self.write()
                with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'): self.verify()

    def test_truncated_or_oversized_zip_entry_rejected_before_open(self):
        name = target.PREFIX + target.LITERALS_NAME
        payload = self.entries[name]
        for changed in (payload[:-1], payload + b'x'):
            self.entries[name] = changed; self.write()
            with zipfile.ZipFile(self.apk) as archive, patch.object(
                    archive, 'open', side_effect=AssertionError('read before size check')):
                with self.assertRaisesRegex(ValueError, 'size mismatch'):
                    target.bounded_entry(archive, name, target.LITERALS_BYTES)

    def test_duplicate_any_zip_entry_fails(self):
        for name in (target.PREFIX + target.LITERALS_NAME, 'unrelated.txt'):
            with self.subTest(name=name):
                self.write(extra=[(name, b'one'), (name, b'two')])
                with self.assertRaisesRegex(ValueError, 'Duplicate'): self.verify()

    def test_report_requires_both_variants_and_rejects_duplicate_arguments(self):
        self.write()
        for apks in ([], [self.apk], [self.apk, self.apk]):
            result = target.report(apks)
            self.assertFalse(result['passed'])
            self.assertIn('required exactly once', result['error'])

    def test_apk_mutation_during_validation_cannot_receive_success_receipt(self):
        self.write()
        with patch.object(target, 'file_sha256', side_effect=['a' * 64, 'b' * 64]):
            with self.assertRaisesRegex(ValueError, 'changed during'):
                self.verify()

    def test_decoded_byte_bound_and_integrity_are_independent(self):
        compressed = self.entries[target.PREFIX + target.LITERALS_NAME]
        self.assertEqual(target.DECODED_BYTES, target.decoded_literals(compressed)['decoded_bytes'])
        with patch.object(target, 'DECODED_BYTES', 16):
            with self.assertRaisesRegex(ValueError, 'decoded byte bound'):
                target.decoded_literals(gzip.compress(b'x' * 1_000_000))
        with self.assertRaisesRegex(ValueError, 'decoded bytes or SHA-256'):
            target.decoded_literals(gzip.compress(b'x' * target.DECODED_BYTES))
        for changed in (compressed[:-1], compressed + b'trailing', compressed + gzip.compress(b'')):
            with self.subTest(length=len(changed)), self.assertRaisesRegex(ValueError, 'Truncated, concatenated or trailing'):
                target.decoded_literals(changed)
        with self.assertRaisesRegex(ValueError, 'Invalid packaged encoder gzip'):
            target.decoded_literals(b'not gzip')

    def test_reviewed_source_constants_cannot_redefine_pins(self):
        for relative in (target.READER, target.STORE, *[
                'app/src/main/' + target.PREFIX + name for name in target.NOTICES]):
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target.ROOT / relative, destination)
        self.assertEqual(self.contract, target.source_contract(self.root))
        reader = self.root / target.READER
        original = reader.read_text()
        reader.write_text(original.replace(target.LITERALS_SHA256, '0' * 64))
        with self.assertRaisesRegex(ValueError, 'constant drift: LITERALS_SHA256'):
            target.source_contract(self.root)
        reader.write_text(original)
        store = self.root / target.STORE
        store.write_text(store.read_text().replace(target.LITERALS_NAME, 'structural-literals.bin.gz'))
        with self.assertRaisesRegex(ValueError, 'constant drift: LITERALS_ASSET_NAME'):
            target.source_contract(self.root)

    def test_both_apk_hashes_and_reviewed_sources_bound_across_consumer_retry(self):
        expected = self.pair()
        result = target.bind_report(self.root, dict(self.identity, GITHUB_RUN_ATTEMPT='3'))
        self.assertTrue(result['passed'])
        self.assertEqual(expected['apks'], result['apks'])
        self.assertEqual('1', result['workflow_identity']['GITHUB_RUN_ATTEMPT'])
        self.assertEqual(target.file_sha256(self.root / target.REPORT_NAME), result['preupload_report_sha256'])
        with zipfile.ZipFile(self.root / target.APK_NAMES[1], 'a') as archive:
            archive.writestr('unrelated.txt', 'APK changed after pre-upload validation')
        with self.assertRaisesRegex(ValueError, 'differ from final APKs/reviewed source'):
            target.bind_report(self.root, self.identity)

    def test_missing_failed_or_tampered_preupload_report_fails(self):
        original = self.pair()
        path = self.root / target.REPORT_NAME
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing, unsafe or oversized'):
            target.bind_report(self.root, self.identity)
        mutations = []
        for key, value in [('passed', False), ('apks', original['apks'][:1]),
                           ('reviewed_contract', {})]:
            changed = copy.deepcopy(original); changed[key] = value; mutations.append(changed)
        changed = copy.deepcopy(original); changed['apks'][0]['apk_sha256'] = '0' * 64; mutations.append(changed)
        for changed in mutations:
            path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError): target.bind_report(self.root, self.identity)

    def test_foreign_source_run_or_future_producer_report_fails(self):
        original = self.pair()
        for key, value in [('GITHUB_SHA', 'b' * 40), ('GITHUB_RUN_ID', '456'),
                           ('GITHUB_REPOSITORY', 'other/repository'), ('GITHUB_RUN_ATTEMPT', '4')]:
            changed = copy.deepcopy(original); changed['workflow_identity'][key] = value
            (self.root / target.REPORT_NAME).write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                target.bind_report(self.root, dict(self.identity, GITHUB_RUN_ATTEMPT='3'))

    def test_cli_failure_records_both_apk_hashes_and_returns_nonzero(self):
        first = self.pair()
        compact = self.root / target.APK_NAMES[1]
        del self.entries[target.PREFIX + target.LITERALS_NAME]
        self.apk = compact; self.write()
        out = self.root / 'failed.json'
        with patch.object(sys, 'argv', ['verify', '--apk', str(self.root / target.APK_NAMES[0]),
                '--apk', str(compact), '--out', str(out)]):
            self.assertEqual(1, target.main())
        result = json.loads(out.read_text())
        self.assertFalse(result['passed']); self.assertEqual(2, len(result['apks']))
        self.assertTrue(result['apks'][0]['passed']); self.assertFalse(result['apks'][1]['passed'])
        self.assertEqual(target.file_sha256(compact), result['apks'][1]['apk_sha256'])
        self.assertEqual(first['apks'][0]['apk_sha256'], result['apks'][0]['apk_sha256'])


if __name__ == '__main__':
    unittest.main()
