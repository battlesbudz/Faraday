#!/usr/bin/env python3
"""Task-owned, temporary Smart Turn input. Model bytes never belong in evidence/APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import time
import urllib.request
import zipfile

FILE_NAME = "smart-turn-v3.2-cpu.onnx"
MODEL_BYTES = 8_679_182
MODEL_SHA256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
MODEL_URL = ("https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/"
             "f766f81d3cfdf7737ac64aad813d91bbfd56bf93/" + FILE_NAME)
PACKAGE = "com.battlesbudz.jarvis.v2"
OWNER_FILE = ".jarvis-smart-turn-owner.json"


def allocate(parent, github_output=None):
    """Publish coordinates only after this invocation acknowledged creating them."""
    directory = Path(parent).resolve() / ("jarvis-smart-turn-input-" + secrets.token_hex(16))
    owner = secrets.token_hex(32)
    # Before successful return from mkdir we have no ownership, even if an I/O
    # failure leaves creation uncertain. Never clean up a collision/unowned path.
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    try:
        identity = directory.stat()
        with (directory / OWNER_FILE).open("x") as output:
            json.dump({"schema": 1, "owner": owner, "directory": str(directory),
                       "device": identity.st_dev, "inode": identity.st_ino}, output)
        if github_output is not None:
            with Path(github_output).open("a") as output:
                output.write(f"directory={directory}\nowner={owner}\n")
    except BaseException:
        # This function did acknowledge mkdir; only its own receipt can exist.
        (directory / OWNER_FILE).unlink(missing_ok=True)
        directory.rmdir()
        raise
    return directory, owner


def check_owner(directory, owner):
    directory = Path(directory)
    marker = directory / OWNER_FILE
    if (not isinstance(owner, str) or re.fullmatch(r"[0-9a-f]{64}", owner) is None or
            directory.is_symlink() or marker.is_symlink() or not marker.is_file() or marker.stat().st_size > 1024):
        raise RuntimeError("Smart Turn host input ownership is not established")
    try:
        receipt = json.loads(marker.read_text())
    except (OSError, ValueError) as error:
        raise RuntimeError("Smart Turn host input ownership receipt is unreadable") from error
    identity = directory.stat()
    expected = {"schema": 1, "owner": owner, "directory": str(directory.resolve()),
                "device": identity.st_dev, "inode": identity.st_ino}
    if receipt != expected:
        raise RuntimeError("Smart Turn host input ownership does not match this directory/attempt")


def verified_model(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size != MODEL_BYTES:
        raise RuntimeError("Smart Turn input size/type mismatch")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        count = 0
        while True:
            chunk = stream.read(min(64 * 1024, MODEL_BYTES + 1 - count))
            if not chunk:
                break
            count += len(chunk)
            if count > MODEL_BYTES:
                raise RuntimeError("Smart Turn input grew beyond pinned size")
            digest.update(chunk)
    if count != MODEL_BYTES or digest.hexdigest() != MODEL_SHA256:
        raise RuntimeError("Smart Turn input SHA-256 mismatch")


def cleanup_host(directory, owner):
    """Delete only acknowledged, identity-bound attempt inputs; never recursively."""
    directory = Path(directory)
    if directory.is_symlink():
        raise RuntimeError("Refusing symlinked Smart Turn input directory")
    if not directory.exists():
        return  # An already removed owned directory is an idempotent cleanup.
    check_owner(directory, owner)
    if any(path.name not in (OWNER_FILE, FILE_NAME, FILE_NAME + ".part") for path in directory.iterdir()):
        raise RuntimeError("Unexpected Smart Turn input contents preserved")
    for name in (FILE_NAME, FILE_NAME + ".part"):
        (directory / name).unlink(missing_ok=True)
    (directory / OWNER_FILE).unlink()
    directory.rmdir()
    if directory.exists():
        raise RuntimeError("Smart Turn host cleanup could not be verified")


def fetch(directory, owner):
    directory = Path(directory)
    check_owner(directory, owner)
    if {path.name for path in directory.iterdir()} != {OWNER_FILE}:
        raise RuntimeError("Smart Turn input must be freshly allocated")
    target = directory / FILE_NAME
    partial = directory / (FILE_NAME + ".part")
    try:
        started, count = time.monotonic(), 0
        # One transfer, no retry or alternate model; outer CI step also has a wall limit.
        with urllib.request.urlopen(MODEL_URL, timeout=15) as response, partial.open("xb") as output:
            while True:
                if time.monotonic() - started >= 90:
                    raise RuntimeError("Smart Turn input transfer exceeded 90 seconds")
                chunk = response.read(min(64 * 1024, MODEL_BYTES + 1 - count))
                if not chunk:
                    break
                count += len(chunk)
                if count > MODEL_BYTES:
                    raise RuntimeError("Smart Turn input exceeds pinned size")
                output.write(chunk)
        verified_model(partial)
        partial.rename(target)
    except BaseException:
        cleanup_host(directory, owner)
        raise


def exclude_from_apks(apks):
    for path in apks:
        with zipfile.ZipFile(path) as apk:
            for entry in apk.infolist():
                if entry.filename.rsplit("/", 1)[-1] == FILE_NAME:
                    raise RuntimeError("Smart Turn weights must not be packaged in either APK")
                if entry.file_size == MODEL_BYTES and hashlib.sha256(apk.read(entry)).hexdigest() == MODEL_SHA256:
                    raise RuntimeError("Renamed Smart Turn weights found in APK")


def stop_target(device):
    """Stop only the caller's admitted disposable app; require affirmative absence evidence."""
    device.shell("am", "force-stop", PACKAGE, timeout=10)
    # pidof exit 1 means absent; an empty stdout from a broken adb/command is not proof.
    absent = device.shell("sh", "-c", f"pidof {PACKAGE} >/dev/null; status=$?; "
                          'if [ "$status" -eq 1 ]; then printf JARVIS_PROCESS_ABSENT; '
                          'elif [ "$status" -eq 0 ]; then printf JARVIS_PROCESS_RUNNING; '
                          'else exit "$status"; fi', timeout=10).strip()
    if absent != "JARVIS_PROCESS_ABSENT":
        raise RuntimeError("Smart Turn target process absence was not acknowledged")


