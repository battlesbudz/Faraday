"""Compare unprimed/primed streaming ASR on a WAV using the shipped 20M model.

Requires numpy and sherpa-onnx==1.13.7. Supply the extracted model directory and
a PCM16 mono 16 kHz WAV. Optional --expect-prefix verifies the primed result.
Example: python scripts/check_asr_onset.py MODEL_DIR MODEL_DIR/0.wav
  --expect-prefix 'AFTER EARLY NIGHTFALL'
This is a host regression check, not an Android latency or dictation benchmark.
"""
import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("wav", type=Path)
    parser.add_argument("--expect-prefix")
    args = parser.parse_args()
    with wave.open(str(args.wav)) as recording:
        assert (recording.getnchannels(), recording.getsampwidth(), recording.getframerate()) == (1, 2, 16000)
        pcm = np.frombuffer(recording.readframes(recording.getnframes()), dtype="<i2").astype(np.float32) / 32768
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(args.model_dir / "tokens.txt"),
        encoder=str(args.model_dir / "encoder-epoch-99-avg-1.int8.onnx"),
        decoder=str(args.model_dir / "decoder-epoch-99-avg-1.int8.onnx"),
        joiner=str(args.model_dir / "joiner-epoch-99-avg-1.int8.onnx"),
        num_threads=2, decoding_method="greedy_search",
    )
    for priming_samples in (0, 12800):
        stream = recognizer.create_stream()
        started = time.monotonic()
        if priming_samples:
            stream.accept_waveform(16000, np.zeros(priming_samples, dtype=np.float32))
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
        for offset in range(0, len(pcm), 1600):
            stream.accept_waveform(16000, pcm[offset:offset + 1600])
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
        stream.accept_waveform(16000, np.zeros(12800, dtype=np.float32))
        stream.input_finished()
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        text = recognizer.get_result(stream)
        print(json.dumps(dict(priming_samples=priming_samples, text=text,
                              decode_ms=round((time.monotonic() - started) * 1000))))
        if priming_samples and args.expect_prefix:
            assert text.upper().startswith(args.expect_prefix.upper()), text


if __name__ == "__main__":
    main()
