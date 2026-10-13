# Whisper QNN CPU frontend verification

Run from the repository root:

```sh
python3 app/src/main/cpp/whisperqnn/tests/verify_frontend.py \
  --receipt /tmp/whisper-qnn-frontend-receipt.json
```

Requires Python 3.10+, NumPy, and a C++17 compiler. `CXX` selects a compiler and
`CXXFLAGS` adds host test flags. The script compiles only `whisper_features.cc`
and the standalone probe in a temporary directory. It does not fetch anything,
read private audio, use model weights, or require Android/QNN. Failures are fatal;
no reference regeneration, downloads, or changed tolerances occur at runtime.
The receipt records source hashes, the NumPy version, and all numerical errors.

## Contract

`jarvis::whisperqnn::WhisperFeatures::Compute(const float*, size_t,
const std::atomic<bool>* = nullptr)` returns `std::vector<float>`, with
`kSamples == 480000` and `kFeatures == 240000` in the same namespace.

- Input: finite mono 16 kHz FP32 samples in the inclusive range `[-1, 1]`;
  reject null, zero-length, and more than 480000 samples.
- Right-pad with zeros to 480000 samples. Do not normalize mean/variance,
  resample, or truncate input.
- Centered reflect padding of 200 samples on both sides, excluding the boundary
  sample; periodic Hann window of 400, hop 160, FFT size 400, power spectrum.
- 80 triangular Slaney filters spanning 0–8000 Hz, with Slaney area normalization.
- Apply `log10(max(energy, 1e-10))`. Drop the final STFT column to retain 3000
  frames; clamp against the single global maximum minus 8 and apply `(x+4)/4`.
- Return contiguous mel-major FP32 `[80, 3000]`; index `mel * 3000 + frame`.
- Reject invalid inputs with `std::invalid_argument`. Cancellation throws
  `std::runtime_error("cancelled")` before work, during PCM validation, before
  each STFT frame, and during/following final scaling. No partial result escapes.
- One serialized caller per instance. FFT scratch is instance-owned, and the
  instance remains reusable after failure or cancellation.

The FFT and filters use double precision internally. To match the Hugging Face
NumPy path, FFT real/imaginary components round to FP32 before the double-precision
power calculation, and log-mel values round to FP32 before clipping/scaling.
“FP32 frontend” describes the PCM and model-facing tensor, not an all-FP32 FFT.

## Independent numeric checks

`verify_frontend.py` implements a separate vectorized NumPy STFT, including all
3001 initial columns and removal of the last column. Its `np.fft.rfft` does not
call or reproduce the production C++ mixed-radix FFT. It follows these upstream
Hugging Face Transformers v4.55.4 sources with `do_normalize=False`,
`dither=0.0`, and the default Whisper feature-extractor parameters:

- [WhisperFeatureExtractor](https://github.com/huggingface/transformers/blob/v4.55.4/src/transformers/models/whisper/feature_extraction_whisper.py)
- [audio_utils: spectrogram, mel_filter_bank, and window_function](https://github.com/huggingface/transformers/blob/v4.55.4/src/transformers/audio_utils.py)

Every feature cell is compared with absolute tolerance `2e-6` and zero relative
tolerance for synthetic full-length silence, one-sample impulse, reflected/start/
end boundary impulses, a short 440 Hz sine, full-length mixed/chirped tones with
quiet/silent sections, short DC, sub-floor audio, and inclusive range endpoints.
Silence and sub-floor audio also have an exact analytic `-1.5` assertion.
Explicitly right-padding a short input must produce a bitwise identical output.
The C++ self-test separately covers null/empty/oversize input, NaN at the first
and final samples, both infinities, just-out-of-range values, pre-cancellation,
asynchronous in-flight cancellation, and reuse after failures and cancellation.

These host checks establish frontend shape, numerical agreement, validation and
cancellation behavior. They do not establish model accuracy, QNN inference,
physical-device performance, or release APK verification. The existing Smart
Turn frontend remains separate and unchanged.

## Attribution

The production frontend adapts Jarvis's existing
`app/src/main/cpp/smartturn/whisper_features.{h,cc}` mixed-radix implementation,
which ports Pipecat's Whisper feature math. The new owner changes the namespace,
input length, padding/normalization policy, explicit HF rounding boundaries, and
cancellation coverage. It does not share Smart Turn state or change Smart Turn.

Copyright (c) 2024–2026, Daily. Portions derived from Hugging Face Transformers:
Copyright 2022 The HuggingFace Inc. team; Copyright 2023 The HuggingFace Inc. team
and the librosa & torchaudio authors. The reference equations and rounding
contract also inform the new independent NumPy test. All fixtures are synthetic.

Retained upstream notices and complete license texts are in:

- [Smart Turn/Pipecat provenance](../../../../../../third_party/smart-turn/NOTICE.md)
- [Pipecat BSD 2-Clause license](../../../../../../third_party/smart-turn/pipecat-BSD-2-Clause.txt)
- [Apache License 2.0](../../../../../../third_party/smart-turn/Apache-2.0.txt)