class SmartTurnInput:
    def __init__(self, directory, evidence, token, owner):
        self.directory = Path(directory)
        self.owner = owner
        self.model = self.directory / FILE_NAME
        self.remote_directory = f"/data/local/tmp/jarvis-smart-turn-input-{token}"
        if re.fullmatch(r"[0-9]+", str(token)) is None:
            raise RuntimeError("Invalid Smart Turn input identity")
        evidence = Path(evidence).resolve()
        source = self.directory.resolve()
        if source == evidence or source.is_relative_to(evidence) or evidence.is_relative_to(source):
            raise RuntimeError("Smart Turn inputs and evidence must be separate directories")
        check_owner(self.directory, self.owner)
        self.remote_owned = False
        self.report = {"model_bytes": MODEL_BYTES, "model_sha256": MODEL_SHA256,
                       "host_ownership_verified": True,
                       "host_verified": False, "device_verified": False,
                       "host_cleanup": "pending", "device_cleanup": "not_created",
                       "private_copy_cleanup": "not_created"}

    @property
    def remote_model(self):
        return self.remote_directory + "/" + FILE_NAME

    def stage(self, device, apks):
        check_owner(self.directory, self.owner)
        verified_model(self.model)
        exclude_from_apks(apks)
        self.report["host_verified"] = True
        # Called only after emulator admission and candidate fresh reset.
        # Only an acknowledged successful mkdir transfers ownership. A collision
        # is not ours; a lost response is uncertain and must not authorize deletion.
        self.report["device_cleanup"] = "uncertain"
        created = device.shell("sh", "-c", f"if mkdir {self.remote_directory}; then "
                               "printf JARVIS_INPUT_CREATED; else printf JARVIS_INPUT_REJECTED; fi", timeout=10).strip()
        if created == "JARVIS_INPUT_REJECTED":
            self.report["device_cleanup"] = "not_owned"
            raise RuntimeError("Smart Turn input directory creation rejected; existing path preserved")
        if created != "JARVIS_INPUT_CREATED":
            raise RuntimeError("Smart Turn input directory ownership was not acknowledged")
        self.remote_owned = True
        self.report.update(device_cleanup="pending", private_copy_cleanup="pending")
        device.run("push", str(self.model), self.remote_model, timeout=30)
        digest = device.shell("sha256sum", self.remote_model, timeout=15).split()
        size = device.shell("stat", "-c", "%s", self.remote_model, timeout=10).strip()
        if not digest or digest[0] != MODEL_SHA256 or size != str(MODEL_BYTES):
            raise RuntimeError("Smart Turn emulator input identity mismatch")
        self.report["device_verified"] = True

    def cleanup(self, device):
        errors = []
        if self.remote_owned:
            # A wedged native worker cannot be killed safely in Java. Stop its
            # disposable process before removing inputs; no concurrent native close.
            try:
                stop_target(device)
                if device.shell("pm", "clear", PACKAGE, timeout=15).strip() != "Success":
                    raise RuntimeError("Smart Turn private input cleanup was not acknowledged")
                self.report["private_copy_cleanup"] = "verified"
            except Exception:
                self.report["private_copy_cleanup"] = "uncertain"
                errors.append("Smart Turn private input/process cleanup could not be verified")
            try:
                device.shell("rm", "-f", self.remote_model, timeout=10)
                device.shell("rmdir", self.remote_directory, timeout=10)
                device.shell("test", "!", "-e", self.remote_directory, timeout=10)
                self.report["device_cleanup"] = "verified"
            except Exception:
                self.report["device_cleanup"] = "uncertain"
                errors.append("Smart Turn emulator input cleanup could not be verified")
        elif self.report["device_cleanup"] == "uncertain":
            errors.append("Smart Turn input creation outcome uncertain; unowned path preserved")
        try:
            cleanup_host(self.directory, self.owner)
            self.report["host_cleanup"] = "verified"
        except Exception:
            self.report["host_cleanup"] = "uncertain"
            errors.append("Smart Turn host input cleanup could not be verified")
        if errors:
            raise RuntimeError("; ".join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("allocate", "fetch", "cleanup"))
    parser.add_argument("--directory")
    parser.add_argument("--owner")
    parser.add_argument("--parent")
    parser.add_argument("--github-output")
    args = parser.parse_args()
    if args.action == "allocate":
        if not args.parent: parser.error("allocate requires --parent")
        directory, owner = allocate(args.parent, args.github_output)
        if args.github_output is None:
            print(json.dumps({"directory": str(directory), "owner": owner}))
    else:
        if not args.directory or not args.owner: parser.error("fetch/cleanup require --directory and --owner")
        (fetch if args.action == "fetch" else cleanup_host)(args.directory, args.owner)
    print(json.dumps({"action": args.action, "passed": True,
                      "model_bytes": MODEL_BYTES, "model_sha256": MODEL_SHA256}))


if __name__ == "__main__":
    main()
