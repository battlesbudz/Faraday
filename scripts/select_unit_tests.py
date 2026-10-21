#!/usr/bin/env python3
"""Per-package unit-test selection for Faraday CI.

Implements Battles's engineering SOP (2026-10-21), in his words: "isolate code
into like packages, execute tests on packages, cache tests per packages, only
test packages that change."

Until now the "Build signed release APK" job ran the whole JVM unit-test suite
(~2,453 tests, :app:testReleaseUnitTest) as one block on every push. This
script inspects the push's diff and decides what actually has to run:

  mode=full     Run the entire suite, exactly as before. Chosen whenever a
                change is shared or cannot be classified safely (see
                FULL TRIGGERS below). Unknown always means full: selection
                must never silently test nothing when code changed.
  mode=selected Run only the test packages the diff touches, plus the small
                always-run set described below.
  mode=none     The diff touches no JVM-testable package (docs/config-only
                style changes). Run only the always-run set so the run still
                produces JVM test evidence; main and test sources still
                compile via assembleRelease in the same Gradle invocation.

Package mapping: sources live under
app/src/main/java/com/battlesbudz/jarvis/v2/<pkg>/ and tests mirror them under
app/src/test/java/com/battlesbudz/jarvis/v2/<pkg>/. The first path segment
under v2/ is the package; a --tests filter for the package covers its nested
subpackages too.

Always-run set (selected/none modes): the verification receipt
(scripts/verification/receipt.py) refuses to consolidate a build whose JVM
results lack the recorded-audio regression tests named in
scripts/verification/acoustic_scenarios.json (required_jvm_tests). Those exact
classes therefore run in every mode, read from that JSON at selection time so
the receipt contract stays the single source of truth. In mode=none the root
v2 test classes (tests for the shared root package) join them as the smoke
set.

FULL TRIGGERS (any one forces mode=full):
  * build/config files: build.gradle.kts, settings.gradle.kts,
    gradle.properties, gradle/**, gradlew*, any *.gradle / *.gradle.kts file,
    app/build.gradle.kts, app/src/main/AndroidManifest.xml,
    app/proguard-rules.pro
  * the workflow itself (.github/workflows/android.yml), this selector, and
    scripts/ci_gradle.sh (the script that invokes Gradle)
  * scripts/verification/acoustic_scenarios.json (the receipt contract above)
  * a file directly in the v2 root package, main or test (shared by all)
  * a main-source package that has NO unit tests (assistant, work today): its
    changes can only be checked by the full suite, through its consumers
  * anything else under app/ the mapper cannot classify (resources, native
    sources, androidTest, other source roots), the :phoneinference module
    (a dependency of the app with no package mapping of its own), shared test
    fixtures under app/src/test/resources/, or an unusable diff base
    (missing/zero SHA, force-push without a merge base, any internal error)

Per-package caching: after a green run the workflow records one marker JSON
per tested package in .ci-test-cache/ (restored/saved with actions/cache),
holding the package's content hash: SHA-256 over that package's main+test
sources plus the build-config files. A marker proves a package passed at an
exact content state and is logged on later runs. Boundary, deliberately: a
marker NEVER skips a flagged package. A package the diff flags always tests,
even if a marker at the same hash exists; the Gradle build cache (managed by
gradle/actions/setup-gradle) is what makes re-runs fast, not marker skips.
The nightly workflow (.github/workflows/nightly-full-tests.yml) runs the full
suite unconditionally, so a selection miss surfaces within a day.

Invocation (from .github/workflows/android.yml):
  select_unit_tests.py --base <sha> --head <sha> --repo <root> \
      --cache-dir .ci-test-cache
      Emits mode / gradle_test_args / packages / reason to $GITHUB_OUTPUT.
  select_unit_tests.py --mark-passed --mode <mode> --packages <csv> \
      --repo <root> --cache-dir .ci-test-cache --head <sha>
      Records pass markers for the packages that just ran green.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

MAIN_PREFIX = "app/src/main/java/com/battlesbudz/jarvis/v2/"
TEST_PREFIX = "app/src/test/java/com/battlesbudz/jarvis/v2/"
BASE_PACKAGE = "com.battlesbudz.jarvis.v2"
TEST_ROOT = "app/src/test/java/com/battlesbudz/jarvis/v2"
ACOUSTIC_CONTRACT = "scripts/verification/acoustic_scenarios.json"
CACHE_DIR_NAME = ".ci-test-cache"

FULL_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/proguard-rules.pro",
    "scripts/select_unit_tests.py",
    "scripts/ci_gradle.sh",
    ACOUSTIC_CONTRACT,
    ".github/workflows/android.yml",
}
FULL_PREFIXES = (
    "gradle/",                      # wrapper + any version catalog: shared build config
    "phoneinference/src/",          # :phoneinference feeds every app test; no package map
    "app/src/test/resources/",      # shared fixtures; cannot be attributed to one package
)
IGNORED_PREFIXES = (
    "docs/", "demo/", "artwork/", "third_party/",
    "scripts/",                     # other lanes verify script changes (dev.py, SDK jobs)
    ".github/",                     # other workflows do not change JVM test behavior
)


class Repo:
    """Checkout-derived facts the classifier needs. Built from the HEAD tree."""

    def __init__(self, root):
        self.root = Path(root)
        # pkg -> sorted list of test class FQCNs, from the test tree at HEAD.
        self.test_classes = {}
        test_dir = self.root / TEST_ROOT
        if test_dir.is_dir():
            for path in sorted(test_dir.rglob("*")):
                if path.is_file() and path.suffix in (".kt", ".java"):
                    rel = path.relative_to(self.root).as_posix()
                    rest = rel[len(TEST_PREFIX):]
                    parts = rest.split("/")
                    if len(parts) > 1:
                        fqcn = BASE_PACKAGE + "." + rest.rsplit(".", 1)[0].replace("/", ".")
                        self.test_classes.setdefault(parts[0], []).append(fqcn)
        self.test_packages = set(self.test_classes)
        # Test classes sitting directly in the shared root package.
        self.root_test_classes = []
        if test_dir.is_dir():
            for path in sorted(test_dir.iterdir()):
                if path.is_file() and path.suffix in (".kt", ".java"):
                    self.root_test_classes.append(BASE_PACKAGE + "." + path.stem)

    def required_classes(self):
        """Exact test classes the verification receipt demands in every run."""
        data = json.loads((self.root / ACOUSTIC_CONTRACT).read_text())
        classes = sorted({entry["classname"] for entry in data["required_jvm_tests"]})
        known = {fqcn for values in self.test_classes.values() for fqcn in values}
        known.update(self.root_test_classes)
        missing = [name for name in classes if name not in known]
        if missing:
            raise ValueError(f"Receipt-required test classes not found in checkout: {missing}")
        return classes


def classify_path(path, repo):
    """Return ('full', reason) | ('package', pkg) | ('ignore', reason)."""
    if path in FULL_FILES:
        return "full", f"{path} is a shared build/CI file"
    if path.startswith(FULL_PREFIXES):
        return "full", f"{path} is shared infrastructure with no single owning package"
    if path.endswith((".gradle", ".gradle.kts")):
        return "full", f"{path} is a Gradle build file"
    for prefix, kind in ((MAIN_PREFIX, "main"), (TEST_PREFIX, "test")):
        if path.startswith(prefix):
            rest = path[len(prefix):]
            if "/" not in rest:
                return "full", f"{path} sits in the shared root v2 package"
            pkg = rest.split("/", 1)[0]
            if pkg in repo.test_packages:
                return "package", pkg
            if kind == "main":
                return "full", f"package {pkg} has no unit tests; only the full suite checks it"
            return "ignore", f"test change in package {pkg}, which has no tests left at HEAD"
    if path.startswith("app/"):
        return "full", f"{path} is an app path the mapper cannot classify"
    if path.startswith(IGNORED_PREFIXES) or path.endswith(".md"):
        return "ignore", f"{path} does not affect JVM unit tests"
    if "/" not in path and path.startswith("LICENSE"):
        return "ignore", f"{path} does not affect JVM unit tests"
    return "ignore", f"{path} is outside the app module and verified by other CI lanes"


def changed_files(repo, base, head):
    """Diff paths for base..head (merge-base semantics), or None if unusable."""
    if not base or set(base) == {"0"}:
        return None
    probe = subprocess.run(["git", "cat-file", "-e", f"{base}^{{commit}}"],
                           cwd=repo.root, capture_output=True)
    if probe.returncode != 0:
        return None
    for spec in (f"{base}...{head}", f"{base}..{head}"):
        diff = subprocess.run(["git", "diff", "--name-only", spec],
                              cwd=repo.root, capture_output=True, text=True)
        if diff.returncode == 0:
            return [line for line in diff.stdout.splitlines() if line]
    return None


def decide(changed, repo):
    """Pure selection decision over a changed-path list."""
    packages, reasons = set(), []
    for path in changed:
        kind, detail = classify_path(path, repo)
        if kind == "full":
            reasons.append(detail)
        elif kind == "package":
            packages.add(detail)
    if reasons:
        return {"mode": "full", "packages": set(), "reasons": reasons}
    if packages:
        return {"mode": "selected", "packages": packages, "reasons": []}
    return {"mode": "none", "packages": set(), "reasons": []}


def build_filters(decision, repo):
    """Gradle --tests arguments for the decision, incl. the always-run set."""
    if decision["mode"] == "full":
        return []
    args = []
    for pkg in sorted(decision["packages"]):
        args += ["--tests", f"{BASE_PACKAGE}.{pkg}.*"]
    always = list(repo.required_classes())
    if decision["mode"] == "none":
        always += repo.root_test_classes  # smoke set: shared root package tests
    for fqcn in sorted(set(always)):
        args += ["--tests", fqcn]
    return args


def _hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_config_hash(repo):
    digest = hashlib.sha256()
    for rel in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                "gradle.properties", "gradle/wrapper/gradle-wrapper.properties"):
        path = repo.root / rel
        if path.is_file():
            digest.update(rel.encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def package_hash(repo, pkg):
    """Content hash of one package: its main+test sources + the build config."""
    digest = hashlib.sha256()
    digest.update(build_config_hash(repo).encode())
    for prefix in (MAIN_PREFIX, TEST_PREFIX):
        folder = repo.root / (prefix + pkg)
        if folder.is_dir():
            for path in sorted(folder.rglob("*")):
                if path.is_file():
                    digest.update(path.relative_to(repo.root).as_posix().encode())
                    digest.update(path.read_bytes())
    return digest.hexdigest()


def emit(outputs):
    """Append key=value lines to $GITHUB_OUTPUT (and echo them for the log)."""
    target = os.environ.get("GITHUB_OUTPUT")
    lines = [f"{key}={value}" for key, value in outputs.items()]
    for line in lines:
        print(line)
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


def run_selection(args):
    repo = Repo(args.repo)
    try:
        changed = (Path(args.changed_files).read_text().splitlines()
                   if args.changed_files else changed_files(repo, args.base, args.head))
        if changed is None:
            decision = {"mode": "full", "packages": set(),
                        "reasons": ["diff base is unusable; running the full suite"]}
        else:
            decision = decide([line for line in changed if line], repo)
        filters = build_filters(decision, repo)
    except Exception as error:  # selection must fail safe, never fail silent
        print(f"select_unit_tests: {error!r}; falling back to the full suite")
        decision = {"mode": "full", "packages": set(),
                    "reasons": [f"selector error ({error}); full suite is the safe fallback"]}
        filters = []
    mode = decision["mode"]
    if mode == "full":
        marker_packages = sorted(repo.test_packages)
        default_reason = "shared or unclassifiable change; full suite"
    elif mode == "selected":
        marker_packages = sorted(decision["packages"])
        default_reason = "changed packages: " + ", ".join(marker_packages)
    else:
        marker_packages = []
        default_reason = "no JVM-testable package changed; always-run set only"
    reason = "; ".join(decision["reasons"][:3]) if decision["reasons"] else default_reason
    reason = " ".join(reason.split())[:300]
    print(f"Unit-test selection: mode={mode} ({reason})")
    # Per-package content hashes + cache status, for the marker mechanism.
    if args.cache_dir:
        cache = Path(args.cache_dir)
        cache.mkdir(parents=True, exist_ok=True)
        hashes = {pkg: package_hash(repo, pkg) for pkg in sorted(repo.test_packages)}
        (cache / "current-hashes.json").write_text(json.dumps(hashes, indent=1) + "\n")
        for pkg, digest in hashes.items():
            marker = cache / f"{pkg}.json"
            status = "no marker"
            if marker.is_file():
                try:
                    status = ("marker hit at same content hash"
                              if json.loads(marker.read_text()).get("content_hash") == digest
                              else "marker stale (content changed since its green run)")
                except (ValueError, OSError):
                    status = "marker unreadable"
            print(f"cache[{pkg}]: {status}")
        if mode != "full":
            print("note: markers never skip a flagged package; flagged packages always test")
    emit({
        "mode": mode,
        "gradle_test_args": " ".join(filters),
        "packages": ",".join(marker_packages),
        "reason": reason,
    })
    return 0


def run_mark_passed(args):
    """Record pass markers for the packages that just ran green. Bookkeeping
    only: any problem here is reported in the log and swallowed (exit 0), the
    workflow step is also continue-on-error."""
    try:
        repo = Repo(args.repo)
        cache = Path(args.cache_dir)
        cache.mkdir(parents=True, exist_ok=True)
        stored = {}
        hashes_file = cache / "current-hashes.json"
        if hashes_file.is_file():
            stored = json.loads(hashes_file.read_text())
        if args.mode == "full":
            packages = sorted(repo.test_packages)
        else:
            packages = [pkg for pkg in args.packages.split(",") if pkg]
        for pkg in packages:
            digest = stored.get(pkg) or package_hash(repo, pkg)
            marker = {
                "package": pkg,
                "content_hash": digest,
                "head": args.head,
                "run_id": os.environ.get("GITHUB_RUN_ID", ""),
                "result": "passed",
            }
            (cache / f"{pkg}.json").write_text(json.dumps(marker, indent=1) + "\n")
            print(f"marker recorded: {pkg} @ {digest[:12]}")
    except Exception as error:
        print(f"select_unit_tests --mark-passed: {error!r} (ignored; bookkeeping only)")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="", help="diff base SHA (push 'before' / PR base)")
    parser.add_argument("--head", default="", help="diff head SHA")
    parser.add_argument("--repo", default=".", help="checkout root")
    parser.add_argument("--cache-dir", default=CACHE_DIR_NAME)
    parser.add_argument("--changed-files", help="read changed paths from this file instead of git")
    parser.add_argument("--mark-passed", action="store_true",
                        help="record pass markers instead of selecting")
    parser.add_argument("--mode", default="", help="selection mode (for --mark-passed)")
    parser.add_argument("--packages", default="", help="comma list (for --mark-passed)")
    args = parser.parse_args(argv)
    if args.mark_passed:
        return run_mark_passed(args)
    return run_selection(args)


if __name__ == "__main__":
    sys.exit(main())
