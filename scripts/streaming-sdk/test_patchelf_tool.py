import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from build_android_sdk import prepare_patchelf


class PinnedToolExtraction(unittest.TestCase):
    def reject(self, members, reason):
        def fake_download(_pin, destination):
            with tarfile.open(destination, 'w:gz') as archive:
                for name, kind, data in members:
                    item = tarfile.TarInfo(name)
                    item.type = kind
                    item.size = len(data) if kind == tarfile.REGTYPE else 0
                    if kind == tarfile.SYMTYPE:
                        item.linkname = '/unapproved/tool'
                    archive.addfile(item, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch('build_android_sdk.download', side_effect=fake_download):
                with self.assertRaisesRegex(ValueError, reason):
                    prepare_patchelf(root)
            self.assertFalse((root/'patchelf').exists())

    def test_path_traversal_is_not_the_pinned_member(self):
        self.reject([('../../bin/patchelf', tarfile.REGTYPE, b'x')], 'unexpected binary member')

    def test_duplicate_normalized_member_is_rejected(self):
        self.reject([('bin/patchelf', tarfile.REGTYPE, b'x'),
                     ('./bin/patchelf', tarfile.REGTYPE, b'x')], 'unexpected binary member')

    def test_symlink_cannot_be_executed(self):
        self.reject([('bin/patchelf', tarfile.SYMTYPE, b'')], 'unexpected binary member')

    def test_wrong_size_is_rejected_before_writing_executable(self):
        self.reject([('bin/patchelf', tarfile.REGTYPE, b'x')], 'unexpected binary member')

    def test_right_size_wrong_bytes_are_rejected(self):
        self.reject([('bin/patchelf', tarfile.REGTYPE, b'x'*1202328)], 'binary mismatch')


if __name__ == '__main__':
    unittest.main()
