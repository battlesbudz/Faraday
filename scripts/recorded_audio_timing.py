#!/usr/bin/env python3
"""Warning-only timing trend for the recorded-speech job.

Compares each check's host Whisper `elapsed_ms` in `report.json` with a stored
baseline and writes a Markdown table to the job summary. Shared-runner noise is
not yet measured, so this never fails the job; the quality gates stay in
`check_recorded_audio.py`. It is Linux CPU timing, not the Android turn path.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "scripts/verification/recorded_audio_timing_baseline.json"


def _ms(value) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value) if math.isfinite(value) and value > 0 else None


def compare(report: dict, baseline: dict) -> list[dict]:
    """One row per reported check; missing values stay None, never 0."""
    ratio = float(baseline.get("warn_slower_ratio", 0.2))
    stored = baseline.get("checks", {})
    rows = []
    for item in report.get("checks", []):
        name = item.get("name", "?")
        elapsed = _ms(item.get("elapsed_ms"))
        base = _ms(stored.get(name, {}).get("elapsed_ms"))
        row = {"name": name, "elapsed_ms": elapsed, "baseline_ms": base, "delta_ms": None, "delta_pct": None}
        if elapsed is None:
            row["verdict"] = "no timing"
        elif base is None:
            row["verdict"] = "no baseline"
        else:
            row["delta_ms"] = round(elapsed - base, 3)
            row["delta_pct"] = round((elapsed - base) / base * 100, 1)
            if elapsed > base * (1 + ratio):
                row["verdict"] = "slower"
            elif elapsed < base:
                row["verdict"] = "faster"
            else:
                row["verdict"] = "within"
        rows.append(row)
    return rows


def _cell(value, suffix="") -> str:
    return "not recorded" if value is None else f"{value:g}{suffix}"


def render(rows: list[dict], baseline: dict, status: str) -> str:
    ratio = float(baseline.get("warn_slower_ratio", 0.2))
    lines = ["### Recorded speech timing (host Whisper, warning only)", "",
             f"Gate status: {status}. Baseline: {baseline.get('source', 'unknown')}. "
             f"Warns when more than {ratio * 100:g}% slower; never fails the job.", ""]
    if not rows:
        return "\n".join(lines + ["No recorded-speech checks were reported.", ""])
    lines += ["| Check | elapsed ms | baseline ms | delta ms | delta % | verdict |",
              "| --- | --- | --- | --- | --- | --- |"]
    for row in rows:
        lines.append(f"| {row['name']} | {_cell(row['elapsed_ms'])} | {_cell(row['baseline_ms'])} | "
                     f"{_cell(row['delta_ms'])} | {_cell(row['delta_pct'], '%')} | {row['verdict']} |")
    return "\n".join(lines + [""])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args(argv)
    try:
        baseline = json.loads(args.baseline.read_text())
    except (OSError, ValueError) as error:
        baseline = {"source": f"baseline unreadable ({type(error).__name__})", "checks": {}}
    try:
        report = json.loads(args.report.read_text())
    except (OSError, ValueError) as error:
        report = {"status": f"report unreadable ({type(error).__name__})", "checks": []}
    rows = compare(report, baseline)
    text = render(rows, baseline, str(report.get("status", "unknown")))
    print(text)
    for row in rows:
        if row["verdict"] == "slower":
            print(f"::warning title=Recorded speech timing::{row['name']} took {row['elapsed_ms']:g} ms, "
                  f"{row['delta_pct']:g}% over the {row['baseline_ms']:g} ms baseline")
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps({"schema_version": 1, "rows": rows}, indent=2) + "\n")
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
