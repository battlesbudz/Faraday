"""Developer entrypoint regressions: coverage and failures survive discovery boundaries."""
from contextlib import redirect_stderr, redirect_stdout
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import dev


class DeveloperEntrypointTest(unittest.TestCase):
    def run_fixture(self, failing_suite, invalid_boundary=False):
        actual_run = subprocess.run
        calls = []
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / dev.check_architecture.SOURCE / "voice" / "Stage.kt"
            source.parent.mkdir(parents=True)
            source.write_text("val runtime: JarvisRuntime" if invalid_boundary else "class Stage", encoding="utf-8")
            for directory in dev.PYTHON_SUITES:
                folder = root / directory
                folder.mkdir(parents=True, exist_ok=True)
                assertion = "self.fail('injected regression')" if directory == failing_suite else "pass"
                (folder / "test_fixture.py").write_text(
                    "import unittest\nclass Fixture(unittest.TestCase):\n"
                    f"    def test_gate(self): {assertion}\n", encoding="utf-8")

            def invoke(command, cwd):
                result = actual_run(command, cwd=cwd, capture_output=True, text=True)
                calls.append((command, cwd, result))
                return result

            with patch("dev.subprocess.run", side_effect=invoke), redirect_stdout(io.StringIO()):
                status = dev.run_checks(root)
            return status, calls, root

    def test_failure_in_either_suite_is_retained_and_both_suites_run(self):
        for suite in dev.PYTHON_SUITES:
            with self.subTest(suite=suite):
                status, calls, root = self.run_fixture(suite)
                self.assertNotEqual(0, status)
                self.assertEqual(2, len(calls))
                self.assertTrue(all(cwd == root for _, cwd, _ in calls))
                self.assertTrue(all("Ran 1 test" in result.stderr for _, _, result in calls))
                self.assertEqual([0, 1], sorted(result.returncode for _, _, result in calls))

    def test_unavailable_interpreter_does_not_report_checks_passed(self):
        errors = io.StringIO()
        with patch("dev.subprocess.run", side_effect=OSError("interpreter unavailable")), \
                redirect_stdout(io.StringIO()), redirect_stderr(errors):
            self.assertEqual(1, dev.run_checks(dev.ROOT))
        self.assertIn("interpreter unavailable", errors.getvalue())

    def test_boundary_failure_survives_successful_suites_and_both_suites_run(self):
        with redirect_stderr(io.StringIO()):
            status, calls, _ = self.run_fixture(None, invalid_boundary=True)
        self.assertEqual(1, status)
        self.assertEqual(2, len(calls))
        self.assertTrue(all(result.returncode == 0 for _, _, result in calls))

    def test_cli_resolves_repo_from_unrelated_working_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(dev.__file__)), "map", "ConversationRuntime"],
                                    cwd=directory, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("app/src/main/", result.stdout)
        self.assertIn("ConversationRuntime.kt", result.stdout)

    def test_doctor_reports_signing_presence_without_secret_values(self):
        output = io.StringIO()
        secret = "fixture-signing-secret-do-not-display"
        with patch.dict(os.environ, {key: secret for key in dev.SIGNING_KEYS}), \
                patch("dev.shutil.which", return_value=None), redirect_stdout(output):
            self.assertEqual(0, dev.doctor(dev.ROOT))
        self.assertIn("gradle: missing", output.getvalue())
        self.assertIn("ANDROID_KEYSTORE_PASSWORD: set", output.getvalue())
        self.assertNotIn(secret, output.getvalue())


if __name__ == "__main__":
    unittest.main()
