#!/usr/bin/env python3
"""Build the isolated frontend and compare every cell with an independent NumPy STFT.

Requires Python 3.10+, NumPy, and a C++17 compiler. No network, model weights,
Android SDK, QNN SDK, or private audio. See README.md for attribution and scope.
The reference uses NumPy's FFT, not the production mixed-radix implementation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

import numpy as np

NATIVE = Path(__file__).resolve().parents[1]
SAMPLES = 480000
SHAPE = (80, 3000)
ABSOLUTE_TOLERANCE = 2e-6


def reference(samples: np.ndarray) -> np.ndarray:
    """HF Whisper v4.55.4 NumPy math with do_normalize=False and right padding.

    This separately vectorized reference intentionally computes all 3001 STFT
    columns, then drops the last column before global clipping. FFT output is
    rounded to complex64 as in HF audio_utils.spectrogram, followed by float64
    power/filter arithmetic and float32 log-mel output.
    """
    waveform = np.pad(samples.astype(np.float32), (0, SAMPLES - samples.size))
    centered = np.pad(waveform, (200, 200), mode="reflect").astype(np.float64)
    frames = np.lib.stride_tricks.sliding_window_view(centered, 400)[::160]
    periodic_hann = np.hanning(401)[:-1]
    spectrum = np.fft.rfft(frames * periodic_hann, axis=-1).astype(np.complex64)
    power = np.abs(spectrum, dtype=np.float64) ** 2

    # Slaney mel endpoints and Hz-space area-normalized triangular filters.
    top_mel = 15.0 + np.log(8000.0 / 1000.0) * (27.0 / np.log(6.4))
    mel_edges = np.linspace(0.0, top_mel, 82)
    hz_edges = mel_edges * (200.0 / 3.0)
    upper = mel_edges >= 15.0
    hz_edges[upper] = 1000.0 * np.exp((mel_edges[upper] - 15.0) * np.log(6.4) / 27.0)
    frequencies = np.linspace(0.0, 8000.0, 201)[:, None]
    distances = hz_edges[None, :] - frequencies
    intervals = np.diff(hz_edges)
    filters = np.maximum(0.0, np.minimum(-distances[:, :-2] / intervals[:-1],
                                        distances[:, 2:] / intervals[1:]))
    filters *= (2.0 / (hz_edges[2:] - hz_edges[:-2]))[None, :]
    log_mel = np.log10(np.maximum(1e-10, filters.T @ power.T)).astype(np.float32)
    log_mel = log_mel[:, :-1]
    clipped = np.maximum(log_mel, np.max(log_mel) - np.float32(8.0))
    return (clipped + np.float32(4.0)) / np.float32(4.0)


def fixtures() -> dict[str, np.ndarray]:
    t_short = np.arange(16003, dtype=np.float64) / 16000.0
    t_full = np.arange(SAMPLES, dtype=np.float64) / 16000.0
    mixed = (0.24 * np.sin(2 * np.pi * 123.45 * t_full)
             + 0.13 * np.sin(2 * np.pi * 3371.1 * t_full)
             + 0.09 * np.sin(2 * np.pi * (83 * t_full + 17 * t_full ** 2)))
    mixed[90123:250777] *= 0.001
    mixed[270123:317239] = 0.0
    boundary = np.zeros(SAMPLES, dtype=np.float32)
    boundary[[0, 1, 17, 159, 160, 199, 200, 479800, 479998, 479999]] = [
        1.0, -0.5, 0.7, -0.1, 0.2, -0.8, 0.6, 0.3, -1.0, 0.9]
    return {
        "silence_30s": np.zeros(SAMPLES, dtype=np.float32),
        "impulse_one_sample": np.array([1.0], dtype=np.float32),
        "impulse_boundary_reflections": boundary,
        "sine_440hz_short": (0.4 * np.sin(2 * np.pi * 440 * t_short)).astype(np.float32),
        "mixed_30s": mixed.astype(np.float32),
        "dc_short_no_normalization": np.full(159, 0.2, dtype=np.float32),
        "quiet_log_floor": np.full(319, 1e-9, dtype=np.float32),
        "range_endpoints": np.array([-1.0, 1.0], dtype=np.float32),
    }


def verify() -> dict:
    records = []
    sources = [NATIVE / "whisper_features.h", NATIVE / "whisper_features.cc",
               NATIVE / "tests/frontend_probe.cc", Path(__file__).resolve()]
    with tempfile.TemporaryDirectory(prefix="whisper-qnn-frontend-") as directory:
        work = Path(directory)
        probe = work / "frontend_probe"
        compiler = shlex.split(os.environ.get("CXX", "c++"))
        flags = shlex.split(os.environ.get("CXXFLAGS", ""))
        command = [*compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                   "-pedantic", "-pthread", *flags, "-I", str(NATIVE),
                   str(NATIVE / "tests/frontend_probe.cc"),
                   str(NATIVE / "whisper_features.cc"), "-o", str(probe)]
        subprocess.run(command, check=True, timeout=90)
        subprocess.run([str(probe), "--self-test"], check=True, timeout=30)
        cases = fixtures()
        short_output = None
        for name, samples in cases.items():
            source, result = work / "input.f32", work / "output.f32"
            samples.tofile(source)  # Probe and Python run on the same native-endian host.
            subprocess.run([str(probe), str(source), str(result)], check=True, timeout=30)
            actual = np.fromfile(result, dtype=np.float32)
            if actual.size != np.prod(SHAPE):
                raise AssertionError(f"{name}: wrong output size {actual.size}")
            actual = actual.reshape(SHAPE)
            expected = reference(samples)
            if not np.all(np.isfinite(actual)):
                raise AssertionError(f"{name}: nonfinite feature")
            error = float(np.max(np.abs(actual - expected)))
            np.testing.assert_allclose(actual, expected, atol=ABSOLUTE_TOLERANCE,
                                       rtol=0, err_msg=name)
            if name in ("silence_30s", "quiet_log_floor"):
                np.testing.assert_array_equal(actual, np.full(SHAPE, -1.5, dtype=np.float32))
            if name == "sine_440hz_short":
                short_output = actual.copy()
            records.append({"id": name, "samples": int(samples.size),
                            "max_abs_error": error, "passed": True})
            print(f"PASS {name}: max_abs_error={error:.9g}")

        # Explicit right-padding equivalence guards against accidental Smart Turn
        # left-padding or normalization behavior leaking into this separate owner.
        padded = np.pad(cases["sine_440hz_short"], (0, SAMPLES - cases["sine_440hz_short"].size))
        padded.tofile(work / "input.f32")
        subprocess.run([str(probe), str(work / "input.f32"), str(work / "output.f32")],
                       check=True, timeout=30)
        np.testing.assert_array_equal(short_output,
                                      np.fromfile(work / "output.f32", dtype=np.float32).reshape(SHAPE))
        print("PASS right-padding equivalence")

    return {
        "scope": "Standalone host frontend only; no QNN/model/Android/device verification",
        "reference": "Hugging Face Transformers v4.55.4 NumPy Whisper math, independent NumPy FFT",
        "numpy_version": np.__version__, "shape": list(SHAPE),
        "compiler": compiler, "extra_cxxflags": flags,
        "sanitizer_options": {key: os.environ[key] for key in ("ASAN_OPTIONS", "UBSAN_OPTIONS")
                              if key in os.environ},
        "absolute_tolerance": ABSOLUTE_TOLERANCE,
        "source_sha256": {str(path.relative_to(NATIVE)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in sources},
        "cases": records, "invalid_input_rejected": True,
        "pre_cancelled_and_in_flight_cancelled": True,
        "instance_reuse_checked": True, "right_padding_equivalence": True,
        "weights_used": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    result = verify()
    output = json.dumps(result, indent=2) + "\n"
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(output)
    print(output, end="")
