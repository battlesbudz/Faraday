# Smart Turn recovery and fresh admission — 9 October 2026

This candidate starts at published f783849de9cb6ebb2932eba52b7ebdc596aa02fa.
It preserves the test45 Copy-part2 descendant selector. It does not include the
abandoned encrypted diagnostic policy, credentials or model weights.

## Source provenance

The CPU frontend/session/JNI, Kotlin backend/snapshot/worker, settings/model store,
call owner, capture observer, telemetry, runtime hooks and phone settings were
recovered from retained source and surviving uploaded Git blobs. Their content
identities were checked before local assembly. No pre-final worker/settings
variant was promoted. The model remains exactly smart-turn-v3.2-cpu.onnx at
HF f766f81d3cfdf7737ac64aad813d91bbfd56bf93, SHA-256
2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f.

Missing build-input/cache/R8/symbol support, lifecycle tests, this documentation,
license packaging, manifest and frontend verifier are **newly reconstructed**.
They receive fresh tests and review, not the old admission. The17 worker tests
are meaningful new tests; they are not the lost 16-test original suite. Existing
recovered observer/store/late-telemetry tests and capture-hook tests remain intact.

The retained original generator and independent fixture manifest were restored.
Every decoded PCM and feature file matches the original frozen SHA-256 pins.
The manifest is byte-identical to its retained original. Chirp's compressed gzip
also matches its surviving uploaded blob. Other gzip containers were regenerated;
their decoded content identity is proven, but an unavailable compressed original
is not claimed recovered. Normal verification never regenerates expectations.

## Preserved build and ownership contracts

- ONNX Runtime 1.27.1 remains the existing pinned Sherpa dependency. The bridge
  compiles into libsherpa-onnx-jni.so, with no new AAR or duplicate runtime.
- The upstream export script retains local:* and precisely five new JNI exports:
  create, prepare, infer, cancel, close. R8 retains only SmartTurnNative's bridge;
  existing VoiceInputSettingsKt retention covers journey81's test-DEX entry.
- Native cache identity hashes source names/bytes and the profile; Gradle inputs
  and hosted cache keys include the native source directory.
- Existing 16 KB ELF flags, C++ runtime packaging, native pins and APK audits remain.
  Pinned Sherpa CMake defaults to C++17. Actual NDK/minified linkage still needs CI.
- Default off. Setup uses the existing explicit settings/download consent path,
  pinned size/hash and atomic install. No capture/answer-time download or hash.
- One retained call worker, no inference backlog, fixed eight-second PCM ring, at most
  three candidate attempts per turn separated by 500 ms. No endpoint authority.
- Cancellation/revocation does not join the answer lane or replace undrained
  native ownership. Unknown phase costs remain null. Late completion is retained
  as bounded metadata against the original turn.
- Post-priority worker wall time is not CPU utilization or proof of concurrent
  Gemma execution. Physical device contention and cutoff calibration are unknown.

## Fresh verification and remaining gates

Current local recovery checks pass 37 Smart Turn JVM tests (20 recovered and
17 new worker tests), five actual capture-hook JVM tests, and all eight original
frozen frontend cases. Worst observed absolute frontend error is 2.3842e-7,
within the unchanged 2e-6 tolerance. The compiled source hashes and fresh logs
are bound in the new recovery receipt; historical receipts are not substituted.


Run `python3 scripts/dev.py check`; it includes the new source-fingerprint,
export allowlist, model pin and actual C++ frontend checks. Run
`python3 scripts/smart-turn/verify_frontend.py --receipt <path>` for an independent
weight-free receipt. All eight original cases must retain their 2e-6 tolerance, with
nonfinite input rejection. No model inference occurs in these commands.

The release Gradle JVM suite discovers all worker/observer/store/telemetry tests.
Local compiler/API receipts describe their exact dependency and fixture scope;
they do not substitute for a complete Android build. Recovered Compose tests and
journey81 still require real release execution. The 900-second main suite, all
assertions, and all five Android profiles are unchanged. Journey81's added device
runtime remains unknown; an earlier Fold margin of 7.53 seconds is a risk to measure,
not grounds to raise deadlines or remove tests.

No model download or execution is part of this recovery phase. Actual model
predictions, native phase costs, thermal/resource contention, endpoint quality,
NDK build, APK 16 KB audit, signed/minified instrumentation linkage and phone
performance remain separately required. No default or authoritative endpoint
activation follows from source/helper success.
