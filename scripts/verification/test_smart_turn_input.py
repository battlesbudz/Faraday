"""Input transport/cleanup failures never produce a native-model verification pass."""
import hashlib
import gzip
import io
import importlib
import sys
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import zipfile

import smart_turn_input as inputs
from android import Device, PACKAGE, smart_turn_result


class InputTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "input"
        self.owner = "0" * 64
        self.payload = b"synthetic non-model input"
        self.addCleanup(patch.stopall)
        patch.object(inputs, "MODEL_BYTES", len(self.payload)).start()
        patch.object(inputs, "MODEL_SHA256", hashlib.sha256(self.payload).hexdigest()).start()

    def download(self, payload=None):
        self.directory, self.owner = inputs.allocate(self.root)
        with patch.object(inputs.urllib.request, "urlopen", return_value=io.BytesIO(self.payload if payload is None else payload)) as open_url:
            inputs.fetch(self.directory, self.owner)
        open_url.assert_called_once_with(inputs.MODEL_URL, timeout=15)

    def test_pinned_one_shot_fetch_and_idempotent_cleanup(self):
        self.download()
        self.assertEqual(self.payload, (self.directory / inputs.FILE_NAME).read_bytes())
        inputs.cleanup_host(self.directory, self.owner)
        inputs.cleanup_host(self.directory, self.owner)
        self.assertFalse(self.directory.exists())

    def test_allocation_collision_preserves_preexisting_named_inputs_and_publishes_no_coordinates(self):
        directory = self.root / ("jarvis-smart-turn-input-" + "1" * 32)
        directory.mkdir()
        model = directory / inputs.FILE_NAME
        model.write_bytes(self.payload)
        output = self.root / "github-output"
        with patch.object(inputs.secrets, "token_hex", side_effect=lambda size: "1" * (size * 2)):
            with self.assertRaises(FileExistsError): inputs.allocate(self.root, output)
        with self.assertRaises(RuntimeError): inputs.cleanup_host(directory, "1" * 64)
        self.assertFalse(output.exists())
        self.assertEqual(self.payload, model.read_bytes())

    def test_failed_or_uncertain_mkdir_never_creates_an_ownership_receipt_or_deletes_path(self):
        original_mkdir = Path.mkdir
        for uncertain in (False, True):
            with self.subTest(uncertain=uncertain):
                output = self.root / "github-output"
                observed = []
                def mkdir(path, *args, **kwargs):
                    observed.append(path)
                    if uncertain:
                        original_mkdir(path, *args, **kwargs)
                        (path / inputs.FILE_NAME).write_bytes(self.payload)
                    raise OSError("Injected uncertain create" if uncertain else "Injected failed create")
                with patch.object(Path, "mkdir", mkdir), self.assertRaises(OSError):
                    inputs.allocate(self.root, output)
                self.assertFalse(output.exists())
                self.assertFalse((observed[0] / inputs.OWNER_FILE).exists())
                if uncertain:
                    with self.assertRaises(RuntimeError): inputs.cleanup_host(observed[0], "0" * 64)
                    self.assertEqual(self.payload, (observed[0] / inputs.FILE_NAME).read_bytes())
                else: self.assertFalse(observed[0].exists())

    def test_cleanup_requires_attempt_nonce_and_exact_directory_identity(self):
        self.download()
        model = self.directory / inputs.FILE_NAME
        with self.assertRaises(RuntimeError): inputs.cleanup_host(self.directory, "0" * 64)
        self.assertEqual(self.payload, model.read_bytes())
        receipt_file = self.directory / inputs.OWNER_FILE
        receipt = json.loads(receipt_file.read_text())
        receipt["inode"] += 1
        receipt_file.write_text(json.dumps(receipt))
        with self.assertRaises(RuntimeError): inputs.cleanup_host(self.directory, self.owner)
        self.assertEqual(self.payload, model.read_bytes())

    def test_acknowledged_allocation_publishes_receipt_bound_coordinates(self):
        output = self.root / "github-output"
        directory, owner = inputs.allocate(self.root, output)
        inputs.check_owner(directory, owner)
        self.assertEqual(f"directory={directory}\nowner={owner}\n", output.read_text())
        inputs.cleanup_host(directory, owner)
        inputs.cleanup_host(directory, owner)

    def test_corrupt_short_oversized_and_failed_download_remove_partial_bytes(self):
        for payload in (b"x" * len(self.payload), self.payload[:-1], self.payload + b"x"):
            with self.subTest(size=len(payload)), self.assertRaises(RuntimeError):
                self.download(payload)
            self.assertFalse(self.directory.exists())
        self.directory, self.owner = inputs.allocate(self.root)
        with patch.object(inputs.urllib.request, "urlopen", side_effect=TimeoutError), self.assertRaises(TimeoutError):
            inputs.fetch(self.directory, self.owner)
        self.assertFalse(self.directory.exists())

    def test_existing_directory_unexpected_contents_symlink_and_evidence_overlap_refused(self):
        self.directory.mkdir()
        sentinel = self.directory / "keep.txt"
        sentinel.write_text("unrelated")
        with self.assertRaises(RuntimeError): inputs.fetch(self.directory, self.owner)
        with self.assertRaises(RuntimeError): inputs.cleanup_host(self.directory, self.owner)
        self.assertEqual("unrelated", sentinel.read_text())
        link = self.root / "link"
        link.symlink_to(self.directory)
        with self.assertRaises(RuntimeError): inputs.cleanup_host(link, self.owner)
        for evidence in (self.directory, self.directory / "evidence", self.root):
            with self.assertRaises(RuntimeError): inputs.SmartTurnInput(self.directory, evidence, "123", self.owner)
        self.download()
        (self.directory / "unexpected").write_bytes(b"preserve")
        with self.assertRaises(RuntimeError): inputs.cleanup_host(self.directory, self.owner)
        self.assertEqual(self.payload, (self.directory / inputs.FILE_NAME).read_bytes())

    def test_both_apks_reject_named_or_renamed_weights(self):
        for name, payload in ((inputs.FILE_NAME, b"anything"), ("assets/renamed", self.payload)):
            apk = self.root / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive: archive.writestr(name, payload)
            with self.assertRaises(RuntimeError): inputs.exclude_from_apks([apk])

    def test_stage_then_cleanup_records_all_three_locations(self):
        self.download()
        session = inputs.SmartTurnInput(self.directory, self.root / "evidence", "123", self.owner)
        device = FakeDevice()
        with patch.object(inputs, "exclude_from_apks"):
            session.stage(device, ["app.apk", "test.apk"])
        self.assertTrue(session.report["device_verified"])
        session.cleanup(device)
        self.assertEqual("verified", session.report["host_cleanup"])
        self.assertEqual("verified", session.report["device_cleanup"])
        self.assertEqual("verified", session.report["private_copy_cleanup"])
        commands = [command for command, _ in device.calls]
        self.assertLess(commands.index(("am", "force-stop", inputs.PACKAGE)),
                        commands.index(("pm", "clear", inputs.PACKAGE)))
        self.assertTrue(all("/sdcard/Download" not in str(command) for command in commands))

    def test_failed_push_still_cleans_and_lingering_process_or_cleanup_fault_fails(self):
        for fault in ("push", "pid", "pid_transport", "pid_empty", "clear", "rmdir"):
            with self.subTest(fault=fault):
                self.download()
                session = inputs.SmartTurnInput(self.directory, self.root / "evidence", "123", self.owner)
                device = FakeDevice(fault)
                with patch.object(inputs, "exclude_from_apks"):
                    if fault == "push":
                        with self.assertRaises(RuntimeError): session.stage(device, [])
                    else: session.stage(device, [])
                if fault == "push": session.cleanup(device)
                else:
                    with self.assertRaises(RuntimeError): session.cleanup(device)
                self.assertEqual("verified", session.report["host_cleanup"])
                self.assertFalse(self.directory.exists())
                if fault in ("pid", "pid_transport", "pid_empty", "clear"):
                    self.assertEqual("uncertain", session.report["private_copy_cleanup"])
                if fault == "rmdir": self.assertEqual("uncertain", session.report["device_cleanup"])

    def test_before_staging_only_host_cleanup_runs(self):
        self.download()
        session = inputs.SmartTurnInput(self.directory, self.root / "evidence", "123", self.owner)
        device = FakeDevice()
        session.cleanup(device)
        self.assertEqual([], device.calls)
        self.assertEqual("not_created", session.report["private_copy_cleanup"])

    def test_collision_or_uncertain_creation_never_deletes_unowned_device_path(self):
        for fault in ("collision", "lost_creation"):
            with self.subTest(fault=fault):
                self.download()
                session = inputs.SmartTurnInput(self.directory, self.root / "evidence", "123", self.owner)
                device = FakeDevice(fault)
                with patch.object(inputs, "exclude_from_apks"), self.assertRaises(RuntimeError):
                    session.stage(device, [])
                if fault == "collision": session.cleanup(device)
                else:
                    with self.assertRaises(RuntimeError): session.cleanup(device)
                self.assertEqual("not_owned" if fault == "collision" else "uncertain", session.report["device_cleanup"])
                self.assertFalse(any(cmd[0] in ("rm", "rmdir", "am", "pm") for cmd, _ in device.calls))
                self.assertFalse(self.directory.exists())

    def test_local_gate_cleans_input_on_build_failure_controller_failure_and_success(self):
        env = {key: "fixture" for key in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
               "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", "JARVIS_PREVIOUS_APK", "JARVIS_PREVIOUS_METADATA",
               "JARVIS_EMULATOR_PROFILE")}
        env.update(JARVIS_ATTEMPT_DIR=str(self.root / "attempt"), JARVIS_SOURCE_COMMIT="a" * 40,
                   JARVIS_SMART_TURN_INPUT_DIR=str(self.directory))
        for failure in (0, 1, None):
            with self.subTest(failure=failure):
                self.download()
                env.update(JARVIS_SMART_TURN_INPUT_DIR=str(self.directory), JARVIS_SMART_TURN_INPUT_OWNER=self.owner)
                calls = []
                def run(command, **kwargs):
                    calls.append(command)
                    if failure == len(calls) - 1: raise subprocess.CalledProcessError(1, "fixture")
                with patch.dict(os.environ, env, clear=True), patch("subprocess.run", side_effect=run):
                    script = str(Path(__file__).with_name("local_gate.py"))
                    if failure is None: runpy.run_path(script)
                    else:
                        with self.assertRaises(subprocess.CalledProcessError): runpy.run_path(script)
                self.assertFalse(self.directory.exists())
                self.assertEqual(1 if failure == 0 else 2, len(calls))
        self.download()
        env.update(JARVIS_SMART_TURN_INPUT_DIR=str(self.directory), JARVIS_SMART_TURN_INPUT_OWNER="0" * 64)
        with patch.dict(os.environ, env, clear=True), patch("subprocess.run") as run:
            with self.assertRaises(RuntimeError): runpy.run_path(str(Path(__file__).with_name("local_gate.py")))
            run.assert_not_called()
        self.assertEqual(self.payload, (self.directory / inputs.FILE_NAME).read_bytes())
    def test_local_gate_cleans_owned_input_on_every_preflight_abort(self):
        env = {key: "fixture" for key in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
               "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", "JARVIS_PREVIOUS_APK", "JARVIS_PREVIOUS_METADATA",
               "JARVIS_EMULATOR_PROFILE", "JARVIS_ATTEMPT_DIR", "JARVIS_SOURCE_COMMIT")}
        for missing in env:
            with self.subTest(missing=missing):
                self.download()
                incomplete = {key: value for key, value in env.items() if key != missing}
                incomplete.update(JARVIS_SMART_TURN_INPUT_DIR=str(self.directory), JARVIS_SMART_TURN_INPUT_OWNER=self.owner)
                with patch.dict(os.environ, incomplete, clear=True), patch("subprocess.run") as run:
                    with self.assertRaises((KeyError, SystemExit)):
                        runpy.run_path(str(Path(__file__).with_name("local_gate.py")))
                    run.assert_not_called()
                self.assertFalse(self.directory.exists())



