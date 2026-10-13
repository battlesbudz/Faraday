import json
from pathlib import Path
import shutil
import tempfile
import unittest
import check_launcher_icons as icons


class LauncherIconsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(icons.ROOT / 'artwork/launcher', self.root / 'artwork/launcher')
        for p in (icons.ROOT / 'app/src').glob('*/AndroidManifest.xml'):
            dest = self.root / p.relative_to(icons.ROOT)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(p, dest)
        for p in (icons.ROOT / 'app/src/main/res').glob('mipmap-*'):
            shutil.copytree(p, self.root / p.relative_to(icons.ROOT))

    def test_shared_resources_are_valid(self):
        icons.check(self.root)

    def test_missing_density_fails(self):
        (self.root / 'app/src/main/res/mipmap-mdpi/ic_launcher.png').unlink()
        with self.assertRaises(FileNotFoundError):
            icons.check(self.root)

    def test_stale_source_fails(self):
        p = self.root / 'artwork/launcher/faraday-wisp.svg'
        p.write_text(p.read_text() + '\n')
        with self.assertRaisesRegex(ValueError, 'Stale'):
            icons.check(self.root)

    def test_variant_override_fails(self):
        p = self.root / 'app/src/debug/res/mipmap-mdpi/ic_launcher.png'
        p.parent.mkdir(parents=True)
        p.write_bytes(b'override')
        with self.assertRaisesRegex(ValueError, 'override'):
            icons.check(self.root)

    def test_themed_layer_mismatch_fails(self):
        p = self.root / 'app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml'
        p.write_text(p.read_text().replace('ic_launcher_monochrome', 'ic_launcher_foreground'))
        with self.assertRaisesRegex(ValueError, 'Wrong adaptive'):
            icons.check(self.root)

    def test_manifest_override_fails(self):
        p = self.root / 'app/src/debug/AndroidManifest.xml'
        p.parent.mkdir(parents=True)
        p.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:icon="@drawable/wrong"/></manifest>')
        with self.assertRaisesRegex(ValueError, 'override'):
            icons.check(self.root)


if __name__ == '__main__':
    unittest.main()
