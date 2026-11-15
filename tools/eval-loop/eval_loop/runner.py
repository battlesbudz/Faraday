"""Paired replay of baseline and candidate variants."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Sequence

from .backend import Generation, ModelBackend
from .checkers import CheckerSuite
from .judge import Judge
from .transcript import Transcript


@dataclass(frozen=True)
class ReplayItem:
    transcript_id: str
    baseline_output: str
    variant_output: str
    baseline_signals: dict[str, bool]
    variant_signals: dict[str, bool]
    baseline_judge: float
    variant_judge: float

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ReplayReport:
    baseline: str
    variant: str
    items: tuple[ReplayItem, ...]
    checker_improved: bool
    checker_regressed: bool
    baseline_violations: int
    variant_violations: int
    baseline_judge_average: float
    variant_judge_average: float
    judge_improved: bool

    @property
    def shadow_win(self) -> bool:
        return self.checker_improved and not self.checker_regressed and self.judge_improved

    @property
    def judge_only_win(self) -> bool:
        return self.judge_improved and not self.checker_improved

    def to_dict(self) -> dict[str, Any]:
        return {"baseline": self.baseline, "variant": self.variant,
                "items": [item.to_dict() for item in self.items],
                "checker_improved": self.checker_improved, "checker_regressed": self.checker_regressed,
                "baseline_violations": self.baseline_violations, "variant_violations": self.variant_violations,
                "baseline_judge_average": self.baseline_judge_average,
                "variant_judge_average": self.variant_judge_average, "judge_improved": self.judge_improved,
                "shadow_win": self.shadow_win, "judge_only_win": self.judge_only_win}


class EvaluationRunner:
    def __init__(self, backend: ModelBackend, checker: CheckerSuite, judge: Judge):
        self.backend, self.checker, self.judge = backend, checker, judge

    def run(self, transcripts: Sequence[Transcript], baseline: str, variant: str) -> ReplayReport:
        if not transcripts:
            raise ValueError("evaluation needs at least one transcript")
        items: list[ReplayItem] = []
        baseline_violations = variant_violations = 0
        strict_improvement = regression = False
        baseline_scores: list[float] = []
        variant_scores: list[float] = []
        for transcript in transcripts:
            base = self.backend.generate(transcript, baseline)
            candidate = self.backend.generate(transcript, variant)
            base_checks = self.checker.check(transcript, base)
            candidate_checks = self.checker.check(transcript, candidate)
            baseline_violations += base_checks.violations
            variant_violations += candidate_checks.violations
            for signal, passed in base_checks.signals.items():
                candidate_passed = candidate_checks.signals[signal]
                regression |= passed and not candidate_passed
                strict_improvement |= not passed and candidate_passed
            base_judge = self.judge.score(transcript, base, baseline).score
            candidate_judge = self.judge.score(transcript, candidate, variant).score
            baseline_scores.append(base_judge)
            variant_scores.append(candidate_judge)
            items.append(ReplayItem(transcript.id, base.text, candidate.text, base_checks.signals,
                                    candidate_checks.signals, base_judge, candidate_judge))
        base_average = sum(baseline_scores) / len(baseline_scores)
        variant_average = sum(variant_scores) / len(variant_scores)
        return ReplayReport(baseline, variant, tuple(items), strict_improvement, regression,
                            baseline_violations, variant_violations, base_average, variant_average,
                            variant_average > base_average)