class FakeDevice:
    def __init__(self, fault=None):
        self.fault, self.calls = fault, []

    def run(self, *command, **kwargs):
        self.calls.append((command, kwargs))
        if command[0] == self.fault: raise RuntimeError("Injected input transport failure")
        return ""

    def shell(self, *command, **kwargs):
        self.calls.append((command, kwargs))
        if command[0] == self.fault: raise RuntimeError("Injected cleanup failure")
        if command[0] == "sh" and command[2].startswith("pidof "):
            if self.fault == "pid_transport": raise RuntimeError("Injected process check transport failure")
            if self.fault == "pid_empty": return ""
            return "JARVIS_PROCESS_RUNNING" if self.fault == "pid" else "JARVIS_PROCESS_ABSENT"
        if command[0] == "sh":
            if self.fault == "lost_creation": raise RuntimeError("Injected lost creation response")
            return "JARVIS_INPUT_REJECTED" if self.fault == "collision" else "JARVIS_INPUT_CREATED"
        if command[0] == "sha256sum": return inputs.MODEL_SHA256 + "  model"
        if command[0] == "stat": return str(inputs.MODEL_BYTES)
        if command[0] == "pidof": return "123" if self.fault == "pid" else ""
        if command[:2] == ("pm", "clear"): return "Failure" if self.fault == "clear" else "Success"
        return ""


