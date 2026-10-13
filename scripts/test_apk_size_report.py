import tempfile
import unittest
from pathlib import Path
import zipfile

from apk_size_report import inspect, markdown


class ApkSizeReportTest(unittest.TestCase):
    def test_zip_bytes_and_payload_are_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sample.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('lib/arm64-v8a/sample.so', b'x' * 5000, compress_type=zipfile.ZIP_DEFLATED)
                archive.writestr('classes.dex', b'dex' * 10)
            report = inspect(path)
            self.assertEqual(5000, report['groups']['lib']['uncompressed_bytes'])
            self.assertLess(report['groups']['lib']['zip_bytes'], 5000)
            self.assertEqual(30, report['groups']['dex']['zip_bytes'])
            self.assertEqual(path.stat().st_size, sum(e['zip_bytes'] for e in report['entries']) + report['zip_overhead_bytes'])
            self.assertIn('Change from baseline: +0 bytes', markdown(report, report))
