import tempfile
import unittest
from pathlib import Path
import zipfile
from check_compact_apk import check


class CompactApkTest(unittest.TestCase):
    def test_rejects_changed_runtime_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            normal, compact = root / 'normal.apk', root / 'compact.apk'
            def write(path, payload, compression):
                with zipfile.ZipFile(path, 'w', compression=compression) as archive:
                    archive.writestr('lib/arm64-v8a/runtime.so', payload)
                    archive.writestr('classes.dex', b'dex')
            write(normal, b'native' * 100, zipfile.ZIP_STORED)
            write(compact, b'native' * 100, zipfile.ZIP_DEFLATED)
            check(normal, compact)
            write(compact, b'changed', zipfile.ZIP_DEFLATED)
            with self.assertRaises(AssertionError):
                check(normal, compact)
