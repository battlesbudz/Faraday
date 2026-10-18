# Whisper QNN and combined audio candidate — 11 October 2026

## What this implements

The optional **Whisper base · Snapdragon NPU** choice targets Android arm64
Snapdragon 8 Gen 3 (SM8650, Hexagon v75). CPU Whisper remains the default and an
independently constructed fallback. The NPU export is multilingual Whisper Base
with forced English transcription; CPU retains the existing int8 `base.en`
model. They are not identical weights or precision, so compare accuracy as well
as time. In Gemma direct-audio mode, Whisper remains caption-only; these changes
do not grant ASR turn-end or intent authority.

Moonshine adapters, SDK, three private native libraries and active CI model
requirements are removed. Persisted Moonshine selections map to CPU Whisper;
Off, current choices, Gemma mode, downloaded old model files and historical
measurements remain intact. Shared ONNX Runtime 1.27.1 stays required by Sherpa,
CPU Whisper, Piper, Silero VAD and SmartTurn. Historical d2 replay tooling remains
an explicitly archival reproduction path outside the APK.

The same candidate also contains [first-clause Piper streaming](first-piper-clause-2026-10-11.md)
and [early Gemma preparation/prefill](early-gemma-session-2026-10-11.md).

## Native contract and packaging

The separately registered official QNN EP 2.6.0 uses the existing ORT C API
1.27.1. No second ORT AAR, renamed runtime, `pickFirst`, or shared-runtime
replacement is introduced. Only the five pinned runtime objects needed for
precompiled v75 contexts are packaged as deterministic gzip assets with opaque `.gz.bin` filenames
(to prevent Android asset-merge inflation); no graph
compiler or other SoC objects are shipped. Hash and byte-count checks apply to
both source AARs, each extracted ELF, generated assets, on-device extraction and
model files. AArch64 ELF load segments must support 16 KB pages; the Hexagon
skeleton is separately identified and is never loaded as an Android host ELF.

`WhisperQnnRuntime` installs immutable checked objects under app-private storage
and preloads their absolute paths before provider registration. Android declares
optional `libcdsprpc.so` access. Provider registration selects an actual NPU
device, verifies both graph contracts and disables CPU execution fallback inside
each session. Any unavailable/incompatible provider, model or runtime causes an
explicit independently labelled CPU path. Successfully creating sessions alone
is not evidence of hardware execution. Completed NPU calls record strict-provider
execution, while per-utterance work metrics identify the providers actually used,
including a failed NPU attempt followed by CPU. Requested selection is separate.

The model archive and tokenizer are on-demand only, never in Git or APK. Downloads
have pinned HTTPS URLs, exact lengths and SHA-256; extraction allows only fixed
entry names, checks expanded sizes, writes a private staging directory and verifies
all files before atomic installation. Cancellation preserves the current CPU
fallback and never deletes unrelated user downloads.

The encoder accepts FP16 `[1,80,3000]` log-Mel features: mono 16 kHz, right padding
to 30 seconds, periodic Hann 400/hop 160, reflect padding, Slaney 80 filters and
Whisper log/clipping scaling. The app retains its 25-second rolling decode window.
Six layer cross/self K/V pairs are bound by name, not ordinal. Decoder inputs
include int32 token/position, FP16 mask and cache; the graph shifts the self-cache.
The forced English/transcribe/no-timestamps prefix and 199-call context yield at
most 196 text tokens. Base64-rank tokenizer bytes are concatenated before UTF-8
decoding, preserving split multibyte characters. No fabricated punctuation or text
is introduced by this decoder.

`AsyncWhisperSession` cancels busy native work before join/release; terminal
cancellation cannot trigger fallback or publish a late final result. Native handles
are shared-owned across in-flight calls; close revokes admission, terminates ORT
runs and frees sessions only after the last call returns. A healthy idle warm lease
is retained without cancellation. Failed provider registration cleanup rolls back
the registration, including when another owner retains the shared ORT environment.

## Inputs, licensing and reproducibility

The weight-free [manifest](../../third_party/whisper-qnn/manifest.json) records
archive/runtime/tokenizer identities. The upstream export
[metadata](../../third_party/whisper-qnn/model-metadata.json) records QAIRT
2.50.0.260828221209 and exporter ORT 1.30.0. Official QNN EP Android validation
uses QAIRT 2.50.0/ORT 1.27.0, with ORT >=1.24.1 supported; our retained 1.27.1
host accepts these model envelopes until the unavailable EPContext kernel. That
is a parser compatibility check, not an Android context-load qualification.

Runtime object code is incorporated in the app under the Qualcomm AI Stack
license. Full upstream LICENSE.pdf and NOTICE.txt are copied from the verified
runtime AAR into APK assets, alongside plugin MIT, OpenAI Whisper MIT and native
frontend attribution. No standalone SDK or weights are redistributed. Relevant
sources:

- [Qualcomm Whisper Base model card](https://huggingface.co/qualcomm/Whisper-Base)
- [Pinned Qualcomm model wrapper](https://github.com/qualcomm/ai-hub-models/tree/v0.64.0/src/qai_hub_models/models/templates/hf_whisper)
- [Official QNN provider contract](https://github.com/onnxruntime/onnxruntime-qnn/blob/v2.6.0/docs/execution_providers/QNN-ExecutionProvider.md)
- [AI Stack license](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf)
- [Build preparation](../../scripts/prepare_whisper_qnn.py)

## Verification and remaining acceptance

Fresh host checks pass: 121 Python helper and 101 verification tests; architecture
and diff guards; 16 focused QNN JVM tests/API compilation; 17 retirement policy
and historical-record tests; eight independent full-tensor frontend cases;
autoregressive state/mask/FP16 checks; real ORT metadata, cancellation, registration
lifetime/rollback tests; JNI lifetime and cancellation races. Native tests also
pass ASan/UBSan (LeakSanitizer unavailable in this execution environment). Five
new Android-backed migration tests await the normal release JVM suite. Source-bound
host receipts are in [evidence](evidence/whisper-qnn-2026-10-11/).

The mandatory recorded-audio job retains unchanged CPU Whisper pins, both waveform
variants, first/last-word checks and its 3/28 WER threshold. It additionally executes
the weight-free QNN frontend/native contract tests. It does not replace acoustic
acceptance with synthetic tests. Signed build, APK packaging, Android lifecycle
and physical Fold6 execution must still pass; no NPU acoustic accuracy, sustained
thermal behavior or device latency is established by these host tests.

For physical qualification, test fresh install/upgrade, offline verified cache,
corrupt/missing assets, unavailable HTP, cancel/barge-in during encoder and token
runs, repeated calls, caption-only Gemma routing, and CPU fallback diagnostics.
Capture strict NPU provider completion, decode time, end-of-speech to first model
callback, guard release, first Piper PCM and actual playback. Compare identical
speech/tasks against CPU-ASR/text and direct Gemma audio; component benchmarks
cannot establish the fastest complete route.

## Size accounting

Build1265 normal APK was 146,534,679 bytes and compact 66,186,091 bytes. Removing
Moonshine's three objects removes 17,508,904 / 8,204,559 ZIP payload bytes
respectively. The five new QNN gzip payloads total 9,306,959 bytes before notices,
manifest, native glue and DEX. These are gross component figures, not final net
APK savings. New signed APK size reports are required to calculate the actual
normal/compact delta. Old downloaded Moonshine weights (142,300,974 bytes) remain
in app-private storage and were never an APK payload.
