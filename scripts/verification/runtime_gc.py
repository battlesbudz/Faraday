#!/usr/bin/env python3
"""A bounded ART CC setup for the API 35 x86-64 16 KB emulator only.

The DeviceConfig override and reboot are exercised by AOSP ART's odrefresh test:
https://android.googlesource.com/platform/art/+/8222aa2d2df6273da689f0edd3913e8370c0c1c2
This is emulator compatibility setup, not physical-device/performance coverage.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

try:
    from .profiles import load_profiles
except ImportError:
    from profiles import load_profiles

# Historical API35-only repair; the required API36 image boots with stock runtime settings.
PROFILE_ID = "35-16k-normal"
NAMESPACE = "runtime_native_boot"
FLAG = "force_disable_uffd_gc"
PROPERTY = f"persist.device_config.{NAMESPACE}.{FLAG}"
BOOT_ID = "/proc/sys/kernel/random/boot_id"
SERVICES = ("activity", "package", "window", "input")
LOG_LINES = 20000
PROFILE = {"id": PROFILE_ID, "api": 35, "apk": "app-release", "device_profile": "pixel_2",
           "screen_profile": "phone", "page_size": 16384, "target": "google_apis_ps16k",
           "runner": "ubuntu-latest", "arch": "x86_64", "acceleration": "kvm",
           "boot_timeout": 300, "job_timeout": 40, "instrumentation_timeout": 900}


def require_profile(profile):
    if profile != PROFILE:
        raise RuntimeError("ART CC setup requires the unchanged API 35 x86-64 16 KB profile")


def boot_directory(out):
    out = Path(out)
    return out.with_name(out.name + "-16kb-boot")


def prelaunch(profile, out, source_commit, *, now=time.monotonic):
    require_profile(profile)
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise RuntimeError("ART CC setup requires an exact source commit")
    out = boot_directory(out)
    out.mkdir(parents=True, exist_ok=False)
    # Linux CLOCK_MONOTONIC is shared between these two processes. Reject receipts
    # from another host boot, run/attempt, source or profile; never reset the timer.
    receipt = {"schema": 1, "profile": profile, "source_commit": source_commit,
               "host_boot_id": Path(BOOT_ID).read_text().strip(),
               "run_id": os.getenv("GITHUB_RUN_ID", ""), "run_attempt": os.getenv("GITHUB_RUN_ATTEMPT", ""),
               "started_monotonic": now()}
    receipt["deadline_monotonic"] = receipt["started_monotonic"] + 300
    (out / "prelaunch.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def process_pair(raw):
    """Bind the current system server to its real zygote parent, not app_process."""
    rows = [line.split() for line in raw.splitlines()]
    rows = [row for row in rows if len(row) == 3 and row[0].isdigit() and row[1].isdigit()]
    servers = [row for row in rows if row[2] == "system_server"]
    if len(servers) != 1:
        return None
    server = servers[0]
    parents = [row for row in rows if row[0] == server[1] and row[2] == "zygote64"]
    if len(parents) != 1:
        return None
    return {"system_server": int(server[0]), "zygote64": int(server[1])}


def process_stat(raw, pid):
    # /proc/pid/stat field 22 is boot-relative process start time in clock ticks.
    # Split at the final closing comm parenthesis; comm may contain spaces or ')'.
    prefix, separator, fields = raw.rpartition(") ")
    values = fields.split()
    if (not separator or not prefix.startswith(f"{pid} (") or len(values) < 20 or
            not values[1].isdigit() or not values[19].isdigit() or int(values[19]) <= 0):
        raise RuntimeError("Missing/invalid current process start-time identity")
    return {"pid": pid, "parent_pid": int(values[1]), "start_ticks": int(values[19])}


def collector_lines(raw, pid):
    # ART heap.cc logs this exact string at post-fork collector selection.
    # Match the emitter PID, not a PID quoted in a crash dump or app_process log.
    pattern = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+[ \t]+" + str(pid) +
                         r"[ \t]+\d+[ \t]+I[ \t]+[^:\r\n]+:[ \t]+Using (CollectorType\w+) GC\.[ \t]*$", re.MULTILINE)
    return [(match.group(1), match.group(0)) for match in pattern.finditer(raw)]


def odrefresh_compilation(raw):
    # odsign's verification property also becomes 1 after partial compilation.
    # Require its explicit complete-compilation branch for this fresh-AVD trial.
    pattern = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+[ \t]+\d+[ \t]+\d+[ \t]+"
                         r"([IWEF])[ \t]+(odsign|odrefresh)[ \t]*:[ \t]+([^\r\n]+)$", re.MULTILINE)
    complete = None
    for match in pattern.finditer(raw):
        level, tag, message = match.groups()
        if ((tag == "odsign" and message.startswith(("odrefresh compiled partial artifacts, returned ",
                "odrefresh failed cleaning up existing artifacts", "odrefresh exited unexpectedly, returned "))) or
                (tag == "odrefresh" and message.startswith("Compilation failed, stage: "))):
            raise RuntimeError("Post-reboot ART artifact regeneration failed: " + match.group(0))
        # Android 15 ExitCode::kCompilationSuccess is EX__MAX + 2 == 80.
        if level == "I" and tag == "odsign" and message == "odrefresh compiled all artifacts, returned 80":
            complete = match.group(0)
    return complete


class RuntimeGcSetup:
    """Own just the single setting/reboot admission; android.py owns all app gates."""
    def __init__(self, device, profile, source_commit, *, now=time.monotonic, pause=time.sleep):
        require_profile(profile)
        self.device, self.now, self.pause = device, now, pause
        self.out = boot_directory(device.out)
        self.report = {"passed": False, "flag": f"{NAMESPACE}/{FLAG}", "reboots": 0,
                       "boot_timeout_seconds": 300, "observations": []}
        try:
            receipt = json.loads((self.out / "prelaunch.json").read_text())
        except (OSError, ValueError) as error:
            raise RuntimeError(f"Missing/invalid ART CC prelaunch deadline: {error}") from error
        if (not isinstance(receipt, dict) or receipt.get("schema") != 1 or receipt.get("profile") != profile or
                receipt.get("source_commit") != source_commit or
                receipt.get("host_boot_id") != Path(BOOT_ID).read_text().strip() or
                receipt.get("run_id") != os.getenv("GITHUB_RUN_ID", "") or
                receipt.get("run_attempt") != os.getenv("GITHUB_RUN_ATTEMPT", "")):
            raise RuntimeError("ART CC prelaunch receipt identity disagrees with this verification")
        started, deadline = receipt.get("started_monotonic"), receipt.get("deadline_monotonic")
        if (type(started) not in (int, float) or type(deadline) not in (int, float) or
                not math.isfinite(started) or not math.isfinite(deadline) or
                not 0 <= started <= now() or deadline - started != 300):
            raise RuntimeError("Invalid original 300-second ART CC boot deadline")
        self.deadline = deadline
        self.report["prelaunch"] = receipt
        self.report["status"] = "pending"
        self.save()

    def save(self):
        (self.out / "setup.json").write_text(json.dumps(self.report, indent=2) + "\n")

    def remaining(self, deadline=None):
        budget = (self.deadline if deadline is None else deadline) - self.now()
        if budget <= 0:
            raise RuntimeError("ART CC setup exceeded its original 300-second boot deadline" if deadline is None
                               else "Jarvis collector receipt exceeded its 60-second command deadline")
        return budget

    def command(self, *args, deadline=None, check=True):
        result = self.device.run(*args, timeout=min(10, self.remaining(deadline)), check=check)
        self.remaining(deadline)  # A late success never admits installation/tests.
        return result.strip()

    def shell(self, *args, **kwargs):
        return self.command("shell", shlex.join(args), **kwargs)

    def wait(self):
        self.pause(min(1, self.remaining()))
        self.remaining()

    def boot_id(self, *, deadline=None, check=True):
        value = self.shell("cat", BOOT_ID, deadline=deadline, check=check)
        return value if re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value) else None

    def identity(self):
        identity = {key: self.shell("getprop", prop) for key, prop in
                    (("emulator", "ro.kernel.qemu"), ("api", "ro.build.version.sdk"),
                     ("abi", "ro.product.cpu.abilist"), ("native_bridge", "ro.dalvik.vm.native.bridge"),
                     ("fingerprint", "ro.build.fingerprint"))}
        identity["page_size"] = self.shell("getconf", "PAGE_SIZE")
        identity["selinux"] = self.shell("getenforce")
        if (identity["emulator"] != "1" or not re.fullmatch(r"emulator-\d+", self.device.serial) or
                identity["api"] != "35" or identity["page_size"] != "16384" or
                "x86_64" not in identity["abi"].split(",") or "arm64-v8a" not in identity["abi"].split(",") or
                identity["native_bridge"] in ("", "0") or not identity["fingerprint"] or identity["selinux"] != "Enforcing"):
            raise RuntimeError("ART CC setup refuses a nonmatching emulator/API/page size/ABI/bridge/SELinux identity")
        return identity

    def process(self, pid, *, deadline=None):
        return process_stat(self.shell("cat", f"/proc/{pid}/stat", deadline=deadline), pid)

    def processes(self, *, deadline=None):
        raw = self.shell("ps", "-A", "-o", "PID,PPID,NAME", deadline=deadline)
        (self.out / "current-processes.txt").write_text(raw + "\n")
        pair = process_pair(raw)
        if pair is None:
            return None
        server = self.process(pair["system_server"], deadline=deadline)
        zygote = self.process(pair["zygote64"], deadline=deadline)
        if server["parent_pid"] != zygote["pid"] or zygote["parent_pid"] != 1:
            raise RuntimeError("Current system_server/zygote ancestry changed")
        return dict(pair, system_server_start_ticks=server["start_ticks"],
                    zygote64_start_ticks=zygote["start_ticks"])

    def flag(self):
        return {"device_config": self.shell("device_config", "get", NAMESPACE, FLAG),
                "property": self.shell("getprop", PROPERTY)}

    def capture(self, name, *, deadline=None):
        raw = self.command("logcat", "-b", "all", "-d", "-v", "threadtime", "-t", str(LOG_LINES), deadline=deadline)
        (self.out / name).write_text(raw + "\n")
        return raw

    def prepare(self):
        try:
            self.remaining()
            self.report["status"] = "configure"
            self.report["initial_identity"] = self.identity()
            before = self.boot_id()
            if before is None:
                raise RuntimeError("Missing initial Android boot identity")
            self.report["initial_boot_id"] = before
            self.report["initial_processes"] = self.processes()
            self.capture("initial-boot-logcat.txt")
            self.shell("device_config", "put", NAMESPACE, FLAG, "true")
            while True:
                self.report["configured_flag"] = self.flag()
                self.save()
                if set(self.report["configured_flag"].values()) == {"true"}:
                    break
                self.wait()
            if self.boot_id() != before:
                raise RuntimeError("Android rebooted before the intentional ART CC boundary")
            self.capture("initial-boot-logcat.txt")
            self.report["reboots"] = 1
            self.report["status"] = "intentional_reboot"
            self.report["reboot_monotonic"] = self.now()
            self.save()
            self.command("reboot")
            self.await_reboot(before)
            self.report["ready_monotonic"] = self.now()
            self.remaining()
            self.report["passed"] = True
            self.report["status"] = "ready"
        except Exception as error:
            self.report["status"] = "failed"
            self.report["error"] = str(error)
            raise
        finally:
            self.save()

    def await_reboot(self, before):
        observed_boot, observed_pair, cc_receipt, compiled_receipt = None, None, None, None
        while True:
            try:
                current_boot = self.boot_id(check=False)
            except subprocess.TimeoutExpired:
                # adb may block while the one requested reboot drops transport.
                # Only this read-only pre-reconnection probe may be retried.
                if observed_boot is not None:
                    raise
                self.report["reboot_poll_timeouts"] = self.report.get("reboot_poll_timeouts", 0) + 1
                self.save()
                self.wait()
                continue
            if current_boot is None or current_boot == before:
                if observed_boot is not None:
                    raise RuntimeError("Android boot identity disappeared after the intentional reboot")
                self.wait()
                continue
            if observed_boot is not None and current_boot != observed_boot:
                raise RuntimeError("Unexpected second Android reboot during ART CC setup")
            observed_boot = self.report["boot_id"] = current_boot
            pair = self.processes()
            raw = self.capture("post-reboot-logcat.txt")
            if self.boot_id() != current_boot:
                raise RuntimeError("Android boot identity changed during ART regeneration capture")
            compiled_receipt = odrefresh_compilation(raw) or compiled_receipt
            if compiled_receipt:
                self.report["odrefresh_compilation"] = {"boot_id": current_boot, "line": compiled_receipt}
                (self.out / "odrefresh-compilation.txt").write_text(compiled_receipt + "\n")
            if pair is None:
                if observed_pair is not None:
                    raise RuntimeError("System server/zygote disappeared after the intentional reboot")
                self.wait()
                continue
            if observed_pair is not None and pair != observed_pair:
                raise RuntimeError("System server/zygote changed after the intentional reboot")
            if (self.boot_id() != current_boot or
                    self.processes() != pair):
                raise RuntimeError("Android process/boot identity changed during collector capture")
            observed_pair = self.report["processes"] = pair
            choices = collector_lines(raw, pair["system_server"])
            if any(choice != "CollectorTypeCC" for choice, _ in choices):
                raise RuntimeError("Current system_server selected a collector other than CC")
            if choices:
                cc_receipt = choices[-1][1]
                (self.out / "system-server-collector.txt").write_text(cc_receipt + "\n")
            state = {"boot_id": current_boot, "processes": pair,
                     "boot_completed": self.shell("getprop", "sys.boot_completed"),
                     "odsign_verification": self.shell("getprop", "odsign.verification.success"),
                     "collector_receipt": cc_receipt, "compiled_all_artifacts": bool(compiled_receipt)}
            self.report["observations"].append(state)
            self.save()
            if state["boot_completed"] != "1" or state["odsign_verification"] != "1" or not cc_receipt or not compiled_receipt:
                self.wait()
                continue
            for service in SERVICES:
                if self.shell("service", "check", service) != f"Service {service}: found":
                    break
            else:
                self.report["post_reboot_flag"] = self.flag()
                if set(self.report["post_reboot_flag"].values()) != {"true"}:
                    raise RuntimeError("ART CC DeviceConfig override did not survive reboot")
                self.shell("input", "keyevent", "KEYCODE_WAKEUP")
                self.shell("input", "keyevent", "82")
                self.shell("wm", "dismiss-keyguard")
                if (self.boot_id() != current_boot or
                        self.processes() != pair):
                    raise RuntimeError("Android process/boot identity changed during readiness")
                self.report["identity"] = self.identity()
                if self.report["identity"] != self.report["initial_identity"]:
                    raise RuntimeError("Android runtime identity changed across the ART CC reboot")
                self.remaining()
                return
            self.wait()

    def verify_app(self, package):
        """Immediately retain this real first-launch process receipt before ring loss."""
        deadline = self.now() + 60
        receipt = {"passed": False}
        self.report["jarvis_collector"] = receipt
        try:
            if not self.report["passed"]:
                raise RuntimeError("Jarvis collector check requires successful ART CC admission")
            before = self.boot_id(deadline=deadline)
            pid = self.shell("pidof", package, deadline=deadline)
            if before != self.report["boot_id"] or not re.fullmatch(r"[1-9][0-9]*", pid):
                raise RuntimeError("No current Jarvis process in the admitted Android boot")
            process = self.process(int(pid), deadline=deadline)
            receipt.update(boot_id=before, process=process)
            raw = self.capture("jarvis-first-launch-logcat.txt", deadline=deadline)
            choices = collector_lines(raw, int(pid))
            receipt["collector_lines"] = [line for _, line in choices]
            if not choices or any(choice != "CollectorTypeCC" for choice, _ in choices):
                raise RuntimeError("Current Jarvis process did not report CollectorTypeCC")
            if (self.shell("pidof", package, deadline=deadline) != pid or
                    self.process(int(pid), deadline=deadline) != process or
                    self.boot_id(deadline=deadline) != before or
                    self.processes(deadline=deadline) != self.report["processes"]):
                raise RuntimeError("Jarvis/system process identity changed during collector receipt")
            self.remaining(deadline)
            receipt["passed"] = True
        except Exception as error:
            receipt["error"] = str(error)
            raise
        finally:
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--out", required=True, help="Controller output directory, still nonexistent")
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    profile = next((profile for profile in load_profiles() if profile["id"] == args.profile), None)
    prelaunch(profile, args.out, args.source_commit)


if __name__ == "__main__":
    main()
