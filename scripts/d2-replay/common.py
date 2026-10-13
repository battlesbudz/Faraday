"""Bounded offline host comparison. Never a live barge-in decision or phone benchmark."""
import argparse, io, json, pathlib, sys, time, wave, zipfile
from importlib.metadata import version
import numpy as np
from moonshine_voice import Transcriber, ModelArch

parser = argparse.ArgumentParser()
parser.add_argument('--input', type=pathlib.Path, required=True, help='Directory with D2 communication_speaker ZIPs')
parser.add_argument('--moonshine', type=pathlib.Path, required=True, help='Pinned SMALL_STREAMING model directory')
parser.add_argument('--whisper', type=pathlib.Path, required=True, help='Pinned Whisper base.en int8 directory')
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
root = args.input
args.output.mkdir(parents=True, exist_ok=True)
for package, expected in [('moonshine-voice', '0.1.5'), ('sherpa-onnx', '1.13.7'), ('onnxruntime', '1.23.2')]:
    if version(package) != expected:
        raise RuntimeError(f'{package} must be {expected}')