class ScalarReceiptTest(unittest.TestCase):
    def test_production_model_pin_and_exact_weight_free_fixture_copies(self):
        root = Path(__file__).resolve().parents[2]
        spec = (root / "app/src/main/java/com/battlesbudz/jarvis/v2/voice/smartturn/SmartTurnModelSpec.kt").read_text()
        for pin in (inputs.MODEL_URL, inputs.MODEL_SHA256, inputs.FILE_NAME, f"{inputs.MODEL_BYTES:,}".replace(",", "_")):
            self.assertIn(pin, spec)
        assets = root / "app/src/androidTest/assets/smart-turn"
        manifest = json.loads((assets / "manifest.json").read_text())
        self.assertEqual(hashlib.sha256((root / manifest["generator"]).read_bytes()).hexdigest(), manifest["generator_sha256"])
        for fixture in manifest["fixtures"]:
            actual = (assets / fixture["file"]).read_bytes()
            self.assertEqual((root / fixture["source"]).read_bytes(), actual)
            self.assertEqual(fixture["sha256_compressed"], hashlib.sha256(actual).hexdigest())
            pcm = gzip.decompress(actual)
            self.assertEqual(fixture["sha256_pcm16le"], hashlib.sha256(pcm).hexdigest())
            self.assertEqual(fixture["decoded_bytes"], len(pcm))

    def test_missing_duplicate_failed_nonscalar_and_bad_identity_rejected(self):
        # Production pins here; payload-patching above is solely helper transport testing.
        receipt = {"schema": "android-smart-turn-native-v1", "passed": True,
                   "model_bytes": 8_679_182,
                   "model_sha256": "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f",
                   "input_hash_verified": True, "private_copy_deleted": True, "closed_on_worker": True,
                   "worker_terminated": True, "pre_cancel_rejected": True, "session_reused_after_cancel": True,
                   "control_input_compatible": True, "no_speech_complete_ignored": True,
                   "worker_timed_out": False, "caller_interrupted": False, "unexpected_failure": False,
                   "fixtures": 2, "native_requests": 3, "silence_probability": 0.9, "tone_probability": 0.1,
                   "worker_timeout_ms": 45_000, "cleanup_timeout_ms": 2_000,
                   "silence_frontend_nanos": 1, "silence_inference_nanos": 2,
                   "tone_frontend_nanos": 1, "tone_inference_nanos": 2,
                   "coverage": "Real Android JNI, production hash-bound wrapper, synthetic control input compatibility",
                   "not_covered": "Natural-language endpoint accuracy; physical microphone/audio; Fold latency"}
        prefix = "INSTRUMENTATION_STATUS: jarvisSmartTurnResult="
        line = prefix + json.dumps(receipt)
        self.assertEqual(receipt, smart_turn_result(line))
        mutations = [dict(receipt, arbitrary_scalar="unapproved"),
                     dict(receipt, worker_timeout_ms=450_000), dict(receipt, cleanup_timeout_ms=20_000),
                     dict(receipt, fixtures=True), dict(receipt, coverage="unsupported claim")]
        mutations += [{key: value for key, value in receipt.items() if key != missing} for missing in receipt]
        for key in ("silence_frontend_nanos", "silence_inference_nanos", "tone_frontend_nanos", "tone_inference_nanos"):
            mutations += [dict(receipt, **{key: value}) for value in (float("nan"), float("inf"), -1, True, "1")]
        for malformed in mutations:
            with self.subTest(receipt=malformed), self.assertRaises(RuntimeError):
                smart_turn_result(prefix + json.dumps(malformed))
        for output in ("", line + "\n" + line, prefix + "garbage", prefix + "[]",
                       prefix + json.dumps(dict(receipt, passed=False)),
                       prefix + json.dumps(dict(receipt, model_bytes=1)),
                       prefix + json.dumps(dict(receipt, closed_on_worker=False)),
                       prefix + json.dumps(dict(receipt, tone_probability=float("nan"))),
                       prefix + json.dumps(dict(receipt, silence_probability=0.5)),
                       prefix + json.dumps(dict(receipt, tensor=[1, 2]))):
            with self.subTest(output=output), self.assertRaises(RuntimeError): smart_turn_result(output)


