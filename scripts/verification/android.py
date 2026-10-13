#!/usr/bin/env python3
"""Drive a disposable Android emulator and retain evidence even when verification fails."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import queue
import threading
import zipfile
import xml.etree.ElementTree as ET

try:
    from .profiles import load_profiles
    from .runtime_gc import PROFILE_ID as GC_PROFILE_ID, RuntimeGcSetup
    from .smart_turn_input import stop_target, SmartTurnInput, MODEL_BYTES, MODEL_SHA256
except ImportError:
    from profiles import load_profiles
    from runtime_gc import PROFILE_ID as GC_PROFILE_ID, RuntimeGcSetup
    from smart_turn_input import stop_target, SmartTurnInput, MODEL_BYTES, MODEL_SHA256

PACKAGE = "com.battlesbudz.jarvis.v2"
ACTIVITY = f"{PACKAGE}/.MainActivity"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SCENARIOS = Path(__file__).with_name("scenarios.json")
LIFECYCLE_SCENARIOS = Path(__file__).with_name("lifecycle_scenarios.json")
LAYOUT_SCENARIOS = Path(__file__).with_name("layout_scenarios.json")


def smart_turn_result(output):
    prefix = "INSTRUMENTATION_STATUS: jarvisSmartTurnResult="
    rows = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
    if len(rows) != 1 or len(rows[0]) > 4096:
        raise RuntimeError("Missing, duplicate or oversized Smart Turn native probe receipt")
    try:
        result = json.loads(rows[0])
    except ValueError as error:
        raise RuntimeError("Malformed Smart Turn native probe receipt") from error
    fixed = {
        "schema": "android-smart-turn-native-v1", "passed": True,
        "model_bytes": MODEL_BYTES, "model_sha256": MODEL_SHA256,
        "input_hash_verified": True, "private_copy_deleted": True, "closed_on_worker": True,
        "worker_terminated": True, "pre_cancel_rejected": True, "session_reused_after_cancel": True,
        "control_input_compatible": True, "no_speech_complete_ignored": True,
        "worker_timed_out": False, "caller_interrupted": False, "unexpected_failure": False,
        "fixtures": 2, "native_requests": 3, "worker_timeout_ms": 45_000, "cleanup_timeout_ms": 2_000,
        "coverage": "Real Android JNI, production hash-bound wrapper, synthetic control input compatibility",
        "not_covered": "Natural-language endpoint accuracy; physical microphone/audio; Fold latency",
    }
    timings = ("silence_frontend_nanos", "silence_inference_nanos", "tone_frontend_nanos", "tone_inference_nanos")
    allowed = set(fixed) | set(timings) | {"silence_probability", "tone_probability"}
    if (not isinstance(result, dict) or set(result) != allowed or
            any(type(result[key]) is not type(value) or result[key] != value for key, value in fixed.items())):
        raise RuntimeError("Invalid, failed or unbounded Smart Turn native probe receipt")
    if any(type(result[key]) not in (float, int) or not math.isfinite(result[key]) or result[key] < 0
           for key in timings):
        raise RuntimeError("Invalid Smart Turn native probe timing")
    silence, tone = result.get("silence_probability"), result.get("tone_probability")
    if (any(type(value) not in (float, int) or not math.isfinite(value) for value in (silence, tone)) or
            not 0.5 < silence <= 1 or not 0 <= tone <= 0.5):
        raise RuntimeError("Smart Turn fixture classification disagrees with pinned host semantics")
    return result


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def instrumentation_results(output, expected, expected_class):
    """adb exits zero on failed tests; require one explicit pass for every named test."""
    status, completed, errors = {}, {}, []
    if not expected or len(set(expected)) != len(expected):
        errors.append("Expected test contract must be nonempty and unique")
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
            except ValueError:
                errors.append("Invalid instrumentation status code")
                status = {}
                continue
            if code != 1:
                name = status.get("test", "<unknown>")
                if name in completed or status.get("class") != expected_class:
                    errors.append(f"Unexpected/duplicate test result: {status}")
                completed[name] = code
            status = {}
    if set(completed) != set(expected):
        errors.append(f"Expected {sorted(expected)}, received {sorted(completed)}")
    if any(code != 0 for code in completed.values()):
        errors.append("Failed, skipped or incomplete instrumentation test")
    completions = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    if completions != [str(len(expected))]:
        errors.append("Missing/duplicate successful runner completion or wrong test count")
    if any(marker in output for marker in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "Process crashed", "shortMsg=")):
        errors.append("Instrumentation reported a failure")
    return {"passed": not errors, "tests": completed, "errors": errors}


def interrupted_results(output, test, expected_class, boundary):
    """Only a declared external-death phase may lack a JUnit completion."""
    errors = []
    marker = f"INSTRUMENTATION_STATUS: jarvisBoundary={boundary}"
    if output.splitlines().count(marker) != 1:
        errors.append("Expected exactly one acknowledged interruption boundary")
    if f"INSTRUMENTATION_STATUS: class={expected_class}" not in output or f"INSTRUMENTATION_STATUS: test={test}" not in output:
        errors.append("Missing named test start at interruption boundary")
    status, starts, observed = {}, 0, False
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key] = value
            if key == "jarvisBoundary":
                if starts != 1:
                    errors.append("Interruption boundary did not follow a unique named test start")
                observed = True
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
            except ValueError:
                errors.append("Invalid interrupted instrumentation status")
                status = {}
                continue
            if code == 1 and "test" in status:
                starts += 1
                if status.get("class") != expected_class or status.get("test") != test or observed:
                    errors.append("Unexpected named test in interrupted phase")
            elif code in (0, -2, -3) or code != 1 and not observed:
                errors.append("Interrupted phase completed, failed, skipped or ended before its boundary")
            status = {}
    if starts != 1:
        errors.append("Missing or duplicate interrupted test start")
    if re.search(r"^OK \(\d+ tests?\)", output, re.MULTILINE) or "FAILURES!!!" in output:
        errors.append("An interrupted phase cannot finish or report a test assertion failure")
    return {"passed": not errors, "tests": {test: "interrupted"}, "errors": errors,
            "boundary_observed": not errors, "interrupted": True}


class Device:
    def __init__(self, serial, out, adb="adb"):
        self.serial, self.out, self.adb = serial, Path(out), adb
        self.out.mkdir(parents=True, exist_ok=True)

    def run(self, *args, timeout=60, check=True, binary=False):
        command = [self.adb, "-s", self.serial, *map(str, args)]
        started = time.monotonic()
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        with (self.out / "commands.jsonl").open("a") as log:
            log.write(json.dumps({"argv": command, "exit": result.returncode,
                                  "seconds": round(time.monotonic() - started, 2)}) + "\n")
        if check and result.returncode:
            raise RuntimeError(f"adb {args[0]} failed: {result.stderr.decode(errors='replace')}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, *args, **kwargs):
        # adb joins shell arguments remotely; quote even though local shell=False.
        return self.run("shell", shlex.join(map(str, args)), **kwargs)

    def install(self, apk, *flags):
        return self.run("install", *flags, apk, timeout=180)

    def copy_phase_boundary(self, test, evidence_folder, boundary):
        """Reuse evidence already captured by the active instrumentation UiAutomation.

        Starting a second `uiautomator dump` would conflict with that registered
        session. adb sync can safely copy the exported files before process death.
        """
        if (not re.fullmatch(r"[A-Za-z0-9_]+", test) or
                not re.fullmatch(r"jarvis-verification-[0-9]+", evidence_folder) or
                boundary not in ("process_kill", "permission_revoke")):
            raise RuntimeError("Invalid phase boundary evidence identity")
        for extension in ("png", "xml"):
            target = self.out / f"boundary-{boundary}.{extension}"
            self.run("pull", f"/sdcard/Download/{evidence_folder}/{test}.{extension}", str(target))
            if not target.is_file() or target.stat().st_size == 0:
                raise RuntimeError(f"Missing pre-interruption {extension} evidence")
            if extension == "png":
                if not target.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError("Invalid pre-interruption screenshot")
            else:
                ET.parse(target)

    def instrument(self, test_class, tests, evidence_folder, timeout=600, boundary=None, foldable=False, expected_page_size=4096, smart_turn_input=None):
        """Stream explicit test handshakes so adb can interrupt/change the real target."""
        selector = test_class if len(tests) != 1 else f"{test_class}#{tests[0]}"
        args = ["am", "instrument", "-w", "-r", "-e", "class", selector,
                "-e", "jarvisEvidenceDir", evidence_folder, "-e", "jarvisFoldable", str(foldable).lower(),
                "-e", "jarvisExpectedPageSize", str(expected_page_size)]
        if smart_turn_input is not None:
            args += ["-e", "jarvisSmartTurnInput", smart_turn_input]
        args.append(RUNNER)
        command = [self.adb, "-s", self.serial, "shell", shlex.join(args)]
        started = time.monotonic()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        lines, events = [], []
        model_started_at, model_completed = None, False
        incoming = queue.Queue()

        def read_lines():
            for line in process.stdout:
                incoming.put(line)
            incoming.put(None)

        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        try:
            ended = False
            while not ended:
                if time.monotonic() - started > timeout:
                    raise RuntimeError(f"Instrumentation exceeded {timeout} seconds")
                if model_started_at is not None and not model_completed and time.monotonic() - model_started_at > 55:
                    raise RuntimeError("Smart Turn native probe exceeded its 55-second controller deadline")
                try:
                    line = incoming.get(timeout=.1)
                except queue.Empty:
                    continue
                if line is None:
                    ended = True
                    continue
                lines.append(line)
                stripped = line.strip()
                if stripped.startswith("INSTRUMENTATION_STATUS: jarvisSmartTurnStart="):
                    if (smart_turn_input is None or model_started_at is not None or
                            stripped != "INSTRUMENTATION_STATUS: jarvisSmartTurnStart=android-smart-turn-native-v1"):
                        raise RuntimeError("Unexpected or duplicate Smart Turn native probe start")
                    model_started_at = time.monotonic()
                elif stripped.startswith("INSTRUMENTATION_STATUS: jarvisSmartTurnResult="):
                    if smart_turn_input is None or model_started_at is None or model_completed:
                        raise RuntimeError("Unexpected or duplicate Smart Turn native probe result")
                    smart_turn_result(stripped)  # Fail immediately; do not let a live failed worker run later tests.
                    model_completed = True
                elif stripped.startswith("INSTRUMENTATION_STATUS: jarvisBoundary="):
                    received = stripped.split("=", 1)[1]
                    if received != boundary or events:
                        raise RuntimeError("Unexpected or duplicate external interruption request")
                    pid = self.shell("pidof", PACKAGE).strip()
                    if not re.fullmatch(r"\d+", pid):
                        raise RuntimeError("No single live app process at interruption boundary")
                    self.copy_phase_boundary(tests[0], evidence_folder, received)
                    if received == "process_kill":
                        self.shell("am", "force-stop", PACKAGE)
                    elif received == "permission_revoke":
                        self.shell("pm", "revoke", PACKAGE, "android.permission.RECORD_AUDIO")
                    else:
                        raise RuntimeError("Unsupported interruption boundary")
                    for _ in range(100):
                        remaining = self.shell("pidof", PACKAGE, check=False).strip()
                        if not remaining:
                            break
                        time.sleep(.1)
                    else:
                        raise RuntimeError("External interruption did not terminate the app process")
                    events.append({"boundary": received, "pid_before": int(pid), "pid_after": None})
                elif stripped.startswith("INSTRUMENTATION_STATUS: jarvisFold="):
                    posture = stripped.split("=", 1)[1]
                    if not foldable or posture not in ("fold", "unfold"):
                        raise RuntimeError("Unexpected emulator posture request")
                    self.run("emu", posture)
                    events.append({"posture": posture})
            if smart_turn_input is not None and not model_completed:
                raise RuntimeError("Missing completed Smart Turn native probe handshake")
            process.wait(timeout=30)
            if process.returncode and not boundary:
                raise RuntimeError(f"Instrumentation adb exited {process.returncode}")
            output = "".join(lines)
            if boundary:
                result = interrupted_results(output, tests[0], test_class, boundary)
                if len(events) != 1:
                    result["errors"].append("Controller did not perform the declared external interruption")
                    result["passed"] = False
                else:
                    result.update(events[0])
            else:
                result = instrumentation_results(output, tests, test_class)
            return output, result, events
        except BaseException as error:
            if smart_turn_input is not None:
                # This phase is entered only after disposable-emulator admission and
                # acknowledged model staging. Stop its native owner before evidence work.
                try:
                    stop_target(self)
                except Exception as stop_error:
                    raise RuntimeError(f"{error}; immediate Smart Turn process stop could not be verified: {stop_error}") from error
            raise
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            with (self.out / "commands.jsonl").open("a") as log:
                log.write(json.dumps({"argv": command, "exit": process.returncode,
                                      "seconds": round(time.monotonic() - started, 2)}) + "\n")
            # Keep partial output if infrastructure, a handshake or a timeout fails.
            self.out.joinpath("last-instrumentation.txt").write_text("".join(lines))

    def snapshot(self, name):
        import zlib

        deadline = time.monotonic() + 60

        def command(*args, diagnostic=None):
            argv = [self.adb, "-s", self.serial, *map(str, args)]
            started = time.monotonic()
            remaining = deadline - started
            if remaining <= 0:
                raise RuntimeError("Snapshot exceeded its 60-second command budget")
            receipt = {"argv": argv}
            try:
                result = subprocess.run(argv, capture_output=True, timeout=remaining)
                receipt.update(exit=result.returncode, stdout=result.stdout.decode(errors="replace"),
                               stderr=result.stderr.decode(errors="replace"))
            except subprocess.TimeoutExpired as error:
                receipt.update(exit=None, timed_out=True, stdout=(error.stdout or b"").decode(errors="replace"),
                               stderr=(error.stderr or b"").decode(errors="replace"), error=str(error))
                raise
            except OSError as error:
                receipt.update(exit=None, stdout="", stderr=str(error), error=str(error))
                raise
            finally:
                receipt["seconds"] = round(time.monotonic() - started, 2)
                with (self.out / "commands.jsonl").open("a") as log:
                    log.write(json.dumps(receipt) + "\n")
                if diagnostic is not None:
                    diagnostic.write_text(receipt.get("stdout", "") + receipt.get("stderr", "") +
                                          ("\n" + receipt["error"] if "error" in receipt else ""))
            if result.returncode:
                raise RuntimeError(f"adb {args[0]} failed: {receipt['stderr']}")
            if time.monotonic() >= deadline:
                raise RuntimeError("Snapshot exceeded its 60-second command budget")
            return result

        target = self.out / f"{name}.png"
        remote = "/sdcard/jarvis-screen.png"
        capture_diagnostic = self.out / f"{name}-screencap.txt"
        hierarchy = self.out / f"{name}.xml"
        remote_hierarchy = "/sdcard/jarvis-window.xml"
        hierarchy.unlink(missing_ok=True)

        def capture_png():
            target.unlink(missing_ok=True)
            # A device file keeps screencap's multi-display diagnostics out of the
            # image bytes. Remove the prior device file so a capture that produces
            # no new file cannot pass by pulling stale pixels. Keep both diagnostic
            # streams in command receipts and the dedicated capture diagnostic.
            command("shell", shlex.join(["rm", "-f", remote]))
            command("shell", shlex.join(["screencap", "-p", remote]),
                    diagnostic=capture_diagnostic)
            command("pull", remote, target)
            png = target.read_bytes()
            valid, offset, kinds = png.startswith(b"\x89PNG\r\n\x1a\n"), 8, []
            while valid and offset < len(png):
                if len(png) - offset < 12:
                    valid = False
                    break
                size = int.from_bytes(png[offset:offset + 4], "big")
                kind = png[offset + 4:offset + 8]
                end = offset + 12 + size
                if end > len(png) or zlib.crc32(png[offset + 4:end - 4]) != int.from_bytes(png[end - 4:end], "big"):
                    valid = False
                    break
                kinds.append(kind)
                if len(kinds) == 1:
                    valid = (kind == b"IHDR" and size == 13 and
                             int.from_bytes(png[offset + 8:offset + 12], "big") > 0 and
                             int.from_bytes(png[offset + 12:offset + 16], "big") > 0)
                if kind == b"IEND":
                    valid = valid and size == 0 and end == len(png)
                    offset = end
                    break
                offset = end
            if not valid or not kinds or kinds[-1] != b"IEND" or b"IDAT" not in kinds:
                raise RuntimeError(f"Invalid screenshot PNG for {name}")

        for attempt in range(2):
            capture_png()
            # Each attempt must produce its own XML; no old device hierarchy
            # can qualify a successful capture or the one permitted reattempt.
            command("shell", shlex.join(["rm", "-f", remote_hierarchy]))
            dump = command("shell", shlex.join(["uiautomator", "dump", remote_hierarchy]),
                           diagnostic=self.out / f"{name}-hierarchy-attempt-{attempt + 1}.txt")
            # This CLI condition exits zero before writing a file. All ordinary
            # adb failures, different diagnostics and malformed XML still fail.
            if dump.stderr.strip() == b"ERROR: null root node returned by UiTestAutomationBridge.":
                if attempt:
                    raise RuntimeError(f"Captured hierarchy for {name} still has no active root after two attempts")
                # Retain first-attempt pixels/diagnostics, then recapture both
                # files without launching an app or changing device state.
                target.replace(self.out / f"{name}-attempt-1.png")
                capture_diagnostic.replace(self.out / f"{name}-attempt-1-screencap.txt")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError("Snapshot exceeded its 60-second command budget")
                time.sleep(min(0.5, remaining))
                continue
            xml = command("shell", shlex.join(["cat", remote_hierarchy])).stdout.decode(errors="replace")
            hierarchy.write_text(xml)
            if ET.fromstring(xml).tag != "hierarchy":
                raise RuntimeError(f"Invalid captured hierarchy for {name}: expected hierarchy root")
            return xml


def verify(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    device = Device(args.serial, out, args.adb)
    scenarios = json.loads(SCENARIOS.read_text())
    lifecycle = json.loads(LIFECYCLE_SCENARIOS.read_text())
    layout = json.loads(LAYOUT_SCENARIOS.read_text())
    profile = next((p for p in load_profiles() if p["id"] == args.profile), None)
    evidence_folder = f"jarvis-verification-{time.time_ns()}"
    report = {"schema": 1, "passed": False, "source_commit": args.source_commit,
              "pr_head": args.pr_head, "run_url": os.getenv("GITHUB_SERVER_URL", "https://github.com") + "/" +
              os.getenv("GITHUB_REPOSITORY", "") + "/actions/runs/" + os.getenv("GITHUB_RUN_ID", ""),
              "coverage": "release UI + Android actions + previous-APK upgrade + external process/permission recovery + layout/accessibility + bounded real Smart Turn JNI/control input",
              "not_covered": scenarios["not_covered"], "device_evidence_folder": evidence_folder, "errors": []}
    report["profile"] = profile
    gc_setup = None
    smart_turn_input = None
    evidence_tests = (list(scenarios["tests"]) + list(layout["tests"]) +
                      [phase["test"] for phase in lifecycle["upgrade"].values()] +
                      [phase["test"] for phase in lifecycle["phases"].values()])

    def instrument_phase(contract, target, *, boundary=None, foldable=False, timeout=180, model_input=None):
        test_class = contract.get("class", lifecycle["class"])
        tests = contract.get("tests", [contract.get("test")])
        path = out / target
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            output, result, events = device.instrument(test_class, tests, evidence_folder,
                                                       timeout=timeout, boundary=boundary, foldable=foldable,
                                                       expected_page_size=profile["page_size"], smart_turn_input=model_input)
        except Exception:
            partial = out / "last-instrumentation.txt"
            if partial.is_file():
                path.write_bytes(partial.read_bytes())
            raise
        path.write_text(output)
        if not result["passed"]:
            raise RuntimeError(f"Instrumentation phase {target} failed: {result['errors']}")
        return result, events

    def package_version(target):
        value = device.shell("dumpsys", "package", PACKAGE)
        (out / target).write_text(value)
        matches = re.findall(r"\bversionCode=(\d+)\b", value)
        if len(set(matches)) != 1:
            raise RuntimeError("Installed package has no unambiguous version code")
        return int(matches[0])

    def collect_test_evidence():
        device.run("pull", f"/sdcard/Download/{evidence_folder}", str(out / "tests"))
        for name in evidence_tests:
            for extension in ("png", "xml"):
                files = list((out / "tests").rglob(f"{name}.{extension}"))
                if len(files) != 1 or files[0].stat().st_size == 0:
                    raise RuntimeError(f"Missing or duplicate evidence for {name}.{extension}")
                if extension == "xml":
                    ET.parse(files[0])
                elif not files[0].read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError(f"Invalid screenshot for {name}")
        native_files = list((out / "tests").rglob("test04_nativeLibrariesLoadAtExpectedPageSize-native.json"))
        if len(native_files) != 1:
            raise RuntimeError("Missing or duplicate native runtime page-size evidence")
        native = json.loads(native_files[0].read_text())
        with zipfile.ZipFile(args.apk) as apk:
            libraries = sorted(name.rsplit("/", 1)[1].removeprefix("lib").removesuffix(".so")
                               for name in apk.namelist() if name.startswith("lib/arm64-v8a/") and name.endswith(".so"))
        if (native.get("passed") is not True or native.get("page_size") != profile["page_size"] or
                native.get("expected_page_size") != profile["page_size"] or not libraries or
                sorted(native.get("shipping_libraries", [])) != libraries or
                sorted(native.get("loaded_libraries", [])) != libraries):
            raise RuntimeError("Runtime loading evidence disagrees with shipping APK libraries/page size")
        report["layout"]["native_loading"] = native
    try:
        if getattr(args, "smart_turn_input_dir", None):
            smart_turn_input = SmartTurnInput(args.smart_turn_input_dir, out, evidence_folder.rsplit("-", 1)[1],
                                             args.smart_turn_input_owner)
            report["smart_turn_input"] = smart_turn_input.report
        # Reset is intentionally restricted to an emulator; never clear a user's phone.
        if not args.allow_emulator_reset or device.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Verification requires a disposable emulator and --allow-emulator-reset")
        if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise RuntimeError("A full source commit SHA is required")
        if profile is None:
            raise RuntimeError("A declared required emulator --profile is required")
        report["apk_sha256"] = sha256(args.apk)
        report["test_apk_sha256"] = sha256(args.test_apk)
        report["device"] = {key: device.shell("getprop", prop).strip() for key, prop in
                            (("abi", "ro.product.cpu.abilist"), ("api", "ro.build.version.sdk"),
                             ("fingerprint", "ro.build.fingerprint"), ("native_bridge", "ro.dalvik.vm.native.bridge"))}
        report["device"]["page_size"] = int(device.shell("getconf", "PAGE_SIZE").strip())
        if int(report["device"]["api"]) != profile["api"] or report["device"]["page_size"] != profile["page_size"]:
            raise RuntimeError("Provisioned emulator API/page size disagrees with its required profile")
        if "arm64-v8a" not in report["device"]["abi"]:
            raise RuntimeError("Emulator cannot run the release ARM64 APK: missing arm64-v8a ABI/native bridge")
        if profile["arch"] == "x86_64" and (
                "x86_64" not in report["device"]["abi"].split(",") or
                report["device"]["native_bridge"] in ("", "0")):
            raise RuntimeError("x86-64 emulator requires an observed ARM64 native bridge")
        if profile["id"] == GC_PROFILE_ID:
            gc_setup = RuntimeGcSetup(device, profile, args.source_commit)
            report["runtime_gc_setup"] = gc_setup.report
            gc_setup.prepare()
        # Seed OLD installed application storage, then replace package bytes without reset.
        # Android install -r also requires the original package signing identity.
        report["upgrade"] = {"passed": False, "previous_apk_sha256": sha256(args.previous_apk)}
        previous_metadata = json.loads(Path(args.previous_metadata).read_text())
        if (previous_metadata.get("schema") != 1 or previous_metadata.get("sha256") != report["upgrade"]["previous_apk_sha256"] or
                not isinstance(previous_metadata.get("build"), int) or isinstance(previous_metadata["build"], bool) or
                not previous_metadata.get("tag") or not previous_metadata.get("repository")):
            raise RuntimeError("Previous release provenance does not match its APK bytes")
        report["upgrade"]["previous_release"] = previous_metadata
        (out / "upgrade").mkdir()
        (out / "upgrade" / "previous-release.json").write_text(json.dumps(previous_metadata, indent=2) + "\n")
        device.run("uninstall", PACKAGE, check=False)
        device.run("uninstall", PACKAGE + ".test", check=False)
        device.install(args.previous_apk)
        device.install(args.test_apk, "-r", "-t")
        if "Success" not in device.shell("pm", "clear", PACKAGE):
            raise RuntimeError("Previous-release fixture data reset failed")
        report["upgrade"]["previous_version_code"] = package_version("upgrade/previous-package.txt")
        if report["upgrade"]["previous_version_code"] != previous_metadata["build"]:
            raise RuntimeError("Previous installed APK version disagrees with its numbered published release")
        report["upgrade"]["seed"], _ = instrument_phase(lifecycle["upgrade"]["seed"], "upgrade/seed.txt")
        device.shell("am", "force-stop", PACKAGE)
        device.install(args.apk, "-r")
        report["upgrade"]["candidate_version_code"] = package_version("upgrade/candidate-package.txt")
        if report["upgrade"]["candidate_version_code"] <= report["upgrade"]["previous_version_code"]:
            raise RuntimeError("Candidate is not a newer APK than the previous release")
        report["upgrade"]["verify"], _ = instrument_phase(lifecycle["upgrade"]["verify"], "upgrade/verify.txt")
        report["upgrade"]["data_cleared_during_update"] = False
        report["upgrade"]["passed"] = True
        # Existing isolated fresh-install journeys remain an independent gate.
        if "Success" not in device.shell("pm", "clear", PACKAGE):
            raise RuntimeError("Candidate fresh-install data reset failed")
        if smart_turn_input is None:
            raise RuntimeError("The pinned temporary Smart Turn input directory is required")
        smart_turn_input.stage(device, [args.apk, args.test_apk])
        device.run("logcat", "-c")
        # Permissions remain ungranted: first-run UI must function without microphone access.
        device.shell("dumpsys", "battery", "set", "level", "73")
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("wm", "dismiss-keyguard", check=False)
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        if gc_setup is not None:
            gc_setup.verify_app(PACKAGE)
        device.snapshot("first-launch")
        report["instrumentation"], _ = instrument_phase(scenarios, "instrumentation.txt",
                                                       timeout=profile["instrumentation_timeout"],
                                                       model_input=smart_turn_input.remote_model)
        report["smart_turn_native"] = smart_turn_result((out / "instrumentation.txt").read_text())
        device.shell("am", "force-stop", PACKAGE)
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        for _ in range(6):
            xml = device.snapshot("after-process-restart")
            texts = [node.get("text") for node in ET.fromstring(xml).iter("node")]
            if scenarios["restart_model"] in texts:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Model selection did not survive a fresh app process")
        report["process_restart"] = "passed"
        report["lifecycle"] = {"passed": False, "phases": {},
                               "notification_permission": "runtime" if profile["api"] >= 33 else "platform_not_applicable"}
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        for phase in ("process_seed", "process_verify"):
            contract = lifecycle["phases"][phase]
            report["lifecycle"]["phases"][phase], _ = instrument_phase(
                contract, f"lifecycle/{phase}.txt", boundary=contract.get("interrupt"))
        device.shell("pm", "revoke", PACKAGE, "android.permission.RECORD_AUDIO")
        report["lifecycle"]["phases"]["microphone_denied"], _ = instrument_phase(
            lifecycle["phases"]["microphone_denied"], "lifecycle/microphone_denied.txt")
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        for phase in ("permission_seed", "permission_revoked"):
            contract = lifecycle["phases"][phase]
            report["lifecycle"]["phases"][phase], _ = instrument_phase(
                contract, f"lifecycle/{phase}.txt", boundary=contract.get("interrupt"))
        device.shell("pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        report["lifecycle"]["phases"]["permission_regranted"], _ = instrument_phase(
            lifecycle["phases"]["permission_regranted"], "lifecycle/permission_regranted.txt")
        for phase, permission_action in (("notification_denied", "revoke"), ("notification_regranted", "grant")):
            if profile["api"] >= 33:
                device.shell("pm", permission_action, PACKAGE, "android.permission.POST_NOTIFICATIONS")
            report["lifecycle"]["phases"][phase], _ = instrument_phase(lifecycle["phases"][phase], f"lifecycle/{phase}.txt")
        report["lifecycle"]["passed"] = True
        # Run independent recovery/permission phases before layout, so a layout
        # regression cannot conceal their diagnostic outcomes. All gates remain required.
        report["layout"] = {"passed": False}
        if profile["screen_profile"] == "foldable":
            device.run("emu", "unfold")
            time.sleep(1)
            device.snapshot("layout-unfolded-baseline")
        report["layout"]["instrumentation"], report["layout"]["fold_events"] = instrument_phase(
            layout, "layout/instrumentation.txt", foldable=profile["screen_profile"] == "foldable", timeout=300)
        if profile["screen_profile"] == "foldable" and report["layout"]["fold_events"] != [{"posture": "fold"}, {"posture": "unfold"}]:
            raise RuntimeError("Foldable profile did not exercise fold and unfold")
        report["layout"]["passed"] = True
        report["passed"] = True
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        report["errors"].append(str(error))
    finally:
        for name, collect in (
            ("final screenshot", lambda: device.snapshot("final")),
            ("logcat", lambda: (out / "logcat.txt").write_text(device.run("logcat", "-d", "-v", "threadtime", timeout=30))),
            ("per-test evidence", collect_test_evidence),
            ("package metadata", lambda: (out / "package.txt").write_text(device.shell("dumpsys", "package", PACKAGE))),
        ):
            try:
                collect()
            except Exception as error:
                report["errors"].append(f"Could not collect {name}: {error}")
                report["passed"] = False
        try:
            device.shell("dumpsys", "battery", "reset", check=False)
        except Exception:
            pass
        if smart_turn_input is not None:
            try:
                smart_turn_input.cleanup(device)
            except Exception as error:
                report["errors"].append(str(error))
                report["passed"] = False
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        (out / "summary.md").write_text(
            f"Jarvis verification: {'PASS' if report['passed'] else 'FAIL'}\n\n"
            f"Source: `{args.source_commit}`\n\nScope: {report['coverage']}\n\n" +
            "\n".join(f"- {error}" for error in report["errors"]) +
            "\n\nNot verified:\n" + "\n".join(f"- {item}" for item in report["not_covered"]) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--apk", required=True)
    run.add_argument("--test-apk", required=True)
    run.add_argument("--previous-apk", required=True, help="Previous published signed release APK for a real update")
    run.add_argument("--previous-metadata", required=True, help="SHA-bound previous-release provenance JSON")
    run.add_argument("--smart-turn-input-dir", required=True, help="Task-owned pinned model input directory, consumed and deleted")
    run.add_argument("--smart-turn-input-owner", required=True, help="Allocation receipt nonce binding this input to its owning attempt")
    run.add_argument("--profile", required=True, help="Required profile ID from profiles.json")
    run.add_argument("--out", required=True, help="New evidence directory; existing evidence is never overwritten")
    run.add_argument("--source-commit", required=True)
    run.add_argument("--pr-head", default="")
    run.add_argument("--allow-emulator-reset", action="store_true")
    control = sub.add_parser("control", help="Interactive controls for a running emulator; never resets app data")
    control.add_argument("action", choices=["snapshot", "launch", "tap", "swipe", "text", "back"])
    control.add_argument("values", nargs="*")
    control.add_argument("--out", required=True)
    args = parser.parse_args()
    if args.command == "run":
        return verify(args)
    device = Device(args.serial, args.out, args.adb)
    if args.action == "snapshot":
        device.snapshot(f"screen-{time.time_ns()}")
    elif args.action == "launch":
        device.shell("am", "start", "-W", "-n", ACTIVITY)
    elif args.action == "back":
        device.shell("input", "keyevent", "KEYCODE_BACK")
    elif args.action in ("tap", "swipe"):
        count = 2 if args.action == "tap" else 5
        if len(args.values) != count or not all(value.isdecimal() for value in args.values):
            parser.error(f"{args.action} requires {count} nonnegative integers")
        device.shell("input", args.action, *args.values)
    else:
        if len(args.values) != 1:
            parser.error("text requires one quoted string")
        device.shell("input", "text", args.values[0].replace(" ", "%s"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
