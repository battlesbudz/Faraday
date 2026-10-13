import hashlib
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
import warnings
import zipfile

import verify_packaged_samplers as target


class PackagedSamplerContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payloads = {name: ('reviewed-' + name).encode() for name in target.OUTPUTS}
        self.pins = {name: target.sha256(data) for name, data in self.payloads.items()}
        override = patch.object(target, 'OUTPUTS', self.pins)
        override.start(); self.addCleanup(override.stop)
        self.notice = (target.HERE / 'SAMPLER-DEPENDENCY-NOTICE.md').read_bytes()
        self.producer = {'aar_sha256': 'a' * 64, 'source': {'workflow_identity': {'GITHUB_RUN_ID': '123'}},
                         'native_sha256': self.pins,
                         'sampler_dependency_derivations': {n: {'output_sha256': h} for n, h in self.pins.items()},
                         'sampler_modifications_notice_sha256': target.sha256(self.notice)}
        self.provenance = self.root / 'producer.json'
        self.provenance.write_text(json.dumps(self.producer))
        self.expected = target.sha256(self.provenance.read_bytes())
        self.entries = {target.PROVENANCE_ASSET: json.dumps({k: v for k, v in self.producer.items() if k != 'aar_sha256'}).encode(),
                        target.NOTICE_ASSET: self.notice,
                        **{'lib/arm64-v8a/' + n: b for n, b in self.payloads.items()}}
        self.apk = self.root / 'candidate.apk'

    def write(self, compression=zipfile.ZIP_STORED, extra=()):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w', compression) as z:
                for name, data in [*self.entries.items(), *extra]: z.writestr(name, data)

    def verify(self):
        return target.verify(self.apk, self.provenance, self.expected)

    def test_both_compression_modes_preserve_exact_binary_identity(self):
        for compression in [zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED]:
            with self.subTest(compression=compression):
                self.write(compression)
                self.assertTrue(self.verify()['passed'])

    def test_post_sdk_binary_rewrite_is_rejected(self):
        name = next(iter(self.payloads))
        self.entries['lib/arm64-v8a/' + name] += b'stripped'
        self.write()
        with self.assertRaisesRegex(ValueError, 'bytes changed after SDK'): self.verify()

    def test_changed_or_missing_notice_is_rejected(self):
        self.entries[target.NOTICE_ASSET] += b'changed'; self.write()
        with self.assertRaisesRegex(ValueError, 'notice missing or changed'): self.verify()
        del self.entries[target.NOTICE_ASSET]; self.write()
        with self.assertRaises(KeyError): self.verify()

    def test_duplicate_zip_entries_are_rejected(self):
        self.write(extra=[(target.NOTICE_ASSET, self.notice)])
        with self.assertRaisesRegex(ValueError, 'Duplicate'): self.verify()

    def test_producer_hash_or_embedded_receipt_drift_is_rejected(self):
        self.write(); self.expected = '0' * 64
        with self.assertRaisesRegex(ValueError, 'digest mismatch'): self.verify()
        self.expected = target.sha256(self.provenance.read_bytes())
        self.entries[target.PROVENANCE_ASSET] = b'{}'; self.write()
        with self.assertRaisesRegex(ValueError, 'exact SDK producer'): self.verify()

    def test_foreign_abi_missing_sampler_or_path_escape_is_rejected(self):
        name = next(iter(self.payloads)); original = dict(self.entries)
        for replacement in [None, 'lib/x86_64/' + name, '../' + name]:
            with self.subTest(replacement=replacement):
                self.entries = dict(original); data = self.entries.pop('lib/arm64-v8a/' + name)
                if replacement: self.entries[replacement] = data
                self.write()
                with self.assertRaisesRegex(ValueError, 'ABI/path'): self.verify()

    def test_unreviewed_producer_cannot_redefine_expected_binary(self):
        self.producer['native_sha256'] = {n: '0' * 64 for n in self.pins}
        self.provenance.write_text(json.dumps(self.producer)); self.expected = target.sha256(self.provenance.read_bytes())
        self.write()
        with self.assertRaisesRegex(ValueError, 'reviewed derivation'): self.verify()

    def test_entry_read_is_bounded_before_decompression(self):
        self.write()
        with zipfile.ZipFile(self.apk) as z:
            with self.assertRaisesRegex(ValueError, 'Oversized'):
                target.bounded_entry(z, target.NOTICE_ASSET, 1)

    def test_malformed_producer_maps_raise_value_error(self):
        self.write()
        cases = [[], {**self.producer, 'sampler_dependency_derivations': []},
                 {**self.producer, 'native_sha256': []},
                 {**self.producer, 'sampler_dependency_derivations': {name: [] for name in self.pins}}]
        for producer in cases:
            with self.subTest(producer=producer):
                self.provenance.write_text(json.dumps(producer))
                self.expected = target.sha256(self.provenance.read_bytes())
                with self.assertRaises(ValueError): self.verify()

    def test_cli_writes_failed_receipt_for_malformed_producer(self):
        self.write(); self.provenance.write_text('[]')
        self.expected = target.sha256(self.provenance.read_bytes())
        out = self.root / 'failed.json'
        with patch.object(sys, 'argv', ['verify', '--apk', str(self.apk),
                '--provenance', str(self.provenance), '--expected-provenance-sha256', self.expected,
                '--out', str(out)]):
            with self.assertRaises(SystemExit): target.main()
        report = json.loads(out.read_text())
        self.assertFalse(report['passed']); self.assertIn('must be an object', report['error'])

    def test_second_apk_failure_retains_first_result_but_fails_aggregate(self):
        self.write(); first = self.root / 'first.apk'; first.write_bytes(self.apk.read_bytes())
        name = next(iter(self.payloads)); self.entries['lib/arm64-v8a/' + name] += b'changed'; self.write()
        out = self.root / 'partial.json'
        with patch.object(sys, 'argv', ['verify', '--apk', str(first), '--apk', str(self.apk),
                '--provenance', str(self.provenance), '--expected-provenance-sha256', self.expected,
                '--out', str(out)]):
            with self.assertRaises(SystemExit): target.main()
        report = json.loads(out.read_text())
        self.assertFalse(report['passed']); self.assertEqual(1, len(report['apks']))
        self.assertTrue(report['apks'][0]['passed']); self.assertIn('bytes changed', report['error'])


if __name__ == '__main__': unittest.main()
