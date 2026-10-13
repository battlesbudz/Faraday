"""Failure evidence must not be mistaken for an intentional external process death."""
from contextlib import redirect_stdout
import base64
import io
import json
import shlex
import struct
import subprocess
import sys
import time
from types import SimpleNamespace
import unittest
from pathlib import Path
import tempfile
from unittest.mock import Mock, patch
from xml.etree.ElementTree import ParseError
import zlib

from android import Device, PACKAGE, instrumentation_results, interrupted_results, sha256, verify
from profiles import load_profiles
from runtime_gc import PROFILE as LEGACY_GC_PROFILE


def interrupted(boundary="process_kill"):
    return ("INSTRUMENTATION_STATUS: class=Suite\nINSTRUMENTATION_STATUS: test=phase\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: jarvisBoundary={boundary}\nINSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")


class PhaseEvidenceTest(unittest.TestCase):
    def test_boundary_reuses_exported_evidence_without_a_second_ui_automation_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            calls = []

            def pull(*args, **kwargs):
                calls.append(args)
                self.assertEqual("pull", args[0])
                target = Path(args[2])
                target.write_bytes(b"\x89PNG\r\n\x1a\nfixture" if target.suffix == ".png" else b"<hierarchy />")

            with patch.object(device, "run", side_effect=pull), patch.object(device, "snapshot", side_effect=AssertionError("Concurrent UiAutomation")):
                device.copy_phase_boundary("testPhase", "jarvis-verification-123", "process_kill")
            self.assertEqual(2, len(calls))
            self.assertEqual("/sdcard/Download/jarvis-verification-123/testPhase.png", calls[0][1])
            self.assertEqual("/sdcard/Download/jarvis-verification-123/testPhase.xml", calls[1][1])

    def test_corrupt_exported_boundary_evidence_prevents_interruption(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            with patch.object(device, "run", side_effect=lambda *args, **kwargs: Path(args[2]).write_bytes(b"invalid")):
                with self.assertRaisesRegex(RuntimeError, "Invalid pre-interruption screenshot"):
                    device.copy_phase_boundary("testPhase", "jarvis-verification-123", "process_kill")

    def test_actual_declared_boundary_is_an_interruption_not_a_junit_pass(self):
        raw = interrupted()
        self.assertTrue(interrupted_results(raw, "phase", "Suite", "process_kill")["passed"])
        self.assertFalse(instrumentation_results(raw, ["phase"], "Suite")["passed"])

    def test_missing_wrong_duplicate_or_late_boundary_never_passes(self):
        for raw in ("", interrupted("permission_revoke"), interrupted() + interrupted(),
                    interrupted().replace("class=Suite", "class=Wrong"),
                    interrupted().replace("test=phase", "test=Other"),
                    interrupted().replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                    interrupted() + "OK (1 test)\n", interrupted() + "FAILURES!!!\n",
                    interrupted().replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: nope", 1)):
            with self.subTest(raw=raw):
                self.assertFalse(interrupted_results(raw, "phase", "Suite", "process_kill")["passed"])

    def test_success_footer_count_and_unique_contract_are_required(self):
        raw = "INSTRUMENTATION_STATUS: class=Suite\nINSTRUMENTATION_STATUS: test=phase\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
        self.assertTrue(instrumentation_results(raw, ["phase"], "Suite")["passed"])
        for output, expected in ((raw.replace("(1 test)", "(999 tests)"), ["phase"]),
                                 (raw + "OK (1 test)\n", ["phase"]), (raw, ["phase", "phase"]),
                                 (raw.replace("CODE: 0", "CODE: invalid"), ["phase"]), ("OK (0 tests)\n", [])):
            with self.subTest(output=output, expected=expected):
                self.assertFalse(instrumentation_results(output, expected, "Suite")["passed"])


class InstallTransportTest(unittest.TestCase):
    def test_install_transport_retains_flags_and_deadline_on_every_required_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            for profile in load_profiles():
                for apk, flags in (("previous.apk", ()), ("tests.apk", ("-r", "-t")),
                                   ("candidate.apk", ("-r",))):
                    with self.subTest(profile=profile["id"], apk=apk), patch(
                            "android.subprocess.run", return_value=subprocess.CompletedProcess([], 0, b"Success", b"")) as run:
                        self.assertEqual("Success", device.install(apk, *flags))
                    run.assert_called_once_with(
                        ["adb", "-s", "emulator-5554", "install", *flags, apk],
                        capture_output=True, timeout=180)

    def test_install_failures_and_deadlines_propagate(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = Device("emulator-5554", temporary)
            with patch("android.subprocess.run", return_value=subprocess.CompletedProcess(
                    [], 1, b"", b"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")):
                with self.assertRaisesRegex(RuntimeError, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
                    device.install("candidate.apk", "-r")
            with patch("android.subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 180)):
                with self.assertRaises(subprocess.TimeoutExpired):
                    device.install("candidate.apk", "-r")

    def exercise_upgrade(self, profile, *, reject_candidate=False, collector_error=False,
                         stop_at_main=False, identity_override=None):
        """Exercise the real controller's upgrade ordering, then deliberately stop."""
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            previous, candidate, tests = (folder / name for name in ("previous.apk", "candidate.apk", "tests.apk"))
            for apk in (previous, candidate, tests):
                apk.write_bytes(b"synthetic controller input")
            metadata = folder / "previous.json"
            metadata.write_text(json.dumps({"schema": 1, "sha256": sha256(previous), "build": 907,
                                            "tag": "audio-pr2-pr6-build.907", "repository": "battlesbudz/Jarvis-OS-V2"}))
            args = SimpleNamespace(out=str(folder / "evidence"), serial="emulator-5554", adb="adb",
                                   profile=profile["id"], apk=str(candidate), test_apk=str(tests),
                                   previous_apk=str(previous), previous_metadata=str(metadata),
                                   smart_turn_input_dir=str(folder / "model-input"), smart_turn_input_owner="a" * 64,
                                   source_commit="a" * 40, pr_head="b" * 40, allow_emulator_reset=True)
            calls = []

            class UpgradeDevice(Device):
                updated = False

                def run(self, *argv, **kwargs):
                    calls.append((argv, kwargs))
                    if argv[0] == "install":
                        if argv[-1] == str(candidate):
                            if reject_candidate:
                                raise RuntimeError("adb install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                            self.updated = True
                        return "Success"
                    if argv[0] != "shell":
                        return ""
                    command = shlex.split(argv[1])
                    if command[0] == "getprop":
                        values = {"ro.kernel.qemu": "1",
                                  "ro.product.cpu.abilist": "x86_64,arm64-v8a" if profile["arch"] == "x86_64" else "arm64-v8a",
                                  "ro.build.version.sdk": str(profile["api"]), "ro.build.fingerprint": "fixture",
                                  "ro.dalvik.vm.native.bridge": "libndk_translation.so" if profile["arch"] == "x86_64" else "0"}
                        values.update(identity_override or {})
                        return values[command[1]]
                    if command[0] == "getconf":
                        return (identity_override or {}).get("PAGE_SIZE", str(profile["page_size"]))
                    if command[:2] == ["dumpsys", "package"]:
                        return "versionCode=" + ("931" if self.updated else "907")
                    return "Success"

                def instrument(self, test_class, named_tests, evidence_folder, **kwargs):
                    calls.append((("instrument", *named_tests), kwargs))
                    if stop_at_main and test_class.endswith("ReleaseJourneyTest"):
                        raise RuntimeError("Controlled stop at main suite")
                    return "synthetic upgrade phase", {"passed": True, "errors": []}, []

                def snapshot(self, label):
                    if label != "final" and not stop_at_main:
                        raise RuntimeError("Controlled stop after upgrade; later Android gates are not simulated")
                    return "<hierarchy />"

            # This fixture owns upgrade ordering; the separate ART setup suite
            # exercises readiness/admission and collector failure behavior.
            with patch("android.Device", UpgradeDevice), patch("android.RuntimeGcSetup") as setup, \
                    patch("android.SmartTurnInput") as model_input, \
                    patch("android.load_profiles", return_value=[profile]), redirect_stdout(io.StringIO()):
                model_input.return_value.report = {}
                model_input.return_value.remote_model = "/data/local/tmp/jarvis-smart-turn-input-123/model.onnx"
                model_input.return_value.stage.side_effect = lambda *a: calls.append((("model_stage",), {}))
                model_input.return_value.cleanup.side_effect = lambda *a: calls.append((("model_cleanup",), {}))
                setup.return_value.report = {}
                setup.return_value.prepare.side_effect = lambda: calls.append((("gc_prepare",), {}))
                def app_collector(package):
                    calls.append((("gc_app", package), {}))
                    if collector_error:
                        raise RuntimeError("Controlled current-app collector rejection")
                setup.return_value.verify_app.side_effect = app_collector
                self.assertEqual(1, verify(args))
                if profile["id"] == "35-16k-normal":
                    setup.assert_called_once()
                    setup.return_value.prepare.assert_called_once()
                    prepare_index = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "gc_prepare")
                    install_index = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "install")
                    self.assertLess(prepare_index, install_index)
                    if not reject_candidate:
                        setup.return_value.verify_app.assert_called_once_with(PACKAGE)
                        app_index = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "gc_app")
                        self.assertEqual(["am", "start", "-W"], shlex.split(calls[app_index - 1][0][1])[:3])
                else:
                    setup.assert_not_called()
            report = json.loads((Path(args.out) / "report.json").read_text())
            self.assertFalse(report["passed"])
            if identity_override:
                self.assertFalse(any(argv[0] in ("install", "instrument") for argv, _ in calls))
                return report, calls
            installs = [(argv[1:], kwargs) for argv, kwargs in calls if argv[0] == "install"]
            self.assertEqual([((str(previous),), {"timeout": 180}),
                              (("-r", "-t", str(tests)), {"timeout": 180}),
                              (("-r", str(candidate)), {"timeout": 180})], installs)
            return report, calls

    def test_controller_routes_all_upgrade_installs_without_clearing_seeded_storage(self):
        for profile in load_profiles():
            with self.subTest(profile=profile["id"]):
                report, calls = self.exercise_upgrade(profile)
                self.assertTrue(report["upgrade"]["passed"])
                self.assertFalse(report["upgrade"]["data_cleared_during_update"])
                phases = [index for index, (argv, _) in enumerate(calls) if argv[0] == "instrument"]
                self.assertEqual(2, len(phases))
                between = [argv for argv, _ in calls[phases[0] + 1:phases[1]]]
                self.assertEqual(1, sum(argv[0] == "install" for argv in between))
                self.assertFalse(any(argv[0] == "uninstall" or
                                     argv[0] == "shell" and shlex.split(argv[1])[:2] == ["pm", "clear"]
                                     for argv in between))
                self.assertEqual(907, report["upgrade"]["previous_version_code"])
                self.assertEqual(931, report["upgrade"]["candidate_version_code"])
                self.assertIn("Controlled stop after upgrade", report["errors"][0])

    def test_current_app_collector_failure_prevents_main_journeys(self):
        profile = dict(LEGACY_GC_PROFILE)
        report, calls = self.exercise_upgrade(profile, collector_error=True)
        self.assertTrue(report["upgrade"]["passed"])
        self.assertNotIn("instrumentation", report)
        self.assertEqual(2, sum(argv[0] == "instrument" for argv, _ in calls))
        self.assertIn("Controlled current-app collector rejection", report["errors"][0])

    def test_main_suite_uses_declared_budget_and_preserves_all_named_tests(self):
        expected = json.loads((Path(__file__).parent / "scenarios.json").read_text())["tests"]
        for profile in load_profiles():
            with self.subTest(profile=profile["id"]):
                report, calls = self.exercise_upgrade(profile, stop_at_main=True)
                phases = [(argv, kwargs) for argv, kwargs in calls if argv[0] == "instrument"]
                self.assertEqual(3, len(phases))
                self.assertEqual(expected, list(phases[-1][0][1:]))
                self.assertEqual(profile["instrumentation_timeout"], phases[-1][1]["timeout"])
                self.assertEqual([180, 180], [kwargs["timeout"] for _, kwargs in phases[:2]])
                self.assertEqual(profile, report["profile"])
                self.assertIn("Controlled stop at main suite", report["errors"][0])
                self.assertIsNone(phases[0][1]["smart_turn_input"])
                self.assertIsNone(phases[1][1]["smart_turn_input"])
                self.assertEqual("/data/local/tmp/jarvis-smart-turn-input-123/model.onnx", phases[-1][1]["smart_turn_input"])
                stage = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "model_stage")
                resets = [i for i, (argv, _) in enumerate(calls) if argv[0] == "shell" and shlex.split(argv[1])[:2] == ["pm", "clear"]]
                self.assertLess(resets[-1], stage)
                pull = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "pull")
                cleanup = next(i for i, (argv, _) in enumerate(calls) if argv[0] == "model_cleanup")
                self.assertLess(pull, cleanup)

    def test_api36_large_page_profile_requires_actual_api_page_size_and_bridge(self):
        profile = next(p for p in load_profiles() if p["id"] == "36-16k-normal")
        for values in ({"ro.build.version.sdk": "35"}, {"PAGE_SIZE": "4096"},
                       {"ro.product.cpu.abilist": "x86_64"},
                       {"ro.dalvik.vm.native.bridge": "0"}, {"ro.dalvik.vm.native.bridge": ""}):
            with self.subTest(values=values):
                report, _ = self.exercise_upgrade(profile, identity_override=values)
                self.assertTrue(report["errors"])
                self.assertNotIn("upgrade", report)

    def test_rejected_update_does_not_run_upgrade_verification_or_pass(self):
        for profile in load_profiles():
            with self.subTest(profile=profile["id"]):
                report, calls = self.exercise_upgrade(profile, reject_candidate=True)
                self.assertFalse(report["upgrade"]["passed"])
                self.assertEqual(1, sum(argv[0] == "instrument" for argv, _ in calls))
                self.assertIn("INSTALL_FAILED_UPDATE_INCOMPATIBLE", report["errors"][0])


class SnapshotEvidenceTest(unittest.TestCase):
    PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
    NULL_ROOT = 'ERROR: null root node returned by UiTestAutomationBridge.'

    @staticmethod
    def fresh_png():
        """A second valid image with independently different pixel content."""
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) +
                chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\xff')) + chunk(b'IEND', b''))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake_adb = self.root / 'adb'
        self.fake_adb.write_text(f'#!{sys.executable}\n' + '''import json,shlex,shutil,sys
from pathlib import Path
root=Path(__file__).parent
mode=json.loads((root/'mode.json').read_text())
args=sys.argv[3:]
with (root/'calls.jsonl').open('a') as log:
    log.write(json.dumps(args)+'\\n')
if args[0]=='pull':
    if mode.get('failure')=='pull':
        print('failed pull',file=sys.stderr);sys.exit(8)
    shutil.copyfile(root/'device.png',args[2])
    print('1 file pulled')
elif args[0]=='shell':
    command=shlex.split(args[1])
    if command==['rm','-f','/sdcard/jarvis-screen.png']:
        if mode.get('failure')=='remove':
            print('failed removal',file=sys.stderr);sys.exit(6)
        (root/'device.png').unlink(missing_ok=True)
    elif command==['rm','-f','/sdcard/jarvis-window.xml']:
        if mode.get('failure')=='hierarchy_remove':
            print('failed hierarchy removal',file=sys.stderr);sys.exit(10)
        (root/'device.xml').unlink(missing_ok=True)
    elif command[0]=='screencap':
        assert command==['screencap','-p','/sdcard/jarvis-screen.png']
        print('[Warning] Multiple displays were found')
        print('capture stderr diagnostic',file=sys.stderr)
        if mode.get('failure')=='capture':sys.exit(7)
        if mode.get('failure')!='missing':
            (root/'device.png').write_bytes(bytes.fromhex(mode['png']))
    elif command[:2]==['uiautomator','dump']:
        assert command[2]=='/sdcard/jarvis-window.xml'
        if mode.get('null_roots',0):
            diagnostic=mode.get('null_diagnostic','ERROR: null root node returned by UiTestAutomationBridge.')
            exit_code=mode.get('null_exit',0)
            mode['null_roots']-=1
            mode.update(mode.pop('after_null_root',{}))
            (root/'mode.json').write_text(json.dumps(mode))
            sys.stderr.write(diagnostic)
            sys.exit(exit_code)
        if mode.get('failure')=='hierarchy':
            print('failed hierarchy',file=sys.stderr);sys.exit(9)
        if mode.get('failure')=='hierarchy_missing':
            print('ERROR: null root node returned',file=sys.stderr);sys.exit(0)
        (root/'device.xml').write_text(mode.get('xml','<hierarchy />'))
        print('UI hierarchy dumped')
    elif command==['cat','/sdcard/jarvis-window.xml']:
        if not (root/'device.xml').exists():
            print('failed hierarchy read: no new device XML',file=sys.stderr);sys.exit(11)
        print((root/'device.xml').read_text())
    else:raise AssertionError(command)
else:raise AssertionError(args)
''')
        self.fake_adb.chmod(0o755)
        self.device = Device('emulator-5554', self.root / 'evidence', str(self.fake_adb))
        self.mode()

    def mode(self, **changes):
        (self.root / 'mode.json').write_text(json.dumps(dict(png=self.PNG.hex(), **changes)))

    def receipts(self):
        return [json.loads(line) for line in (self.device.out / 'commands.jsonl').read_text().splitlines()]

    def test_device_file_and_pull_preserve_png_bytes_and_both_diagnostic_streams(self):
        stale = '<hierarchy><node text="stale model" /></hierarchy>'
        fresh = '<hierarchy><node text="fresh model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(xml=fresh)
        self.assertEqual(fresh + '\n', self.device.snapshot('baseline'))
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual(fresh, (self.root / 'device.xml').read_text())
        self.assertEqual(fresh + '\n', (self.device.out / 'baseline.xml').read_text())
        receipts = self.receipts()
        self.assertEqual(['shell', 'shell', 'pull', 'shell', 'shell', 'shell'], [r['argv'][3] for r in receipts])
        self.assertEqual('rm -f /sdcard/jarvis-screen.png', receipts[0]['argv'][4])
        self.assertEqual('screencap -p /sdcard/jarvis-screen.png', receipts[1]['argv'][4])
        self.assertEqual(['/sdcard/jarvis-screen.png', str(self.device.out / 'baseline.png')], receipts[2]['argv'][4:])
        self.assertEqual('rm -f /sdcard/jarvis-window.xml', receipts[3]['argv'][4])
        self.assertEqual('uiautomator dump /sdcard/jarvis-window.xml', receipts[4]['argv'][4])
        self.assertEqual('cat /sdcard/jarvis-window.xml', receipts[5]['argv'][4])
        self.assertIn('Multiple displays', receipts[1]['stdout'])
        self.assertIn('capture stderr diagnostic', receipts[1]['stderr'])
        diagnostic = (self.device.out / 'baseline-screencap.txt').read_text()
        self.assertIn('Multiple displays', diagnostic)
        self.assertIn('capture stderr diagnostic', diagnostic)
        self.assertFalse(any('exec-out' in r['argv'] for r in receipts))
        self.assertTrue(all(r['exit'] == 0 for r in receipts))

    def test_capture_and_pull_failures_stop_before_hierarchy_and_retain_diagnostics(self):
        for failure, expected in (('remove', 1), ('capture', 2), ('pull', 3)):
            with self.subTest(failure=failure):
                for f in self.device.out.iterdir(): f.unlink()
                (self.device.out / 'baseline.png').write_bytes(self.PNG)
                self.mode(failure=failure)
                with self.assertRaisesRegex(RuntimeError, 'adb (shell|pull) failed'):
                    self.device.snapshot('baseline')
                receipts = self.receipts()
                self.assertEqual(expected, len(receipts))
                self.assertNotEqual(0, receipts[-1]['exit'])
                self.assertTrue(receipts[-1]['stderr'])
                self.assertFalse((self.device.out / 'baseline.png').exists())
                if failure != 'remove':
                    self.assertIn('capture stderr diagnostic', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_missing_new_capture_cannot_reuse_a_previous_local_or_remote_png(self):
        (self.root / 'device.png').write_bytes(self.PNG)
        (self.device.out / 'baseline.png').write_bytes(self.PNG)
        self.mode(failure='missing')
        with self.assertRaisesRegex(RuntimeError, 'adb pull failed'):
            self.device.snapshot('baseline')
        self.assertFalse((self.root / 'device.png').exists())
        self.assertFalse((self.device.out / 'baseline.png').exists())
        self.assertEqual(3, len(self.receipts()))
        self.assertIn('Multiple displays', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_hierarchy_command_failure_remains_a_failed_snapshot_with_valid_png_and_diagnostics(self):
        self.mode(failure='hierarchy')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy'):
            self.device.snapshot('baseline')
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual(5, len(self.receipts()))
        self.assertIn('Multiple displays', (self.device.out / 'baseline-screencap.txt').read_text())

    def test_zero_exit_dump_without_new_xml_cannot_reuse_stale_local_or_remote_hierarchy(self):
        stale = '<hierarchy><node text="previous model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(failure='hierarchy_missing')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy read: no new device XML'):
            self.device.snapshot('baseline')
        self.assertFalse((self.root / 'device.xml').exists())
        self.assertFalse((self.device.out / 'baseline.xml').exists())
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        receipts = self.receipts()
        self.assertEqual(6, len(receipts))
        self.assertEqual(0, receipts[4]['exit'])
        self.assertIn('null root node', receipts[4]['stderr'])
        self.assertEqual(11, receipts[5]['exit'])
        self.assertIn('no new device XML', receipts[5]['stderr'])

    def test_exact_null_root_retry_recaptures_both_files_and_retains_first_failure(self):
        first_xml = '<hierarchy><node text="first attempt must not pass" /></hierarchy>'
        fresh_xml = '<hierarchy><node text="new second capture" /></hierarchy>'
        fresh_png = self.fresh_png()
        self.assertNotEqual(self.PNG, fresh_png)
        (self.root / 'device.png').write_bytes(b'stale remote pixels')
        (self.root / 'device.xml').write_text(first_xml)
        (self.device.out / 'retried.png').write_bytes(b'stale local pixels')
        (self.device.out / 'retried.xml').write_text(first_xml)
        self.mode(null_roots=1, xml=first_xml,
                  after_null_root={'png': fresh_png.hex(), 'xml': fresh_xml})

        sleep = Mock()
        with patch('android.time', SimpleNamespace(monotonic=time.monotonic, sleep=sleep)):
            self.assertEqual(fresh_xml + '\n', self.device.snapshot('retried'))

        sleep.assert_called_once()
        self.assertEqual(0.5, sleep.call_args.args[0])
        self.assertEqual(self.PNG, (self.device.out / 'retried-attempt-1.png').read_bytes())
        self.assertEqual(fresh_png, (self.device.out / 'retried.png').read_bytes())
        self.assertEqual(fresh_xml + '\n', (self.device.out / 'retried.xml').read_text())
        self.assertEqual(fresh_xml, (self.root / 'device.xml').read_text())
        first_capture = (self.device.out / 'retried-attempt-1-screencap.txt').read_text()
        self.assertIn('Multiple displays', first_capture)
        self.assertIn('capture stderr diagnostic', first_capture)
        self.assertEqual(self.NULL_ROOT, (self.device.out / 'retried-hierarchy-attempt-1.txt').read_text())
        self.assertIn('UI hierarchy dumped', (self.device.out / 'retried-hierarchy-attempt-2.txt').read_text())
        self.assertIn('capture stderr diagnostic', (self.device.out / 'retried-screencap.txt').read_text())
        commands = [r['argv'][3:] for r in self.receipts()]
        first = [['shell', 'rm -f /sdcard/jarvis-screen.png'],
                 ['shell', 'screencap -p /sdcard/jarvis-screen.png'],
                 ['pull', '/sdcard/jarvis-screen.png', str(self.device.out / 'retried.png')],
                 ['shell', 'rm -f /sdcard/jarvis-window.xml'],
                 ['shell', 'uiautomator dump /sdcard/jarvis-window.xml']]
        self.assertEqual(first + first + [['shell', 'cat /sdcard/jarvis-window.xml']], commands)

    def test_second_exact_null_root_fails_without_a_third_attempt_or_cat(self):
        self.mode(null_roots=2, after_null_root={'png': self.fresh_png().hex()})
        sleep = Mock()
        with patch('android.time', SimpleNamespace(monotonic=time.monotonic, sleep=sleep)):
            with self.assertRaisesRegex(RuntimeError, 'no active root after two attempts'):
                self.device.snapshot('retried')
        sleep.assert_called_once_with(0.5)
        receipts = self.receipts()
        self.assertEqual(10, len(receipts))
        self.assertEqual(2, sum('screencap -p' in r['argv'][-1] for r in receipts))
        self.assertEqual(2, sum('uiautomator dump' in r['argv'][-1] for r in receipts))
        self.assertFalse(any(r['argv'][-1] == 'cat /sdcard/jarvis-window.xml' for r in receipts))
        self.assertEqual(self.PNG, (self.device.out / 'retried-attempt-1.png').read_bytes())
        self.assertEqual(self.fresh_png(), (self.device.out / 'retried.png').read_bytes())
        self.assertFalse((self.device.out / 'retried.xml').exists())
        for attempt in (1, 2):
            self.assertEqual(self.NULL_ROOT,
                             (self.device.out / f'retried-hierarchy-attempt-{attempt}.txt').read_text())

    def test_null_root_text_does_not_retry_nonzero_other_diagnostics_or_dump_timeout(self):
        cases = [(self.NULL_ROOT, 9), ('Warning before\n' + self.NULL_ROOT, 0),
                 (self.NULL_ROOT + '\nDifferent failure', 0), ('ERROR: null root node returned', 0)]
        for diagnostic, exit_code in cases:
            with self.subTest(diagnostic=diagnostic, exit_code=exit_code):
                for path in self.device.out.iterdir(): path.unlink()
                self.mode(null_roots=1, null_diagnostic=diagnostic, null_exit=exit_code)
                sleep = Mock()
                with patch('android.time', SimpleNamespace(monotonic=time.monotonic, sleep=sleep)):
                    with self.assertRaisesRegex(RuntimeError, 'adb shell failed'):
                        self.device.snapshot('retried')
                sleep.assert_not_called()
                receipts = self.receipts()
                self.assertEqual(5 if exit_code else 6, len(receipts))
                self.assertEqual(1, sum('screencap -p' in r['argv'][-1] for r in receipts))
                self.assertFalse((self.device.out / 'retried-attempt-1.png').exists())
                self.assertFalse((self.device.out / 'retried.xml').exists())
        for path in self.device.out.iterdir(): path.unlink()
        self.mode()
        real_run = subprocess.run

        def dump_timeout(argv, **kwargs):
            if argv[-1] == 'uiautomator dump /sdcard/jarvis-window.xml':
                raise subprocess.TimeoutExpired(argv, kwargs['timeout'], stderr=self.NULL_ROOT.encode())
            return real_run(argv, **kwargs)

        sleep = Mock()
        with patch('android.subprocess.run', side_effect=dump_timeout), \
                patch('android.time', SimpleNamespace(monotonic=time.monotonic, sleep=sleep)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.device.snapshot('retried')
        sleep.assert_not_called()
        self.assertEqual(5, len(self.receipts()))
        self.assertTrue(self.receipts()[-1]['timed_out'])
        self.assertFalse((self.device.out / 'retried-attempt-1.png').exists())

    def test_retry_settlement_and_all_commands_share_the_original_shrinking_deadline(self):
        self.mode(null_roots=1, after_null_root={'png': self.fresh_png().hex()})
        clock, budgets, sleeps = [0.0], [], []
        real_run = subprocess.run

        def run(*args, **kwargs):
            budgets.append(kwargs['timeout'])
            result = real_run(*args, **kwargs)
            clock[0] += 5
            return result

        def settle(seconds):
            sleeps.append(seconds)
            clock[0] += seconds

        with patch('android.time', SimpleNamespace(monotonic=lambda: clock[0], sleep=settle)), \
                patch('android.subprocess.run', side_effect=run):
            self.assertEqual('<hierarchy />\n', self.device.snapshot('retried'))
        self.assertEqual([0.5], sleeps)
        self.assertEqual([60, 55, 50, 45, 40, 34.5, 29.5, 24.5, 19.5, 14.5, 9.5], budgets)
        self.assertEqual(55.5, clock[0])
        self.assertEqual(11, len(self.receipts()))

    def test_retry_expiry_during_settlement_cannot_begin_another_capture(self):
        self.mode(null_roots=1)
        clock, sleeps = [0.0], []
        real_run = subprocess.run

        def run(argv, **kwargs):
            result = real_run(argv, **kwargs)
            if argv[-1] == 'uiautomator dump /sdcard/jarvis-window.xml':
                clock[0] = 59.8
            return result

        def settle(seconds):
            sleeps.append(seconds)
            clock[0] += seconds

        with patch('android.time', SimpleNamespace(monotonic=lambda: clock[0], sleep=settle)), \
                patch('android.subprocess.run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, '60-second command budget'):
                self.device.snapshot('retried')
        self.assertEqual(1, len(sleeps))
        self.assertAlmostEqual(0.2, sleeps[0])
        self.assertEqual(5, len(self.receipts()))
        self.assertEqual(self.PNG, (self.device.out / 'retried-attempt-1.png').read_bytes())
        self.assertFalse((self.device.out / 'retried.png').exists())
        self.assertFalse((self.device.out / 'retried.xml').exists())
        self.assertFalse((self.device.out / 'retried-hierarchy-attempt-2.txt').exists())

    def test_corrupt_second_capture_fails_without_reusing_first_attempt_evidence(self):
        corrupt_png = self.PNG[:-1] + b'\x00'
        for changed, error, pattern, command_count in (
                ({'png': corrupt_png.hex()}, RuntimeError, 'Invalid screenshot PNG', 8),
                ({'png': self.fresh_png().hex(), 'xml': 'not xml'}, ParseError, None, 11),
                ({'png': self.fresh_png().hex(), 'xml': '<diagnostic />'}, RuntimeError,
                 'expected hierarchy root', 11)):
            with self.subTest(changed=changed):
                for path in self.device.out.iterdir(): path.unlink()
                self.mode(null_roots=1, after_null_root=changed)
                sleep = Mock()
                with patch('android.time', SimpleNamespace(monotonic=time.monotonic, sleep=sleep)):
                    assertion = self.assertRaisesRegex(error, pattern) if pattern else self.assertRaises(error)
                    with assertion:
                        self.device.snapshot('retried')
                sleep.assert_called_once_with(0.5)
                self.assertEqual(command_count, len(self.receipts()))
                self.assertEqual(self.PNG, (self.device.out / 'retried-attempt-1.png').read_bytes())
                self.assertEqual(bytes.fromhex(changed['png']), (self.device.out / 'retried.png').read_bytes())
                if 'xml' in changed:
                    self.assertEqual(changed['xml'] + '\n', (self.device.out / 'retried.xml').read_text())
                else:
                    self.assertFalse((self.device.out / 'retried.xml').exists())

    def test_failed_remote_hierarchy_removal_stops_before_dump_and_retains_receipt(self):
        stale = '<hierarchy><node text="previous model" /></hierarchy>'
        (self.root / 'device.xml').write_text(stale)
        (self.device.out / 'baseline.xml').write_text(stale)
        self.mode(failure='hierarchy_remove')
        with self.assertRaisesRegex(RuntimeError, 'failed hierarchy removal'):
            self.device.snapshot('baseline')
        self.assertFalse((self.device.out / 'baseline.xml').exists())
        self.assertEqual(stale, (self.root / 'device.xml').read_text())
        receipts = self.receipts()
        self.assertEqual(4, len(receipts))
        self.assertEqual(10, receipts[-1]['exit'])
        self.assertFalse(any('uiautomator dump' in r['argv'][-1] for r in receipts))

    def test_warning_prefixed_truncated_and_crc_corrupt_pngs_are_rejected_intact(self):
        for png in (b'[Warning] Multiple displays\n' + self.PNG, self.PNG[:-3], self.PNG[:-1] + b'\x00'):
            with self.subTest(png=png):
                for f in self.device.out.iterdir(): f.unlink()
                (self.root / 'mode.json').write_text(json.dumps({'png': png.hex()}))
                with self.assertRaisesRegex(RuntimeError, 'Invalid screenshot PNG'):
                    self.device.snapshot('baseline')
                self.assertEqual(png, (self.device.out / 'baseline.png').read_bytes())
                self.assertEqual(3, len(self.receipts()))

    def test_invalid_hierarchy_still_fails_after_valid_screenshot(self):
        self.mode(xml='not xml')
        with self.assertRaises(ParseError):
            self.device.snapshot('baseline')
        self.assertEqual(self.PNG, (self.device.out / 'baseline.png').read_bytes())
        self.assertEqual('not xml\n', (self.device.out / 'baseline.xml').read_text())
        self.assertEqual(6, len(self.receipts()))

    def test_fresh_well_formed_xml_requires_a_hierarchy_root(self):
        self.mode(xml='<diagnostic />')
        with self.assertRaisesRegex(RuntimeError, 'expected hierarchy root'):
            self.device.snapshot('baseline')
        self.assertEqual('<diagnostic />\n', (self.device.out / 'baseline.xml').read_text())
        self.assertEqual(6, len(self.receipts()))

    def test_timeout_retains_partial_diagnostics_and_propagates(self):
        timeout = subprocess.TimeoutExpired('adb', 60, output=b'partial capture', stderr=b'timed out')
        with patch('android.subprocess.run', side_effect=[subprocess.CompletedProcess([], 0, b'', b''), timeout]):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.device.snapshot('baseline')
        removed, receipt = self.receipts()
        self.assertEqual('rm -f /sdcard/jarvis-screen.png', removed['argv'][4])
        self.assertIsNone(receipt['exit'])
        self.assertTrue(receipt['timed_out'])
        self.assertEqual('partial capture', receipt['stdout'])
        self.assertEqual('timed out', receipt['stderr'])
        diagnostic = (self.device.out / 'baseline-screencap.txt').read_text()
        self.assertIn('partial capture', diagnostic)
        self.assertIn('timed out', diagnostic)

    def test_capture_pull_and_hierarchy_share_existing_60_second_budget(self):
        clock, budgets = [0.0], []
        real_run = subprocess.run

        def run(*args, **kwargs):
            budgets.append(kwargs['timeout'])
            result = real_run(*args, **kwargs)
            clock[0] += 10
            return result

        with patch('android.time.monotonic', side_effect=lambda: clock[0]), patch('android.subprocess.run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, '60-second command budget'):
                self.device.snapshot('baseline')
        self.assertEqual([60, 50, 40, 30, 20, 10], budgets)
        self.assertEqual(6, len(self.receipts()))
        self.assertFalse((self.device.out / 'baseline.xml').exists())


if __name__ == "__main__":
    unittest.main()
