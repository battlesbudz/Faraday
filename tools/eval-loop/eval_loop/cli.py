"""Command-line entry point for offline paired replays."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .backend import FakeModelBackend
from .checkers import CheckerSuite
from .judge import FakeJudge
from .ledger import PromotionLedger
from .runner import EvaluationRunner
from .transcript import load_jsonl


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Replay transcript JSONL against fake baseline and variant outputs")
    parser.add_argument("transcripts", type=Path)
    parser.add_argument("--baseline", help="override the ledger's current shadow baseline")
    parser.add_argument("--variant", required=True)
    parser.add_argument("--ledger", type=Path, default=Path("eval-ledger.json"))
    parser.add_argument("--review-file", type=Path)
    parser.add_argument("--proposed-change", default="")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)

    ledger = PromotionLedger(args.ledger, args.review_file, initial_baseline=args.baseline or "base")
    baseline = args.baseline or ledger.shadow_baseline
    transcripts = load_jsonl(args.transcripts)
    report = EvaluationRunner(FakeModelBackend(), CheckerSuite(), FakeJudge()).run(transcripts, baseline, args.variant)
    ledger_result = ledger.record(report, args.proposed_change)
    payload = {"evaluation": report.to_dict(), "ledger": ledger_result,
               "shadow_baseline": ledger.shadow_baseline}
    rendered = json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=True)
    print(rendered)
    if args.report:
        args.report.write_text(rendered + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
