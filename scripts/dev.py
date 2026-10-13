#!/usr/bin/env python3
"""Discover the repository and run its existing Python checks from any directory.

This entrypoint does not build, sign, install, publish or verify an APK. Existing
Gradle/native scripts and the exact-revision release gates remain authoritative.
"""
import argparse
from collections import Counter
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import check_architecture


ROOT = Path(__file__).resolve().parents[1]
PYTHON_SUITES = ("scripts", "scripts/verification")
SIGNING_KEYS = (
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)


def source_inventory(root):
    """Read checked-in source sets, excluding generated build output."""
    sources = []
    for path in sorted((root / "app/src").rglob("*")):
        if not path.is_file() or path.suffix not in (".kt", ".java"):
            continue
        text = path.read_text(encoding="utf-8")
        match = PACKAGE.search(text)
        sources.append((path.relative_to(root).as_posix(),
                        match.group(1) if match else "<no package>",
                        len(text.splitlines())))
    return sources


def show_map(root, query=None):
    sources = source_inventory(root)
    if query:
        matches = [entry for entry in sources
                   if query.casefold() in (entry[0] + " " + entry[1]).casefold()]
        for path, package, lines in matches:
            print(f"{path} ({lines} lines; {package})")
        if not matches:
            print(f"No source paths or packages match {query!r}.")
        return 0

    print("Navigation: README.md -> CONTRIBUTING.md -> docs/architecture/README.md")
    print("Tooling: scripts/README.md; release contract: docs/verification/features.md")
    print("\nProduction packages (source file count):")
    production = [entry for entry in sources if entry[0].startswith("app/src/main/")]
    for package, count in sorted(Counter(entry[1] for entry in production).items()):
        print(f"  {count:4}  {package}")
    print("\nLargest production sources (navigation aid, not a size limit):")
    for path, _, lines in sorted(production, key=lambda entry: (-entry[2], entry[0]))[:10]:
        print(f"  {lines:5}  {path}")
    print("\nTest source sets:")
    for source_set in ("test", "androidTest"):
        prefix = f"app/src/{source_set}/"
        print(f"  {prefix}: {sum(entry[0].startswith(prefix) for entry in sources)} source files")
    print("\nSearch a path or package: python3 scripts/dev.py map conversation")
    return 0


def doctor(root):
    print(f"Repository: {root}")
    print(f"Python: {sys.version.split()[0]} ({sys.executable}); requires 3.10+")
    print("\nLocal build tools (installed versions must match docs/verification/README.md):")
    for tool, requirement in (
        ("java", "JDK 21"), ("gradle", "8.10.2; repository has no wrapper"),
        ("cmake", "3.22.1"), ("adb", "disposable emulator integration"),
        ("git", "revision and evidence tracking"),
    ):
        path = shutil.which(tool)
        print(f"  {tool}: {path or 'missing'} ({requirement})")

    print("\nAndroid SDK configuration:")
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        state = "set, directory exists" if value and Path(value).is_dir() else (
            "set, directory missing" if value else "unset")
        print(f"  {key}: {state}")
    properties = root / "local.properties"
    sdk_property = properties.is_file() and any(
        re.match(r"\s*sdk\.dir\s*[:=]", line)
        for line in properties.read_text(encoding="utf-8").splitlines()
    )
    print(f"  local.properties sdk.dir: {'configured' if sdk_property else 'not configured'}")
    print("  Required: platform 35, NDK 27.2.12479018, CMake 3.22.1")
    print("\nRelease signing environment (presence only; values are never printed):")
    for key in SIGNING_KEYS:
        print(f"  {key}: {'set' if os.environ.get(key) else 'unset'}")
    print("\nThis report does not validate tool versions or authorize a release.")
    print("Python checks need no SDK. Hosted CI supplies the signed build and emulator gates.")
    return 0


def run_checks(root):
    """Check dependencies and both helper suites, retaining every gate's failure."""
    failed = check_architecture.run_check(root)
    for directory in PYTHON_SUITES:
        print(f"\n[check] Python helper tests: {directory}", flush=True)
        command = [sys.executable, "-m", "unittest", "discover", "-s", directory,
                   "-p", "test_*.py"]
        try:
            result = subprocess.run(command, cwd=root)
        except OSError as error:
            print(f"Unable to start Python helper checks: {error}", file=sys.stderr)
            return 1
        if result.returncode:
            failed = failed or (result.returncode if result.returncode > 0 else 1)
    return failed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    mapping = commands.add_parser("map", help="list production packages and largest sources")
    mapping.add_argument("query", nargs="?", help="filter source paths or package names")
    commands.add_parser("doctor", help="report local tool/configuration presence without changes")
    commands.add_parser("check", help="check architecture and all Python helper suites; no SDK required")
    args = parser.parse_args(argv)
    if args.command == "map":
        return show_map(ROOT, args.query)
    if args.command == "doctor":
        return doctor(ROOT)
    return run_checks(ROOT)


if __name__ == "__main__":
    sys.exit(main())
