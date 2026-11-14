from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from eval_loop.backend import Generation, ScriptedBackend
from eval_loop.checkers import CheckerSuite
from eval_loop.judge import FakeJudge
from eval_loop.ledger import PromotionLedger
from eval_loop.runner import EvaluationRunner
from eval_loop.transcript import Transcript, Turn, load_jsonl


ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples" / "synthetic.jsonl"


def transcript(identifier="t", user="What is 2 + 2?", previous="", **metadata):
    turns = [Turn("user", user)]
    if previous:
        turns.append(Turn("assistant", previous))
    return Transcript(identifier, tuple(turns), metadata)


class CheckerTests(unittest.TestCase):
    def setUp(self):
        self.checker = CheckerSuite({"lookup": {"type": "object", "required": ["query"],
                                                  "properties": {"query": {"type": "string"}}}})

    def test_answerable_refusal_fires_and_clear_answer_passes(self):
        item = transcript(answerable=True)
        self.assertFalse(self.checker.check(item, Generation("I can't answer that.")).signals["answerable_refusal"])
        self.assertTrue(self.checker.check(item, Generation("4")).signals["answerable_refusal"])

    def test_perception_claim_requires_input_modality(self):
        plain = transcript(user="Say hello")
        image = transcript("image", "Describe this picture", input_modalities=["image"])
        self.assertFalse(self.checker.check(plain, Generation("I can see a cat.")).signals["unsupported_perception"])
        self.assertTrue(self.checker.check(image, Generation("I can see a cat.")).signals["unsupported_perception"])

    def test_repeated_answer_fires_and_new_answer_passes(self):
        item = transcript(previous="The answer is four.")
        self.assertFalse(self.checker.check(item, Generation("The answer is FOUR!")).signals["repeated_previous_answer"])
        self.assertTrue(self.checker.check(item, Generation("It equals 4.")).signals["repeated_previous_answer"])

    def test_tool_schema_validity_fires_and_passes(self):
        item = transcript()
        good = Generation("Searching", ({"name": "lookup", "arguments": {"query": "weather"}},))
        bad = Generation("Searching", ({"name": "lookup", "arguments": {"query": 42}},))
        self.assertTrue(self.checker.check(item, good).signals["tool_call_validity"])
        self.assertFalse(self.checker.check(item, bad).signals["tool_call_validity"])
        self.assertTrue(self.checker.check(item, Generation("No call")).signals["tool_call_validity"])


class LedgerTests(unittest.TestCase):
    def make_report(self, shadow=True, judge=True):
        return type("Report", (), {"baseline": "shadow", "variant": "candidate",
            "shadow_win": shadow and judge, "judge_only_win": judge and not shadow,
            "checker_improved": shadow, "checker_regressed": False,
            "baseline_judge_average": 0.3, "variant_judge_average": 0.8,
            "to_dict": lambda self: {"example": "behavior"}})()

    def test_promotion_requires_three_wins_and_resets_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = PromotionLedger(Path(directory) / "ledger.json", initial_baseline="shadow")
            self.assertFalse(ledger.record(self.make_report())["promoted"])
            self.assertFalse(ledger.record(self.make_report())["promoted"])
            self.assertEqual("shadow", ledger.shadow_baseline)
            self.assertTrue(ledger.record(self.make_report())["promoted"])
            self.assertEqual("candidate", ledger.shadow_baseline)
            self.assertEqual("candidate", PromotionLedger(Path(directory) / "ledger.json").shadow_baseline)

    def test_judge_only_win_goes_to_review_file_and_does_not_promote(self):
        with tempfile.TemporaryDirectory() as directory:
            review = Path(directory) / "review.jsonl"
            ledger = PromotionLedger(Path(directory) / "ledger.json", review, "shadow")
            result = ledger.record(self.make_report(shadow=False, judge=True), "Change prompt wording")
            self.assertFalse(result["promoted"])
            self.assertEqual("shadow", ledger.shadow_baseline)
            record = json.loads(review.read_text(encoding="utf-8"))
            self.assertEqual("Change prompt wording", record["proposed_change"])
            self.assertIn("how_variant_behaved", record)


class ReplayTests(unittest.TestCase):
    def test_jsonl_loader_examples_and_end_to_end_shadow_win(self):
        transcripts = load_jsonl(EXAMPLES)
        self.assertEqual(3, len(transcripts))
        backend = ScriptedBackend({
            ("base", "case"): "I can't answer that.",
            ("candidate", "case"): "The answer is four.",
        })
        item = transcript("case", answerable=True, expected="The answer is four.")
        report = EvaluationRunner(backend, CheckerSuite(), FakeJudge()).run([item], "base", "candidate")
        self.assertTrue(report.checker_improved)
        self.assertTrue(report.judge_improved)
        self.assertTrue(report.shadow_win)

    def test_jsonl_loader_rejects_duplicate_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.jsonl"
            payload = {"id": "dup", "turns": [{"role": "user", "text": "x"}]}
            path.write_text(json.dumps(payload) + "\n" + json.dumps(payload) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate transcript id"):
                load_jsonl(path)


if __name__ == "__main__":
    unittest.main()
