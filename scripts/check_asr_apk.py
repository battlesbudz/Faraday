#!/usr/bin/env python3
"""Verify Whisper and shared Sherpa TTS/VAD runtime packaging and Moonshine retirement."""
import argparse
import hashlib
import gzip
import json

from sherpa_jni_profile import REMOVED_CLASSES
from prepare_whisper_qnn import FILES as QNN_FILES, DSP as QNN_DSP, check_elf, asset_name
import re
from pathlib import Path
import subprocess
import tempfile
import zipfile


# Public Android platform libraries used by the bundled runtimes. The C++ STL
# is an app dependency, not a platform library, and must never be allowed here.
ANDROID_LIBRARIES = {
    'libandroid.so', 'libc.so', 'libdl.so', 'liblog.so', 'libm.so', 'libz.so',
    'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libOpenSLES.so',
    'libaaudio.so', 'libmediandk.so', 'libjnigraphics.so', 'libvulkan.so',
    'libnativewindow.so', 'libneuralnetworks.so',
}


def check_dependencies(metadata):
    for name, dynamic in metadata.items():
        needed = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic))
        missing = needed - metadata.keys() - ANDROID_LIBRARIES
        assert not missing, f'{name}: missing packaged native dependencies: {sorted(missing)}'


RETIRED_MOONSHINE_LIBRARIES = frozenset({
    "libmoonshine.so", "libmoonshine-jni.so", "libms_ort_1232.so",
})


def check_retired_moonshine(archive):
    """Reject retired dependencies in every ABI and DEX, including stale build outputs."""
    for entry in archive.namelist():
        if entry.startswith("lib/"):
            assert Path(entry).name not in RETIRED_MOONSHINE_LIBRARIES, f"Retired Moonshine library packaged: {entry}"
        if re.fullmatch(r"classes(?:[0-9]+)?\.dex", entry):
            assert b"Lai/moonshine/voice/" not in archive.read(entry), f"Retired Moonshine SDK classes packaged: {entry}"


def check_qnn_asset_names(names):
    prefix = "assets/whisper-qnn-runtime/"
    actual = [n.removeprefix(prefix) for n in names if n.startswith(prefix) and not n.endswith('/')]
    expected = {asset_name(n) for n in QNN_FILES} | {"manifest.json"}
    assert len(actual) == len(expected) and set(actual) == expected, (
        f"QNN packaged asset names changed: expected {sorted(expected)}, got {sorted(actual)}")


def check(apk):
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        names = archive.namelist()
        check_retired_moonshine(archive)
        prefix = "lib/arm64-v8a/"
        required = ["libsherpa-onnx-jni.so", "libonnxruntime.so", "libmicrowakeword.so",
                    "libc++_shared.so"]
        for retired in ["libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so"]:
            assert prefix + retired not in names, f"Unused native wrapper packaged: {retired}"
        assert "assets/voice/silero_vad.onnx" in names, "Missing speech detector model"
        assert hashlib.sha256(archive.read("assets/microwakeword/hey_jarvis.tflite")).hexdigest() == "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77", "Wrong microWakeWord model"
        metadata = {}
        for name in required:
            assert names.count(prefix + name) == 1, f"Missing/duplicate {name}"
        for entry in names:
            if not entry.startswith(prefix) or not entry.endswith('.so'):
                continue
            name = entry.removeprefix(prefix)
            assert names.count(entry) == 1, f"Duplicate native library: {name}"
            path = Path(temporary) / name
            path.write_bytes(archive.read(prefix + name))
            metadata[name] = subprocess.check_output(["readelf", "-d", "-V", str(path)], text=True)
        sherpa = Path(temporary) / "libsherpa-onnx-jni.so"
        exports = subprocess.check_output(["readelf", "--dyn-syms", "--wide", str(sherpa)], text=True)
        expected = json.loads(Path(__file__).with_name("sherpa_required_symbols.json").read_text())
        missing = [symbol for symbol in expected if not re.search(r"\b" + re.escape(symbol) + r"\b", exports)]
        assert not missing, f"Required Sherpa JNI exports missing: {missing}"
        for retired in REMOVED_CLASSES:
            assert f"Java_com_k2fsa_sherpa_onnx_{retired}_" not in exports, f"Unused JNI wrapper retained: {retired}"
        check_dependencies(metadata)
        qnn_prefix = "assets/whisper-qnn-runtime/"
        check_qnn_asset_names(names)
        for name, (size, digest) in QNN_FILES.items():
            assert names.count(qnn_prefix + asset_name(name)) == 1, f"Missing/duplicate QNN asset {name}"
            payload = gzip.decompress(archive.read(qnn_prefix + asset_name(name)))
            assert len(payload) == size and hashlib.sha256(payload).hexdigest() == digest, f"QNN asset changed: {name}"
            check_elf(payload, 164 if name == QNN_DSP else 183)
            assert prefix + name not in names, f"Optional QNN library linked into startup: {name}"
            if name != QNN_DSP:
                path = Path(temporary) / name; path.write_bytes(payload)
                dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
                required_vendor = {'libcdsprpc.so'} if name == 'libQnnHtpV75Stub.so' else set()
                needed = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic))
                assert needed <= ANDROID_LIBRARIES | required_vendor, f"Unreviewed QNN dependency {name}: {needed}"
        for notice in ('LICENSE.pdf', 'NOTICE.txt', 'OpenAI-Whisper-MIT.txt', 'QNN-Plugin-MIT.txt', 'NOTICE.md', 'Native-NOTICE.md'):
            assert "assets/licenses/whisper-qnn/" + notice in names, f"Missing QNN license: {notice}"

        assert "Library soname: [libonnxruntime.so]" in metadata["libonnxruntime.so"]
        assert "VERS_1.27.1" in metadata["libonnxruntime.so"]
        assert "libms_ort_1232.so" not in metadata["libsherpa-onnx-jni.so"]
    print("ASR APK checks passed (shared ORT 1.27.1 retained; Moonshine SDK/runtime absent).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    check(parser.parse_args().apk)