class ProbeControllerTest(unittest.TestCase):
    def exercise(self, lines, *, stalled=False, stop_fails=False, device_type=Device, later_lines=()):
        with tempfile.TemporaryDirectory() as directory:
            device = device_type("emulator-5554", directory)
            calls, done = [], threading.Event()
            def output():
                yield from lines
                yield from later_lines
                if stalled: done.wait(2)
            process = Mock(stdout=output(), returncode=None)
            process.poll.return_value = None
            def kill():
                calls.append("adb_kill")
                done.set()
                process.returncode = -9
            process.kill.side_effect = kill
            def shell(*command, **kwargs):
                calls.append((command, kwargs))
                if stop_fails: raise RuntimeError("injected stop failure")
                return "JARVIS_PROCESS_ABSENT" if command[0] == "sh" else ""
            ticks = iter(range(0, 10000, 20))
            with patch("android.subprocess.Popen", return_value=process), patch.object(device, "shell", side_effect=shell), \
                    patch("android.time.monotonic", side_effect=lambda: next(ticks) if stalled else 0):
                with self.assertRaises(RuntimeError) as failure:
                    device.instrument("Suite", ["phase"], "jarvis-verification-123", timeout=1200,
                                      smart_turn_input="/data/local/tmp/jarvis-smart-turn-input-123/model.onnx")
            retained = (Path(directory) / "last-instrumentation.txt").read_text()
            self.assertTrue(retained.startswith("".join(lines)))
            for later in later_lines: self.assertNotIn(later, retained)
            self.assertEqual((("am", "force-stop", PACKAGE), {"timeout": 10}), calls[0])
            self.assertLess(calls.index(calls[0]), calls.index("adb_kill"))
            if not stop_fails:
                command, options = calls[1]
                self.assertEqual(("sh", "-c"), command[:2])
                self.assertTrue(command[2].startswith(f"pidof {PACKAGE} >"))
                self.assertEqual({"timeout": 10}, options)
            return str(failure.exception)

    def test_failed_or_missing_probe_receipt_stops_target_before_evidence_and_keeps_output(self):
        start = "INSTRUMENTATION_STATUS: jarvisSmartTurnStart=android-smart-turn-native-v1\n"
        failed = 'INSTRUMENTATION_STATUS: jarvisSmartTurnResult={"passed":false,"worker_terminated":false}\n'
        self.assertIn("Invalid, failed or unbounded", self.exercise([start, failed],
                      later_lines=["INSTRUMENTATION_STATUS: test=later_suite_test\n"]))
        self.assertIn("Missing completed", self.exercise([start]))
        self.assertIn("Unexpected or duplicate", self.exercise([start, start]))
        self.assertIn("could not be verified", self.exercise([start, failed], stop_fails=True))

    def test_package_module_route_stops_target_on_missing_probe_receipt(self):
        with patch.object(sys, "path", [str(Path(__file__).resolve().parents[2]), *sys.path]):
            package = importlib.import_module("scripts.verification.android")
        start = "INSTRUMENTATION_STATUS: jarvisSmartTurnStart=android-smart-turn-native-v1\n"
        self.assertIn("Missing completed", self.exercise([start], device_type=package.Device))

    def test_unresponsive_probe_receipt_has_independent_55_second_deadline(self):
        start = "INSTRUMENTATION_STATUS: jarvisSmartTurnStart=android-smart-turn-native-v1\n"
        self.assertIn("55-second controller deadline", self.exercise([start], stalled=True))


if __name__ == "__main__":
    unittest.main()
