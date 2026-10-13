#!/usr/bin/env python3
"""Bounded local build/test/repair orchestration. Commands are explicit argv arrays, never shell text."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def fingerprint(root, protected=False):
    result = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=root,
                            capture_output=True, check=True)
    digest = hashlib.sha256()
    for name in sorted(set(result.stdout.decode().split("\0")) - {""}):
        if protected and not (name.startswith(("scripts/", ".github/", ".agents/", "docs/verification/")) or
                              "/src/test/" in name or "/src/androidTest/" in name or name in ("AGENTS.md", ".gitignore", "gradle.properties") or
                              name.endswith((".gradle.kts", ".gradle", "proguard-rules.pro"))):
            continue
        path = root / name
        digest.update(name.encode() + b"\0")
        digest.update(path.read_bytes() if path.is_file() else b"<deleted>")
    return digest.hexdigest()


def command(argv, cwd, env, output, timeout):
    with output.open("wb") as log:
        process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            return process.wait(timeout=max(1, timeout))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            return 124


def run(args):
    root, out = Path(args.repo).resolve(), Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    deadline = time.monotonic() + args.budget_seconds
    initial_protected = fingerprint(root, protected=True)
    summary = {"passed": False, "attempts": [], "stop_reason": "attempt limit reached"}
    try:
        for index in range(1, args.max_attempts + 1):
            if time.monotonic() >= deadline:
                summary["stop_reason"] = "time budget exhausted"
                break
            attempt = out / f"attempt-{index}"
            attempt.mkdir()
            env = dict(os.environ, JARVIS_ATTEMPT_DIR=str(attempt))
            env["JARVIS_SOURCE_COMMIT"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            before = fingerprint(root)
            code = command(args.gate_command, root, env, attempt / "gate.log",
                           min(args.command_timeout, deadline - time.monotonic()))
            gate_path = attempt / args.report_relative
            try:
                gate = json.loads(gate_path.read_text())
            except (OSError, ValueError):
                gate = {}
            entry = {"attempt": index, "gate_exit": code, "source_digest": before,
                     "source_commit": env["JARVIS_SOURCE_COMMIT"], "evidence": str(gate_path), "passed": False}
            summary["attempts"].append(entry)
            if before != fingerprint(root):
                summary["stop_reason"] = "source changed during verification"
                break
            if code == 0 and gate.get("passed") is True and gate.get("source_commit") == env["JARVIS_SOURCE_COMMIT"]:
                entry["passed"] = summary["passed"] = True
                summary["stop_reason"] = "fresh verification passed; ready for human signoff"
                break
            if index == args.max_attempts or not args.repair_command:
                break
            if time.monotonic() >= deadline:
                summary["stop_reason"] = "time budget exhausted"
                break
            env["JARVIS_FAILURE_REPORT"] = str(attempt / "failure.json")
            Path(env["JARVIS_FAILURE_REPORT"]).write_text(json.dumps({"attempt": entry, "report": gate,
                "instructions": "Diagnose gate.log and evidence, repair application code, preserve acceptance tests. Do not publish or merge."}, indent=2))
            code = command(args.repair_command, root, env, attempt / "repair.log",
                           min(args.command_timeout, deadline - time.monotonic()))
            entry["repair_exit"] = code
            if fingerprint(root, protected=True) != initial_protected:
                summary["stop_reason"] = "repair changed protected verification files; review required"
                break
            if code:
                summary["stop_reason"] = "repair command failed or timed out"
                break
            if fingerprint(root) == before:
                summary["stop_reason"] = "repair made no source change"
                break
    except (OSError, subprocess.SubprocessError) as error:
        summary["stop_reason"] = str(error)
    (out / "report.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0 if summary["passed"] else 1


def argv_array(value):
    try:
        result = json.loads(value)
        if not isinstance(result, list) or not result or not all(isinstance(item, str) for item in result):
            raise ValueError()
        return result
    except ValueError as error:
        raise argparse.ArgumentTypeError("Use a nonempty JSON array of command arguments") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".")
    parser.add_argument("--out", required=True, help="New directory outside source or under ignored verification-runs/")
    parser.add_argument("--gate-command", type=argv_array, required=True)
    parser.add_argument("--repair-command", type=argv_array)
    parser.add_argument("--report-relative", default="device/report.json")
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--budget-seconds", type=int, default=3600)
    parser.add_argument("--command-timeout", type=int, default=1200)
    args = parser.parse_args()
    if not 1 <= args.max_attempts <= 5 or args.budget_seconds <= 0 or args.command_timeout <= 0:
        parser.error("Use 1–5 attempts and positive time limits")
    relative = Path(args.report_relative)
    if relative.is_absolute() or ".." in relative.parts:
        parser.error("Report must be inside the current attempt directory")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
