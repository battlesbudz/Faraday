# APK size audit and distribution options

Measured 16 September 2026 from the signed PR #6 build 705 APK (commit `51b2455`).
All MB below are decimal, 1,000,000 bytes. The machine-readable baseline is
[build-705-apk-size.json](measurements/build-705-apk-size.json).

## What occupies the APK

Total: **73,236,319 bytes (73.24 MB)**. Native libraries occupy **68.31 MB (93.3%)**;
DEX occupies **3.94 MB**. Packaged assets occupy only **0.64 MB** compressed.

| Native library | MB | Why it is present |
| --- | ---: | --- |
| Sherpa ONNX Runtime | 21.68 | Whisper, Piper, Silero and speaker embedding |
| LiteRT-LM JNI | 14.67 | Gemma E2B/E4B inference |
| Moonshine engine | 11.11 | Streaming ASR; upstream ships additional functionality |
| Moonshine ONNX Runtime | 6.29 | Its matching, separately namespaced runtime |
| LiteRT | 5.05 | Gemma runtime dependency |
| Sherpa JNI | 4.53 | Speech wrappers plus reachable native engine code |
| LiteRT GPU accelerator | 2.76 | Current GPU inference path |
| C++ shared runtime | 1.29 | Native dependency required by the packaged libraries |
| microWakeWord | 0.80 | Wake and stop-keyword detection |

Model weights downloaded during setup are not inside the APK. Deleting Kotlin
benchmark code cannot produce a comparable reduction in these native payloads.
ARM64-only packaging, R8 and resource shrinking were already enabled.

## Implemented reductions

1. The pinned Sherpa build now exposes only the JNI interfaces used by the app.
   Removed wrappers include audio tagging, punctuation, denoising, language ID,
   keyword spotting and online ASR. Keep `OnlineStream`: speaker embedding uses
   it despite Jarvis not using Sherpa online ASR. Section-level compilation and
   linker garbage collection let unused native code become unreachable.
   This changes build reachability, not model implementations or inference policy.
2. CI checks all **48** required native exports captured from build 705, checks
   removed interface classes are absent, and retains runtime dependency/version,
   Piper callback and model-asset validation. JNI configuration DTO keep rules
   remain broad because retained native constructors inspect their fields.
3. Every release produces size reports against build 705, including per-library
   deltas. Measure the actual signed result; no savings from native pruning are
   claimed until a successful build is inspected.

## Normal and compact downloads

- `app-release.apk`: default, uncompressed/page-aligned native libraries. Android
  can load libraries from the APK without an extracted second copy.
- `app-compact.apk`: same application ID, version, signing key, DEX, assets and
  native payload bytes, built with `-PcompactApk=true`. Native ZIP entries are
  compressed and Android extracts them during installation. Install one APK,
  not both. This is an alternative download, not a second app or model stack.

Compressing build 705's native bytes with DEFLATE level 6 yields an estimated
28.24 MB rather than 68.31 MB: approximately **40.06 MB less native download**.
That is an estimate, not a measured new APK size. Installed native code and RAM
requirements do not shrink, and installed disk use can grow because the compressed
APK and extracted libraries both remain. CI signs and verifies both variants,
compares their runtime bytes, and publishes their actual size reports.

The build uses Android's supported
[`useLegacyPackaging` option](https://developer.android.com/reference/tools/gradle-api/8.7/com/android/build/api/dsl/JniLibsPackaging#useLegacyPackaging()).
No post-signing ZIP edits are performed.

## Larger reductions requiring separate work

| Candidate | Evidence / boundary before changing it |
| --- | --- |
| Reduced-operator Sherpa ONNX Runtime | Largest library; inventory operators/types for Piper, Whisper, Silero and speaker models, then build the same ORT version and validate each real model. Do not strip guessed operators. |
| ASR-only Moonshine build | Inspect its pinned native source and exported ABI. Removing Kotlin TTS classes does not remove native implementations. Requires new upstream source pins and ASR/model tests. |
| Narrow Sherpa model factories to Whisper and VITS | This pass removes unused JNI interfaces; retained factories still know other model families, including retired TTS families. A deeper source profile needs explicit model-construction tests. |
| Narrow SDK R8 keep rules | Small DEX opportunity; first enumerate native-created DTOs and reflective fields. Blind keep-rule deletion risks release-only crashes. |
| Optional engine delivery | Could remove whole runtimes from the base APK, but changes offline setup, installation and both-engine availability. Not part of this cleanup. |

The two ONNX runtimes are not interchangeable: the packaged libraries require
`VERS_1.27.1` and `VERS_1.23.2` respectively. Never use `pickFirst` to suppress that
collision. The GPU accelerator and shared C++ runtime are also retained dependencies.

## Reproduce

```sh
python3 scripts/apk_size_report.py app-release.apk --output /tmp/apk-size --baseline docs/measurements/build-705-apk-size.json
python3 scripts/check_asr_apk.py app-release.apk
python3 scripts/check_compact_apk.py app-release.apk app-compact.apk
```

Phone acceptance remains required for both ASR engines, Piper, wake/stop, speaker
checks, E2B/E4B and installing the compact variant on the Fold 6.
