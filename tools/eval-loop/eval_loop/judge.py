"""Pluggable output-quality judge boundary."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .backend import Generation
from .transcript import Transcript


@dataclass(frozen=True)
class JudgeResult:
    score: float
    rationale: str = ""


class Judge(Protocol):
    def score(self, transcript: Transcript, output: Generation, variant: str) -> JudgeResult: ...


class FakeJudge:
    """A reproducible judge driven by expected fixture outputs or explicit scores."""

    def score(self, transcript: Transcript, output: Generation, variant: str) -> JudgeResult:
        scores = transcript.metadata.get("fake_judge_scores", {})
        if isinstance(scores, dict) and variant in scores:
            score = float(scores[variant])
        else:
            expected = transcript.metadata.get("expected")
            score = 1.0 if isinstance(expected, str) and output.text.strip() == expected.strip() else 0.5
        return JudgeResult(max(0.0, min(1.0, score)), "deterministic fixture score")
