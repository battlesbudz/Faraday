"""Failure-injection checks: a broken runner or weakened gate must never become green."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verification.android import instrumentation_results

REPAIR = Path(__file__).with_name("verification") / "repair.py"


def transcript(code=0, footer="OK (1 test)"):
    return f"INSTRUMENTATION_STATUS: class=Suite\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: {code}\n{footer}\n"


class InstrumentationEvidenceTest(unittest.TestCase):
    def test_explicit_complete_pass(self):
        self.assertTrue(instrumentation_results(transcript(), ["case"], "Suite")["passed"])

    def test_failure_skip_crash_and_empty_never_pass(self):
        for output in ("", "OK (0 tests)", transcript(-2), transcript(-3),
                       transcript(footer="INSTRUMENTATION_FAILED: crash"), transcript() + "shortMsg=crashed",
                       transcript() + transcript()):
            with self.subTest(output=output):
                self.assertFalse(instrumentation_results(output, ["case"], "Suite")["passed"])

    def test_missing_or_wrong_test_is_not_coverage(self):
        self.assertFalse(instrumentation_results(transcript(), ["case", "missing"], "Suite")["passed"])
        self.assertFalse(instrumentation_results(transcript(), ["case"], "OtherSuite")["passed"])


class RepairLoopTest(unittest.TestCase):
    def run_fixture(self, repair, *, gate_exit=0, gate_report=True, gate_mutation="", limit=3):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "repo"
            root.mkdir()
            (root / "app.txt").write_text("broken")
            (root / "scripts").mkdir()
            (root / "scripts" / "acceptance.txt").write_text("must work")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                            "commit", "-qm", "fixture"], cwd=root, check=True)
            gate = base / "gate.py"
            gate.write_text("import os,json\nfrom pathlib import Path\n" + gate_mutation + "\n" +
                ("p=Path(os.environ['JARVIS_ATTEMPT_DIR'])/'device';p.mkdir()\n"
                 "(p/'report.json').write_text(json.dumps({'passed':Path('app.txt').read_text()=='fixed',"
                 "'source_commit':os.environ['JARVIS_SOURCE_COMMIT']}))\n" if gate_report else "") +
                f"raise SystemExit({gate_exit})\n")
            fixer = base / "fix.py"
            fixer.write_text("from pathlib import Path\n" + repair)
            out = base / "evidence"
            result = subprocess.run([sys.executable, str(REPAIR), "--repo", str(root), "--out", str(out),
                "--gate-command", json.dumps([sys.executable, str(gate)]),
                "--repair-command", json.dumps([sys.executable, str(fixer)]), "--max-attempts", str(limit)],
                capture_output=True, text=True)
            return result.returncode, json.loads((out / "report.json").read_text())

    def test_deliberate_bug_is_fixed_and_retested(self):
        code, report = self.run_fixture("Path('app.txt').write_text('fixed')")
        self.assertEqual(0, code)
        self.assertEqual(2, len(report["attempts"]))
        self.assertFalse(report["attempts"][0]["passed"])
        self.assertTrue(report["attempts"][1]["passed"])

    def test_changing_acceptance_stops_loop(self):
        code, report = self.run_fixture("Path('scripts/acceptance.txt').write_text('anything passes')")
        self.assertEqual(1, code)
        self.assertIn("protected", report["stop_reason"])

    def test_missing_evidence_and_no_progress_stop(self):
        code, report = self.run_fixture("pass", gate_report=False)
        self.assertEqual(1, code)
        self.assertIn("no source change", report["stop_reason"])

    def test_failed_gate_cannot_claim_success_and_attempts_are_bounded(self):
        code, report = self.run_fixture("p=Path('app.txt');p.write_text(p.read_text()+'x')", gate_exit=1, limit=2)
        self.assertEqual(1, code)
        self.assertEqual(2, len(report["attempts"]))

    def test_mutating_source_during_test_is_rejected(self):
        code, report = self.run_fixture("pass", gate_mutation="Path('app.txt').write_text('fixed')")
        self.assertEqual(1, code)
        self.assertIn("during verification", report["stop_reason"])


if __name__ == "__main__":
    unittest.main()
