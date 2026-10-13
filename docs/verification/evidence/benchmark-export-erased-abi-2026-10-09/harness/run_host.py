#!/usr/bin/env python3
"""Offline, source-bound host reproduction. Requires the recorded dependency cache.

The production factory and the exact instrumentation helper compile separately.
The host-only bridge transform reproduces the missing typed descriptors observed
in the real Build 1247 DEX; it is not R8 execution or Android instrumentation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--deps", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--baseline", default="770cde61791cceed664f7c991e17fc08f0a35a7d")
    args = parser.parse_args()
    repo, deps, out = (p.resolve() for p in (args.repo, args.deps, args.out))
    out.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((deps / "manifest.json").read_text())
    jars = sorted(deps.glob("*.jar"))
    # Verify every cached dependency against its retained acquisition receipt.
    for path in jars:
        entries = [e for e in manifest if e["file"] == path.name or e["file"].replace(".aar", ".jar") == path.name]
        assert len(entries) == 1, path
        entry = entries[0]
        expected = entry["sha256"] if entry["file"] == path.name else entry["classes_sha256"]
        assert digest(path) == expected, path
    cp = ":".join(map(str, jars))
    records = []

    def run(name, command, success=True, contains=None):
        result = subprocess.run(command, capture_output=True, text=True)
        log = result.stdout + result.stderr
        (out / (name + ".log")).write_text(log)
        records.append({"name": name, "command": list(map(str, command)), "exit": result.returncode,
                        "log_sha256": digest(out / (name + ".log"))})
        assert (result.returncode == 0) == success, (name, log)
        if contains:
            assert contains in log, (name, log)
        return log

    compiler = ["java", "-Xmx900m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "17"]
    production = repo / "app/src/main/java/com/battlesbudz/jarvis/v2/ui/BenchmarkExportFiles.kt"
    probe_path = "app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/BenchmarkExportJourneyProbe.kt"
    probe = repo / probe_path
    baseline = out / "baseline/BenchmarkExportJourneyProbe.kt"
    baseline.parent.mkdir()
    baseline.write_bytes(subprocess.check_output(["git", "-C", str(repo), "show", args.baseline + ":" + probe_path]))
    # Every assertion is identical, including repeated/recreated OPENABLE and result checks.
    assertions = lambda s: [line.strip() for line in s.splitlines() if "assert" in line]
    assert assertions(baseline.read_text()) == assertions(probe.read_text())
    runner = out / "RunContract.kt"
    runner.write_text('''package com.battlesbudz.jarvis.v2.verification
import android.content.ContextWrapper
fun main() {
    BenchmarkExportJourneyProbe(ContextWrapper(null)).verifyCreateDocumentContracts()
    println("PASS: 78 exact probe assertions across TXT/JSON/CSV")
}
''')
    prod_out = out / "production"
    run("compile-production", compiler + ["-classpath", cp, "-d", str(prod_out), str(production)])
    for label, source in (("before", baseline), ("after", probe)):
        target = out / label
        run("compile-" + label, compiler + ["-classpath", str(prod_out) + ":" + cp,
            "-Xfriend-paths=" + str(prod_out), "-d", str(target), str(source), str(runner)])
        bytecode = run("bytecode-" + label, ["java", "--module", "jdk.jdeps/com.sun.tools.javap.Main",
            "-classpath", str(target), "-p", "-c", "com.battlesbudz.jarvis.v2.verification.BenchmarkExportJourneyProbe"])
        if label == "after":
            for signature in ("createIntent:(Landroid/content/Context;Ljava/lang/Object;)Landroid/content/Intent;",
                              "getSynchronousResult:(Landroid/content/Context;Ljava/lang/Object;)Landroidx/activity/result/contract/ActivityResultContract$SynchronousResult;",
                              "parseResult:(ILandroid/content/Intent;)Ljava/lang/Object;"):
                assert "ActivityResultContract." + signature in bytecode, signature
            assert "ActivityResultContracts$CreateDocument." not in bytecode
        run("stock-" + label, ["java", "-cp", str(target) + ":" + str(prod_out) + ":" + cp,
            "com.battlesbudz.jarvis.v2.verification.RunContractKt"], contains="PASS: 78")

    transform = Path(__file__).with_name("FoldTypedMethods.java")
    transform_classes = out / "transform"
    run("compile-transform", ["java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-cp", cp,
        "-d", str(transform_classes), str(transform)])
    folded = out / "folded-androidx"
    run("fold-typed-methods", ["java", "-cp", str(transform_classes) + ":" + cp, "FoldTypedMethods",
        str(deps / "activity-1.10.0.jar"), str(folded)])
    folded_bytecode = run("bytecode-folded", ["java", "--module", "jdk.jdeps/com.sun.tools.javap.Main", "-p", "-c",
        "-classpath", str(folded), "androidx.activity.result.contract.ActivityResultContracts$CreateDocument"])
    assert "getSynchronousResult(android.content.Context, java.lang.String)" not in folded_bytecode
    assert "android.net.Uri parseResult(int, android.content.Intent)" not in folded_bytecode
    for label in ("before", "after"):
        run("folded-" + label, ["java", "-cp", str(out / label) + ":" + str(prod_out) + ":" + str(folded) + ":" + cp,
            "com.battlesbudz.jarvis.v2.verification.RunContractKt"], success=label == "after",
            contains="NoSuchMethodError" if label == "before" else "PASS: 78")
    old_failure = (out / "folded-before.log").read_text()
    assert "CreateDocument.getSynchronousResult(android.content.Context, java.lang.String)" in old_failure

    # Isolate the second broken typed descriptor; the original probe never got this far.
    parse_runner = out / "TypedParse.kt"
    parse_runner.write_text('''package com.battlesbudz.jarvis.v2.ui
import android.app.Activity
import android.content.Intent
import android.net.Uri
fun main() {
    val result = Intent().setData(Uri.parse("content://benchmark-test/result.txt"))
    check(BenchmarkExportFiles.createDocument(BenchmarkExportFormat.TXT).parseResult(Activity.RESULT_OK, result) == result.data)
}
''')
    parse_out = out / "typed-parse"
    run("compile-typed-parse", compiler + ["-classpath", str(prod_out) + ":" + cp,
        "-Xfriend-paths=" + str(prod_out), "-d", str(parse_out), str(parse_runner)])
    run("folded-typed-parse", ["java", "-cp", str(parse_out) + ":" + str(prod_out) + ":" + str(folded) + ":" + cp,
        "com.battlesbudz.jarvis.v2.ui.TypedParseKt"], success=False, contains="CreateDocument.parseResult(int, android.content.Intent)")

    # Product regression negative control: the existing OPENABLE assertion must still reject omission.
    missing_openable = out / "missing-openable/BenchmarkExportFiles.kt"
    missing_openable.parent.mkdir()
    source = production.read_text()
    assert source.count(".addCategory(Intent.CATEGORY_OPENABLE)") == 1
    missing_openable.write_text(source.replace(".addCategory(Intent.CATEGORY_OPENABLE)", ""))
    missing_out = out / "missing-openable-classes"
    run("compile-missing-openable", compiler + ["-classpath", cp, "-d", str(missing_out), str(missing_openable)])
    run("reject-missing-openable", ["java", "-cp", str(out / "after") + ":" + str(missing_out) + ":" + str(folded) + ":" + cp,
        "com.battlesbudz.jarvis.v2.verification.RunContractKt"], success=False, contains="AssertionError")
    receipt = {
        "schema": 1, "passed": True, "baseline_commit": args.baseline,
        "source_commit_at_execution": subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip(),
        "scope": "Separate production/test classfile compilation; real Android14/AndroidX bytecode; exact 78-assertion probe; host bridge-folding reproduction, not R8 or device execution.",
        "not_covered": ["Full Gradle release JVM/native checks", "New R8 compilation", "Signed APK", "Android instrumentation", "Picker/ContentResolver/FileProvider I/O", "Full five-profile release gate"],
        "source_sha256": {str(p.relative_to(repo)): digest(p) for p in (production, probe)},
        "harness_sha256": {p.name: digest(p) for p in (Path(__file__), transform)},
        "baseline_probe_sha256": digest(baseline),
        "dependencies": {p.name: digest(p) for p in jars},
        "dependency_provenance": manifest, "commands": records,
    }
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("PASS: old typed ABI fails, fixed erased ABI passes all 78 assertions; typed parse and OPENABLE negative controls fail as expected.")


if __name__ == "__main__":
    main()
