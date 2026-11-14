"""Persistent shadow-promotion ledger and judge-only review queue."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .runner import ReplayReport


class PromotionLedger:
    def __init__(self, path: str | Path, review_path: str | Path | None = None, initial_baseline: str = "base"):
        self.path = Path(path)
        self.review_path = Path(review_path) if review_path else self.path.with_name("judge-only-review.jsonl")
        self.initial_baseline = initial_baseline
        self.state = self._load()

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"shadow_baseline": self.initial_baseline, "consecutive_wins": 0,
                    "candidate": None, "history": []}
        state = json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(state, dict) or not isinstance(state.get("history"), list):
            raise ValueError(f"invalid promotion ledger: {self.path}")
        return state

    @property
    def shadow_baseline(self) -> str:
        return self.state["shadow_baseline"]

    def record(self, report: ReplayReport, proposed_change: str = "") -> dict[str, Any]:
        if report.baseline != self.shadow_baseline:
            raise ValueError(f"comparison baseline {report.baseline!r} does not match shadow baseline {self.shadow_baseline!r}")
        entry = {"baseline": report.baseline, "variant": report.variant,
                 "shadow_win": report.shadow_win, "judge_only_win": report.judge_only_win,
                 "checker_improved": report.checker_improved, "checker_regressed": report.checker_regressed,
                 "baseline_judge_average": report.baseline_judge_average,
                 "variant_judge_average": report.variant_judge_average}
        if report.shadow_win:
            if self.state.get("candidate") == report.variant:
                self.state["consecutive_wins"] += 1
            else:
                self.state["candidate"] = report.variant
                self.state["consecutive_wins"] = 1
        else:
            self.state["candidate"] = None
            self.state["consecutive_wins"] = 0

        promoted = False
        if self.state["consecutive_wins"] >= 3:
            self.state["shadow_baseline"] = report.variant
            self.state["candidate"] = None
            self.state["consecutive_wins"] = 0
            promoted = True
        entry["promoted"] = promoted
        entry["consecutive_wins"] = self.state["consecutive_wins"] if not promoted else 3
        self.state["history"].append(entry)
        if report.judge_only_win:
            self.review_path.parent.mkdir(parents=True, exist_ok=True)
            review = {"proposed_change": proposed_change, "baseline": report.baseline,
                      "variant": report.variant, "how_variant_behaved": report.to_dict()}
            with self.review_path.open("a", encoding="utf-8") as target:
                target.write(json.dumps(review, ensure_ascii=False, sort_keys=True) + "\n")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return entry
