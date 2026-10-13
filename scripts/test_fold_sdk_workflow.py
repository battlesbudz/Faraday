"""Execute the workflow's pinned SDK handoff with disposable SDK catalogs."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-sandbox.yml'


def workflow_step(name):
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index(f'      - name: {name}')
    end = next((index for index in range(start + 1, len(lines))
                if lines[index].startswith('      - ')), len(lines))
    return '\n'.join(lines[start:end])


class FoldSdkWorkflowTest(unittest.TestCase):
    def setUp(self):
        step = workflow_step('Select the pinned catalog for the emulator runner')
        self.script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        installer = workflow_step('Install the pinned Pixel Fold device catalog')
        self.build = re.search(r"cmdline-tools-version: '([0-9]+)'", installer).group(1)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / 'sdk'
        self.runner = self.root / 'runner'
        self.runner.mkdir()
        self.github_path = self.root / 'github-path'
        self.env = dict(os.environ, ANDROID_HOME=str(self.sdk), RUNNER_TEMP=str(self.runner),
                        GITHUB_PATH=str(self.github_path), PATH='/usr/bin:/bin')
        self.latest = self.sdk / 'cmdline-tools/latest'
        self.backup = self.runner / 'jarvis-fold-preinstalled-cmdline-tools'
        self.receipt = self.runner / 'jarvis-fold-display'

    def catalog(self, name, revision, device_id, exit_code=0):
        tools = self.sdk / 'cmdline-tools' / name
        (tools / 'bin').mkdir(parents=True)
        (tools / 'source.properties').write_text(f'Pkg.Revision={revision}\n')
        manager = tools / 'bin/avdmanager'
        listing = f'id: 1 or "{device_id}"\n'
        manager.write_text('#!/bin/bash\n'
                           '[ "$1 $2" = "list device" ] || exit 88\n'
                           f'printf %s {shlex.quote(listing)}\nexit {exit_code}\n')
        manager.chmod(0o755)
        return tools

    def activate(self):
        return subprocess.run(['bash', '-c', self.script], env=self.env,
                              text=True, capture_output=True, timeout=10)

    def test_runner_latest_path_uses_exact_installed_build_and_preserves_catalog_receipts(self):
        self.catalog('latest', '12.0', 'pixel_2')
        pinned = self.catalog(self.build, '23.0', 'pixel_fold')
        result = self.activate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(pinned.exists())
        self.assertEqual('Pkg.Revision=12.0\n', (self.backup / 'source.properties').read_text())
        self.assertEqual('Pkg.Revision=23.0\n', (self.latest / 'source.properties').read_text())
        self.assertEqual(str(self.latest / 'bin') + '\n', self.github_path.read_text())
        self.assertEqual('Pkg.Revision=23.0\n', (self.receipt / 'pinned-sdk-source.properties').read_text())
        self.assertIn('"pixel_fold"', (self.receipt / 'hardware-profiles.txt').read_text())
        # Match emulator-runner's latest/bin precedence rather than relying on
        # setup-android's earlier versioned PATH entry.
        runner_env = dict(self.env, PATH=f'{self.latest}:{self.latest / "bin"}:{pinned / "bin"}:/usr/bin:/bin')
        selected = subprocess.run(['bash', '-c', 'command -v avdmanager; avdmanager list device'],
                                  env=runner_env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, selected.returncode, selected.stderr)
        self.assertEqual(str(self.latest / 'bin/avdmanager'), selected.stdout.splitlines()[0])
        self.assertIn('"pixel_fold"', selected.stdout)

    def test_fresh_sdk_without_hosted_latest_is_supported(self):
        self.catalog(self.build, '23.0', 'pixel_fold')
        result = self.activate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.latest.is_dir())
        self.assertFalse(self.backup.exists())

    def test_missing_or_wrong_revision_fails_before_replacing_hosted_catalog(self):
        for revision in (None, '16.0'):
            with self.subTest(revision=revision), tempfile.TemporaryDirectory() as directory:
                self.sdk = Path(directory) / 'sdk'
                self.env['ANDROID_HOME'] = str(self.sdk)
                old = self.catalog('latest', '12.0', 'pixel_2')
                if revision is not None:
                    self.catalog(self.build, revision, 'pixel_fold')
                result = self.activate()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('Pkg.Revision=12.0\n', (old / 'source.properties').read_text())
                self.assertFalse(self.backup.exists())

    def test_absent_fold_profile_or_failed_avdmanager_cannot_pass_catalog_gate(self):
        for device_id, exit_code in (('pixel_2', 0), ('pixel_fold', 9)):
            with self.subTest(device_id=device_id, exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                self.sdk = Path(directory) / 'sdk'
                self.env['ANDROID_HOME'] = str(self.sdk)
                self.catalog(self.build, '23.0', device_id, exit_code)
                result = self.activate()
                self.assertNotEqual(0, result.returncode)
                self.assertTrue((self.receipt / 'hardware-profiles.txt').is_file())

    def test_existing_backup_fails_before_moving_either_catalog(self):
        self.catalog('latest', '12.0', 'pixel_2')
        pinned = self.catalog(self.build, '23.0', 'pixel_fold')
        self.backup.mkdir()
        result = self.activate()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(pinned.is_dir())
        self.assertEqual('Pkg.Revision=12.0\n', (self.latest / 'source.properties').read_text())


if __name__ == '__main__':
    unittest.main()
