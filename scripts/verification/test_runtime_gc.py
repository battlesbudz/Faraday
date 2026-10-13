"""Fail-closed one-reboot ART setup with an advancing clock and Android transport."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import android

from runtime_gc import (BOOT_ID, FLAG, NAMESPACE, PROFILE, PROPERTY, RuntimeGcSetup,
                        boot_directory, collector_lines, prelaunch, process_pair, process_stat, odrefresh_compilation)
from profiles import load_profiles

SOURCE = "a" * 40
BOOT0, BOOT1, BOOT2 = (f"{n * 8}-1111-2222-3333-444444444444" for n in "056")
PACKAGE = "com.battlesbudz.jarvis.v2"


def log(pid, choice="CollectorTypeCC", tag="system_server"):
    return f"10-06 02:00:01.123 {pid:5d} {pid:5d} I {tag}: Using {choice} GC.\n"


class Clock:
    value = 10.0

    def now(self): return self.value

    def pause(self, duration): self.value += duration


class Android:
    def __init__(self, out, clock):
        self.out, self.serial, self.clock = Path(out), "emulator-5554", clock
        self.calls, self.rebooted, self.override = [], False, False
        self.actual_boot = BOOT0
        self.system_pid, self.zygote_pid, self.app_pid = 500, 300, 1500
        self.system_start, self.zygote_start, self.app_start = 100, 90, 500
        self.choices, self.app_log = log(500), None
        self.compile_log = "10-06 02:00:00.100   250   250 I odsign: odrefresh compiled all artifacts, returned 80\n"
        self.flag_lag, self.flag_reads, self.services_lag = 0, 0, 0
        self.omit_odsign = False
        self.pair_missing_reads, self.roll_compile_log = 0, False
        self.action = lambda *args: None
        self.props = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "35",
                      "ro.product.cpu.abilist": "x86_64,arm64-v8a",
                      "ro.dalvik.vm.native.bridge": "libndk_translation.so",
                      "ro.build.fingerprint": "google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys"}
        self.selinux, self.page_size = "Enforcing", "16384"

    def run(self, *args, timeout=60, check=True):
        self.calls.append((args, timeout, self.clock.now()))
        if not 0 < timeout <= 10: raise AssertionError(f"Unbounded command: {args} {timeout}")
        self.clock.value += .05
        self.action(*args)
        if args == ("reboot",):
            self.rebooted, self.actual_boot = True, BOOT1
            return ""
        if args[0] == "logcat":
            if args != ("logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "20000"):
                raise AssertionError(args)
            result = self.app_log if self.app_log is not None else (self.compile_log + self.choices if self.rebooted else log(400, "CollectorTypeCMC"))
            if self.rebooted and self.roll_compile_log:
                self.compile_log = ""
            return result
        if args[0] != "shell": raise AssertionError(args)
        command = shlex.split(args[1])
        if command == ["cat", BOOT_ID]: return self.actual_boot
        if command[0] == "cat" and command[1].endswith("/stat"):
            pid = int(command[1].split("/")[2])
            parent, start = {self.system_pid: (self.zygote_pid, self.system_start),
                             self.zygote_pid: (1, self.zygote_start),
                             self.app_pid: (self.zygote_pid, self.app_start)}[pid]
            return f"{pid} (process with ) spaces) S {parent} " + "0 " * 17 + str(start)

        if command[0] == "getprop":
            if command[1] == PROPERTY: return "true" if self.override and self.flag_reads > self.flag_lag else "false"
            if command[1] == "sys.boot_completed": return "1"
            if command[1] == "odsign.verification.success": return "" if self.omit_odsign else "1"
            return self.props[command[1]]
        if command == ["getconf", "PAGE_SIZE"]: return self.page_size
        if command == ["getenforce"]: return self.selinux
        if command == ["device_config", "put", NAMESPACE, FLAG, "true"]:
            self.override = True
            return ""
        if command == ["device_config", "get", NAMESPACE, FLAG]:
            self.flag_reads += 1
            return "true" if self.override else "null"
        if command == ["ps", "-A", "-o", "PID,PPID,NAME"]:
            if self.rebooted and self.pair_missing_reads:
                self.pair_missing_reads -= 1
                return f"PID PPID NAME\n{self.zygote_pid} 1 zygote64\n"
            return f"PID PPID NAME\n{self.zygote_pid} 1 zygote64\n{self.system_pid} {self.zygote_pid} system_server\n{self.app_pid} {self.zygote_pid} {PACKAGE}\n"
        if command[:2] == ["service", "check"]:
            if self.services_lag:
                self.services_lag -= 1
                return f"Service {command[2]}: not found"
            return f"Service {command[2]}: found"
        if command[0] == "input" or command == ["wm", "dismiss-keyguard"]: return ""
        if command == ["pidof", PACKAGE]: return str(self.app_pid)
        raise AssertionError(command)


class RuntimeGcTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name) / "evidence"
        self.clock = Clock()
        self.device = Android(self.out, self.clock)
        self.receipt = prelaunch(PROFILE, self.out, SOURCE, now=self.clock.now)
        self.clock.value = 60.0  # First boot/action work already consumed 50 seconds.
        self.session = self.make_session()

    def make_session(self, profile=PROFILE, source=SOURCE):
        return RuntimeGcSetup(self.device, profile, source, now=self.clock.now, pause=self.clock.pause)

    def mutations(self):
        return [args for args, _, _ in self.device.calls
                if args == ("reboot",) or args[0] == "shell" and shlex.split(args[1])[:2] == ["device_config", "put"]]

    def test_one_reboot_current_receipts_and_original_deadline(self):
        self.device.flag_lag, self.device.services_lag = 2, 1
        self.session.prepare()
        report = self.session.report
        self.assertTrue(report["passed"])
        self.assertEqual(310, self.session.deadline)
        self.assertEqual(1, report["reboots"])
        self.assertEqual(BOOT0, report["initial_boot_id"])
        self.assertEqual(BOOT1, report["boot_id"])
        self.assertEqual({"system_server": 500, "zygote64": 300, "system_server_start_ticks": 100,
                          "zygote64_start_ticks": 90}, report["processes"])
        self.assertEqual(report["initial_identity"], report["identity"])
        self.assertEqual("Enforcing", report["identity"]["selinux"])
        self.assertEqual(2, len(self.mutations()))
        self.assertTrue(any(args == ("shell", "input keyevent 82") for args, _, _ in self.device.calls))
        self.assertTrue(all(timeout <= 310 - started for _, timeout, started in self.device.calls))
        self.assertIn("CollectorTypeCMC", (self.session.out / "initial-boot-logcat.txt").read_text())
        self.assertIn("CollectorTypeCC", (self.session.out / "system-server-collector.txt").read_text())
        self.assertEqual(BOOT1, report["odrefresh_compilation"]["boot_id"])
        self.assertIn("compiled all artifacts", (self.session.out / "odrefresh-compilation.txt").read_text())
        self.assertFalse(self.out.exists(), "Setup must not pre-create the controller directory")
        self.device.app_log = log(1500, tag="sbudz.jarvis.v2")
        self.session.verify_app(PACKAGE)
        self.assertTrue(report["jarvis_collector"]["passed"])
        self.assertEqual(1500, report["jarvis_collector"]["process"]["pid"])
        self.assertIn("sbudz.jarvis.v2", (self.session.out / "jarvis-first-launch-logcat.txt").read_text())

    def test_other_profiles_and_changed_shape_refuse_before_mutation(self):
        for profile in load_profiles():
            if profile != PROFILE:
                with self.subTest(profile=profile["id"]), self.assertRaisesRegex(RuntimeError, "unchanged"):
                    self.make_session(profile)
        for field, value in (("api", 36), ("target", "google_apis"), ("boot_timeout", 301),
                             ("page_size", 4096), ("job_timeout", 41), ("acceleration", "software")):
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "unchanged"):
                self.make_session(dict(PROFILE, **{field: value}))
        self.assertEqual([], self.device.calls)

    def test_missing_stale_wrong_or_fabricated_receipt_is_not_renewed(self):
        path = self.session.out / "prelaunch.json"
        for change in ({"source_commit": "b" * 40}, {"host_boot_id": BOOT2}, {"run_id": "wrong"},
                       {"run_attempt": "wrong"}, {"started_monotonic": 61}, {"deadline_monotonic": 311},
                       {"started_monotonic": float("nan")}, {"deadline_monotonic": float("inf")}):
            with self.subTest(change=change):
                path.write_text(json.dumps(dict(self.receipt, **change)))
                with self.assertRaises(RuntimeError): self.make_session()
        for non_object in (None, [], 3, "receipt"):
            path.write_text(json.dumps(non_object))
            with self.assertRaises(RuntimeError): self.make_session()
        path.unlink()
        with self.assertRaisesRegex(RuntimeError, "Missing/invalid"): self.make_session()
        self.assertEqual([], self.device.calls)

    def test_prelaunch_cannot_overwrite_attempt_or_create_controller_output(self):
        with self.assertRaises(FileExistsError): prelaunch(PROFILE, self.out, SOURCE, now=self.clock.now)
        self.assertFalse(self.out.exists())
        self.assertEqual(self.receipt, json.loads((boot_directory(self.out) / "prelaunch.json").read_text()))

    def test_physical_wrong_api_page_bridge_or_selinux_never_mutates(self):
        for key, value in (("serial", "0123456789"), ("selinux", "Permissive"), ("page_size", "4096"),
                           ("ro.kernel.qemu", "0"), ("ro.build.version.sdk", "36"),
                           ("ro.product.cpu.abilist", "x86_64"), ("ro.dalvik.vm.native.bridge", "0")):
            with self.subTest(key=key):
                device = Android(self.out, self.clock)
                if key in device.props: device.props[key] = value
                else: setattr(device, key, value)
                session = RuntimeGcSetup(device, PROFILE, SOURCE, now=self.clock.now, pause=self.clock.pause)
                with self.assertRaisesRegex(RuntimeError, "refuses"): session.prepare()
                self.assertFalse(session.report["passed"])
                self.assertFalse(any(args == ("reboot",) or "device_config put" in args[-1] for args, _, _ in device.calls))

    def test_expired_initial_boot_blocks_without_new_budget_or_mutation(self):
        self.clock.value = 310
        with self.assertRaisesRegex(RuntimeError, "original 300-second"): self.session.prepare()
        self.assertFalse(self.session.report["passed"])
        self.assertEqual([], self.device.calls)

    def test_property_propagation_timeout_preserves_first_boot_never_reboots(self):
        self.device.flag_lag, self.clock.value = 99999, 305
        with self.assertRaisesRegex(RuntimeError, "original 300-second"): self.session.prepare()
        self.assertEqual(0, self.session.report["reboots"])
        self.assertTrue((self.session.out / "initial-boot-logcat.txt").is_file())
        self.assertEqual(1, len(self.mutations()))

    def test_unchanged_boot_cannot_reuse_old_ready_flag_or_collector(self):
        self.clock.value = 306
        def preserve_boot(*args):
            if args[0] == "shell" and shlex.split(args[1]) == ["cat", BOOT_ID] and self.device.rebooted:
                self.device.actual_boot = BOOT0
        self.device.action = preserve_boot
        with self.assertRaisesRegex(RuntimeError, "original 300-second"): self.session.prepare()
        self.assertEqual(1, len([args for args, _, _ in self.device.calls if args == ("reboot",)]))
        self.assertNotIn("boot_id", self.session.report)

    def test_missing_odsign_or_exact_current_collector_blocks_admission(self):
        for mode in ("odsign", "missing", "shell", "stale", "wrong"):
            with self.subTest(mode=mode):
                self.clock.value = 303
                self.device = Android(self.out, self.clock)
                self.session = self.make_session()
                if mode == "odsign": self.device.omit_odsign = True
                if mode == "missing": self.device.choices = ""
                if mode == "shell": self.device.choices = log(900, tag="app_process")
                if mode == "stale": self.device.choices = log(499)
                if mode == "wrong": self.device.choices = log(500, "CollectorTypeCMC")
                with self.assertRaises(RuntimeError): self.session.prepare()
                self.assertFalse(self.session.report["passed"])
                self.assertEqual(1, self.session.report["reboots"])
                self.assertTrue((self.session.out / "post-reboot-logcat.txt").is_file())

    def test_odsign_success_property_cannot_admit_partial_failed_missing_or_cached_compilation(self):
        for raw in ("", "10-06 02:00:00.100 250 250 I odsign: odrefresh said artifacts are VALID\n",
                    "10-06 02:00:00.100 250 250 I odsign: odrefresh compiled partial artifacts, returned 81\n",
                    "10-06 02:00:00.100 250 250 E odsign: odrefresh exited unexpectedly, returned 1\n",
                    "10-06 02:00:00.100 250 250 E odrefresh: Compilation failed, stage: 2 status: 3\n"):
            with self.subTest(raw=raw):
                self.clock.value = 302
                self.device = Android(self.out, self.clock)
                self.session = self.make_session()
                self.device.compile_log = raw
                with self.assertRaises(RuntimeError): self.session.prepare()
                self.assertFalse(self.session.report["passed"])
                self.assertEqual(1, self.session.report["reboots"])

    def test_regeneration_receipt_is_retained_before_system_server_exists(self):
        self.device.pair_missing_reads, self.device.roll_compile_log = 1, True
        self.session.prepare()
        self.assertTrue(self.session.report["passed"])
        self.assertNotIn("compiled all artifacts", (self.session.out / "post-reboot-logcat.txt").read_text())
        self.assertIn("compiled all artifacts, returned 80", (self.session.out / "odrefresh-compilation.txt").read_text())

    def test_compilation_parser_requires_the_explicit_current_odsign_success_branch(self):
        valid = self.device.compile_log
        self.assertEqual(valid.rstrip(), odrefresh_compilation(valid))
        for raw in (valid.replace(" I ", " E "), valid.replace("odsign:", "app_process:"),
                    valid.replace("80", ""), valid.replace("80", "79"), "quoted " + valid):
            self.assertIsNone(odrefresh_compilation(raw))
        with self.assertRaisesRegex(RuntimeError, "regeneration failed"):
            odrefresh_compilation(valid + valid.replace("all artifacts", "partial artifacts").replace("80", "81"))

    def test_transient_reboot_poll_timeout_retries_only_the_read_under_original_deadline(self):
        failures = [1]
        def disconnected(*args):
            if self.device.rebooted and args == ("shell", "cat " + BOOT_ID) and failures[0]:
                failures[0] -= 1
                raise subprocess.TimeoutExpired("adb shell cat", 10)
        self.device.action = disconnected
        self.session.prepare()
        self.assertTrue(self.session.report["passed"])
        self.assertEqual(1, self.session.report["reboot_poll_timeouts"])
        self.assertEqual(2, len(self.mutations()))
        self.assertEqual(310, self.session.deadline)

    def test_checked_mutation_and_reconnected_readiness_timeouts_are_not_retried(self):
        for failure in ("put", "input"):
            with self.subTest(failure=failure):
                self.clock.value = 60
                self.device = Android(self.out, self.clock)
                self.session = self.make_session()
                failures = []
                def timeout(*args):
                    if (args[0] == "shell" and ((failure == "put" and args[1].startswith("device_config put")) or
                                               (failure == "input" and args[1].startswith("input")))):
                        failures.append(args)
                        raise subprocess.TimeoutExpired("adb", 10)
                self.device.action = timeout
                with self.assertRaises(subprocess.TimeoutExpired): self.session.prepare()
                self.assertEqual(1, len(failures))
                self.assertFalse(self.session.report["passed"])
                self.assertNotIn("reboot_poll_timeouts", self.session.report)

    def test_second_boot_or_server_restart_during_readiness_fails(self):
        for mode in ("reboot", "server", "zygote", "server_reused", "zygote_reused"):
            with self.subTest(mode=mode):
                self.clock.value = 60
                self.device = Android(self.out, self.clock)
                self.session = self.make_session()
                def restart(*args):
                    if args[0] == "shell" and shlex.split(args[1])[:1] == ["input"]:
                        if mode == "reboot": self.device.actual_boot = BOOT2
                        elif mode == "server": self.device.system_pid += 1
                        elif mode == "zygote": self.device.zygote_pid += 1
                        elif mode == "server_reused": self.device.system_start += 1
                        else: self.device.zygote_start += 1
                self.device.action = restart
                with self.assertRaisesRegex(RuntimeError, "identity changed"): self.session.prepare()
                self.assertFalse(self.session.report["passed"])

    def test_late_final_identity_success_fails(self):
        def late(*args):
            if self.device.rebooted and args[0] == "shell" and shlex.split(args[1]) == ["getenforce"]:
                self.clock.value = 310
        self.device.action = late
        with self.assertRaisesRegex(RuntimeError, "original 300-second"): self.session.prepare()
        self.assertFalse(self.session.report["passed"])
        self.assertTrue((self.session.out / "system-server-collector.txt").is_file())

    def test_flag_loss_does_not_repeat_put_or_reboot(self):
        def lose_flag(*args):
            if self.device.rebooted: self.device.override = False
        self.device.action = lose_flag
        with self.assertRaisesRegex(RuntimeError, "did not survive"): self.session.prepare()
        self.assertEqual(2, len(self.mutations()))

    def test_jarvis_receipt_rejects_missing_stale_wrong_mixed_and_racing_process(self):
        self.session.prepare()
        for raw in ("", log(1501), log(900, tag="app_process"), log(1500, "CollectorTypeCMC"),
                    log(1500) + log(1500, "CollectorTypeCMC")):
            with self.subTest(raw=raw):
                self.device.app_log = raw
                with self.assertRaisesRegex(RuntimeError, "did not report CollectorTypeCC"): self.session.verify_app(PACKAGE)
                self.assertFalse(self.session.report["jarvis_collector"]["passed"])
        self.device.app_log = log(1500)
        def race(*args):
            if args[0] == "logcat": self.device.app_pid = 1501
        self.device.action = race
        with self.assertRaisesRegex(RuntimeError, "identity changed"): self.session.verify_app(PACKAGE)
        self.assertFalse(self.session.report["jarvis_collector"]["passed"])

    def test_jarvis_same_pid_reuse_is_not_a_current_receipt(self):
        self.session.prepare()
        self.device.app_log = log(1500)
        def reused(*args):
            if args[0] == "logcat": self.device.app_start += 1
        self.device.action = reused
        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.session.verify_app(PACKAGE)
        self.assertFalse(self.session.report["jarvis_collector"]["passed"])

    def test_jarvis_receipt_rejects_boot_change_and_late_success(self):
        self.session.prepare()
        self.device.actual_boot = BOOT2
        with self.assertRaisesRegex(RuntimeError, "No current Jarvis"): self.session.verify_app(PACKAGE)
        self.device.actual_boot, self.device.app_log = BOOT1, log(1500)
        def late(*args):
            if args[0] == "logcat": self.clock.value += 60
        self.device.action = late
        with self.assertRaisesRegex(RuntimeError, "60-second command deadline"): self.session.verify_app(PACKAGE)
        self.assertFalse(self.session.report["jarvis_collector"]["passed"])

    def test_controller_missing_or_failed_setup_blocks_installation_and_reports_failure(self):
        for error in (None, RuntimeError("Controlled setup rejection")):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                apk = root / "app.apk"
                apk.write_bytes(b"synthetic APK")
                args = SimpleNamespace(out=str(root / "out"), serial="emulator-5554", adb="adb",
                    profile=PROFILE["id"], apk=str(apk), test_apk=str(apk), previous_apk=str(apk),
                    previous_metadata=str(root / "unused.json"), source_commit=SOURCE, pr_head="",
                    allow_emulator_reset=True)
                device = Mock(out=Path(args.out), serial=args.serial)
                device.shell.side_effect = lambda *cmd, **kw: (
                    {"ro.kernel.qemu": "1", "ro.product.cpu.abilist": "x86_64,arm64-v8a",
                     "ro.build.version.sdk": "35", "ro.build.fingerprint": "fixture",
                     "ro.dalvik.vm.native.bridge": "libndk_translation.so"}[cmd[1]]
                    if cmd[0] == "getprop" else "16384" if cmd[0] == "getconf" else "")
                device.run.return_value = ""
                if error is None:
                    setup_context = patch("android.RuntimeGcSetup", wraps=RuntimeGcSetup)
                else:
                    setup_context = patch("android.RuntimeGcSetup")
                with patch("android.Device", return_value=device), setup_context as setup, \
                        patch("android.load_profiles", return_value=[PROFILE]), redirect_stdout(io.StringIO()):
                    if error is not None:
                        setup.return_value.report = {"passed": False}
                        setup.return_value.prepare.side_effect = error
                    self.assertEqual(1, android.verify(args))
                device.install.assert_not_called()
                device.instrument.assert_not_called()
                report = json.loads((Path(args.out) / "report.json").read_text())
                self.assertFalse(report["passed"])
                self.assertIn("Missing/invalid" if error is None else "Controlled setup rejection", report["errors"][0])

    def test_start_time_parser_requires_current_pid_and_complete_positive_ticks(self):
        for raw in ("", "500 (system_server) S 300", "499 (system_server) S 300 " + "0 " * 18,
                    "500 (system_server) S 300 " + "0 " * 18):
            with self.subTest(raw=raw), self.assertRaisesRegex(RuntimeError, "start-time"):
                process_stat(raw, 500)
        self.assertEqual({"pid": 500, "parent_pid": 300, "start_ticks": 123},
            process_stat("500 (name with ) spaces) S 300 " + "0 " * 17 + "123", 500))

    def test_process_ancestry_and_collector_reject_quoted_or_other_process_logs(self):
        self.assertIsNone(process_pair("PID PPID NAME\n500 900 system_server\n900 1 app_process\n"))
        self.assertIsNone(process_pair("PID PPID NAME\n500 300 system_server\n501 300 system_server\n300 1 zygote64\n"))
        for raw in (log(499), log(500).replace(" I ", " F "), "quoted " + log(500), log(500).replace("GC.", "GC. extra")):
            self.assertEqual([], collector_lines(raw, 500))
        self.assertEqual([("CollectorTypeCC", log(500).rstrip())], collector_lines(log(500), 500))


if __name__ == "__main__": unittest.main()
