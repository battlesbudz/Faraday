#!/usr/bin/env python3
"""Fail-closed, real-weight Linux speech regression using Jarvis's pinned models.

The Kotlin release tests independently exercise shipping capture/phrase gates
and the real LiteRT Content DTO. This host check proves recorded recognition,
not Android scheduling, microphone/AEC or Gemma audio understanding. Native
results, exact model hashes and source identity are retained even on failure.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import io
import json
import math
from pathlib import Path
import re
import sys
import tarfile
import time
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "scripts/verification/acoustic_scenarios.json"
FIXTURES = ROOT / "app/src/test/resources/recorded-audio"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def contract() -> dict:
    return json.loads(CONTRACT.read_text())


def model_specs() -> dict[str, list[dict]]:
    """Read authoritative production hashes; never maintain a second weight pin."""
    voice = ROOT / "app/src/main/java/com/battlesbudz/jarvis/v2/voice"
    whisper = (voice / "RecognitionModelStore.kt").read_text()
    result = {
        "whisper": [{"name": name, "bytes": int(size), "sha256": digest}
                    for name, size, digest in re.findall(r'Spec\("([^"]+)", (\d+), "([0-9a-f]{64})"\)', whisper)],
    }
    if len(result["whisper"]) != 3:
        raise ValueError("Production ASR weight contract changed; review the host adapter.")
    return result


def fixture() -> tuple[dict, bytes]:
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    if manifest.get("schema_version") != 1 or len(manifest.get("samples", [])) != 1:
        raise ValueError("Unexpected recorded fixture manifest")
    sample = manifest["samples"][0]
    reference = (FIXTURES / "reference.txt").read_bytes()
    if sha256(reference) != sample["reference_line_sha256"]:
        raise ValueError("Official corpus reference line checksum mismatch")
    identity, text = reference.decode().strip().split(" ", 1)
    if sample["id"] != "librispeech-" + identity or sample["text"] != text:
        raise ValueError("Recorded fixture identity/transcript differs from the official reference line")
    data = (FIXTURES / sample["file"]).read_bytes()
    if sha256(data) != sample["sha256"]:
        raise ValueError("Human recording WAV checksum mismatch")
    with wave.open(io.BytesIO(data)) as recording:
        if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate(), recording.getnframes()) != (1, 2, 16000, sample["frames"]):
            raise ValueError("Human recording is not the pinned PCM16 mono 16 kHz fixture")
        pcm = recording.readframes(recording.getnframes())
    if sha256(pcm) != sample["pcm_sha256"]:
        raise ValueError("Human recording PCM checksum mismatch")
    return sample, pcm


def variant_pcm(pcm: bytes, variant: str) -> bytes:
    if variant == "original":
        return pcm
    if variant == "trailing_silence":
        return pcm + bytes(16000 * 2)
    raise ValueError(f"Unrecognized recording variant: {variant}")


def words(text: str) -> list[str]:
    # Abbreviation expansion affects only orthography, never missing words.
    return ["mister" if word == "mr" else word for word in re.findall(r"[a-z]+(?:'[a-z]+)?", text.lower())]


def score(reference: str, hypothesis: str) -> dict:
    expected, actual = words(reference), words(hypothesis)
    if not expected:
        raise ValueError("Speech reference must contain words")
    previous = list(range(len(actual) + 1))
    for i, token in enumerate(expected, 1):
        current = [i]
        for j, other in enumerate(actual, 1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (token != other)))
        previous = current
    return {"word_error_rate": previous[-1] / len(expected),
            "first_word": actual[0] if actual else "", "last_word": actual[-1] if actual else "",
            "first_word_correct": bool(actual) and actual[0] == expected[0],
            "last_word_correct": bool(actual) and actual[-1] == expected[-1]}


def validate_report(report: dict, source_commit: str) -> None:
    """Receipt validator: recompute outcomes and require every check and weight pin."""
    required = contract()
    sample, pcm = fixture()
    models = model_specs()
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit or "") or report.get("source_commit") != source_commit:
        raise ValueError("Recorded audio evidence source commit mismatch")
    if report.get("schema_version") != 1 or report.get("status") != "passed":
        raise ValueError("Recorded audio gate did not pass")
    if report.get("runtime_versions") != required["runtime_versions"]:
        raise ValueError("Recorded audio runtime version mismatch")
    checks = report.get("checks", [])
    if len(checks) != len(required["checks"]):
        raise ValueError("Missing or duplicated recorded audio check")
    by_name = {item.get("name"): item for item in checks}
    if set(by_name) != {item["name"] for item in required["checks"]}:
        raise ValueError("Recorded audio required check names mismatch")
    for spec in required["checks"]:
        item = by_name[spec["name"]]
        if item.get("status") != "passed" or item.get("backend") != spec["backend"] or item.get("variant") != spec["variant"]:
            raise ValueError(f"Recorded audio check failed or substituted: {spec['name']}")
        if item.get("sample_id") != sample["id"] or item.get("recording_sha256") != sample["sha256"] or item.get("input_sha256") != sha256(variant_pcm(pcm, spec["variant"])):
            raise ValueError(f"Recorded waveform provenance mismatch: {spec['name']}")
        if item.get("model_files") != models[spec["backend"]]:
            raise ValueError(f"Recorded audio weight pins mismatch: {spec['name']}")
        actual_score = score(sample["text"], item.get("text", ""))
        if any(item.get(key) != value for key, value in actual_score.items()):
            raise ValueError(f"Recorded audio score does not match native text: {spec['name']}")
        if not actual_score["first_word_correct"] or not actual_score["last_word_correct"] or actual_score["word_error_rate"] > spec["max_word_error_rate"]:
            raise ValueError(f"Recorded first/last word or WER regression: {spec['name']}")
        if not isinstance(item.get("raw_result"), str) or not item["raw_result"]:
            raise ValueError(f"Native recorded-audio result missing: {spec['name']}")
        if not isinstance(item.get("elapsed_ms"), (int, float)) or not math.isfinite(item["elapsed_ms"]) or item["elapsed_ms"] <= 0:
            raise ValueError(f"Recorded inference timing missing: {spec['name']}")


def valid_model(path: Path, spec: dict) -> bool:
    return path.is_file() and path.stat().st_size == spec["bytes"] and file_sha256(path) == spec["sha256"]


def download(url: str, path: Path, expected_bytes: int, expected_sha256: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        # Identify this public release verification client explicitly.
        request = urllib.request.Request(url, headers={"User-Agent": "Jarvis-OS-V2/recorded-audio-verification"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            size = 0
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > expected_bytes:
                    raise ValueError(f"Model download exceeds pinned size: {path.name}")
                output.write(block)
        if temporary.stat().st_size != expected_bytes or file_sha256(temporary) != expected_sha256:
            raise ValueError(f"Model download checksum mismatch: {path.name}")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_models(directory: Path, allow_download: bool) -> dict[str, list[dict]]:
    specs = model_specs()
    voice = ROOT / "app/src/main/java/com/battlesbudz/jarvis/v2/voice"
    if allow_download:
        if not all(valid_model(directory / "whisper" / spec["name"], spec) for spec in specs["whisper"]):
            whisper_source = (voice / "RecognitionModelStore.kt").read_text()
            match = re.search(r'download\("([^"]+)", part,\s*(\d+), "([0-9a-f]{64})"', whisper_source)
            if not match:
                raise ValueError("Production Whisper archive download contract changed")
            archive = directory / "whisper.tar.bz2"
            download(match.group(1), archive, int(match.group(2)), match.group(3))
            try:
                # Extract only expected model bytes, never paths or links from an archive.
                with tarfile.open(archive, "r:bz2") as tar:
                    for spec in specs["whisper"]:
                        member = tar.getmember("sherpa-onnx-whisper-base.en/" + spec["name"])
                        if not member.isfile() or member.size != spec["bytes"]:
                            raise ValueError("Unexpected Whisper archive model entry")
                        target = directory / "whisper" / spec["name"]
                        target.parent.mkdir(parents=True, exist_ok=True)
                        stream = tar.extractfile(member)
                        if stream is None:
                            raise ValueError("Missing Whisper archive model data")
                        with stream, target.open("wb") as output:
                            while block := stream.read(1024 * 1024):
                                output.write(block)
            finally:
                archive.unlink(missing_ok=True)
    for backend, files in specs.items():
        for spec in files:
            if not valid_model(directory / backend / spec["name"], spec):
                raise ValueError(f"Pinned {backend} model missing/corrupt: {spec['name']}")
    return specs


def whisper_recognizer(directory: Path):
    import sherpa_onnx
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(directory / "base.en-encoder.int8.onnx"),
        decoder=str(directory / "base.en-decoder.int8.onnx"),
        tokens=str(directory / "base.en-tokens.txt"), num_threads=2)


def recognize_whisper(recognizer, pcm: bytes) -> tuple[str, str]:
    import numpy as np
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768)
    recognizer.decode_stream(stream)
    return stream.result.text, str(stream.result)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--download-models", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    report = {"schema_version": 1, "source_commit": args.source_commit, "status": "failed", "checks": [],
              "coverage": "Real pinned ASR weights on Linux CPU; shipping capture/Content delivery are separate release JVM checks.",
              "not_covered": ["Gemma audio understanding", "Android native runtime execution", "microphone/speaker/AEC/Bluetooth", "population accuracy", "device latency/thermal"]}
    try:
        required = contract()
        report["runtime_versions"] = {name: importlib.metadata.version(name) for name in required["runtime_versions"]}
        if report["runtime_versions"] != required["runtime_versions"]:
            raise ValueError("Install the pinned recorded-audio runtime versions")
        models = prepare_models(args.models, args.download_models)
        sample, pcm = fixture()
        whisper = whisper_recognizer(args.models / "whisper")
        for spec in required["checks"]:
            data = variant_pcm(pcm, spec["variant"])
            start = time.monotonic()
            if spec["backend"] != "whisper":
                raise ValueError("Unsupported recorded-audio backend")
            text, raw = recognize_whisper(whisper, data)
            result = score(sample["text"], text)
            passed = result["word_error_rate"] <= spec["max_word_error_rate"] and result["first_word_correct"] and result["last_word_correct"]
            item = {"name": spec["name"], "backend": spec["backend"], "variant": spec["variant"], "sample_id": sample["id"],
                    "status": "passed" if passed else "failed", "recording_sha256": sample["sha256"], "input_sha256": sha256(data),
                    "model_files": models[spec["backend"]], "text": text, "raw_result": raw,
                    "elapsed_ms": round((time.monotonic() - start) * 1000, 3), **result}
            report["checks"].append(item)
            print(json.dumps(item), flush=True)
        report["status"] = "passed" if all(item["status"] == "passed" for item in report["checks"]) else "failed"
        validate_report(report, args.source_commit)
    except Exception as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        print(report["error"], file=sys.stderr, flush=True)
    finally:
        (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
