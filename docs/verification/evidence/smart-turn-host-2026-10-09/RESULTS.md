# Smart Turn production-session host evidence — 9 October 2026

One bounded run of the unchanged production C++ Smart Turn Session succeeded on
all eight existing synthetic/public fixtures. Every returned probability was
float32 bit-identical to the independent direct-ORT result using the pinned
official NumPy frontend. The same-feature Session I/O comparisons also matched
exactly. No probability tolerance was introduced.

## Exact identity and scope

- Source commit: `165ffbb46af313d51e3947a2e70cab3eaef3a543`
- Source tree: `bd90286517b045508d2b2b778eb89322fac5920d`; clean before/after.
- Model: official `pipecat-ai/smart-turn-v3`, revision
  `f766f81d3cfdf7737ac64aad813d91bbfd56bf93`, `smart-turn-v3.2-cpu.onnx`.
  Exactly 8,679,182 bytes, SHA-256
  `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f`.
- Host runtime: Microsoft official CPU ORT 1.27.1, Linux x86-64 archive SHA-256
  `25b1ef1fea1acd210d63f8f24dc870ad6e077795ce1f54876252c6d3803c15af`.
- Reference frontend: unchanged Pipecat revision
  `7597e0c2f84fc05a31dd636daa9d91bb8405e9ff`, NumPy 2.3.5.
- No private audio or new dataset was used. The existing public speech fixture
  remains LibriSpeech 1089-134686-0000, CC-BY-4.0, with repository attribution.

The native and reference processes each retained one session and ran sequentially.
The admitted run used one CPU affinity, one intra/inter-op thread, CPU provider,
2 GiB address-space caps, 600 seconds overall and 180 seconds per child. It made
nine successful production and sixteen reference inferences, without retries.
All five child phases exited zero, were reaped and had their owned groups
observed absent before proceeding. No runtime/source pin changed.

## Eight-fixture comparison

| Fixture | Production / reference probability | Maximum frontend absolute error |
| --- | ---: | ---: |
| silence_8s | 0.9870367050170898 | 0 |
| tone_440hz_1s | 0.08601978421211243 | 1.1920928955078125e-7 |
| noise_lcg_8s | 0.9380639791488647 | 2.384185791015625e-7 |
| chirp_9s | 0.40278559923171997 | 1.1920928955078125e-7 |
| quiet_one_lsb | 0.978554368019104 | 1.1920928955078125e-7 |
| dc_short | 0.9848582744598389 | 2.384185791015625e-7 |
| impulse_one_sample | 0.9619386196136475 | 1.1920928955078125e-7 |
| public_librispeech_last8s | 0.4509245455265045 | 1.1920928955078125e-7 |

Every probability difference is zero absolute / zero float32 ULP. Maximum
frontend error is `2.384185791015625e-7`, within the unchanged `2e-6` fixture
tolerance. Independently recomputed official features reproduced all frozen
golden bytes exactly. Existing expectations were not regenerated or replaced.

Unprepared inference, pre-cancelled inference and nonfinite input were rejected;
the retained session then reproduced its first probability exactly after recovery.

## Host phase observations and limits

Across the eight reported production calls, frontend time was 13.641861–17.454483
ms, model inference time was 50.608356–62.797250 ms, and the per-call sum was
64.821680–77.105418 ms. These are one-run host observations, not a performance
benchmark or phone estimate. Model/session initialization was **not separately
measured**: construction preceded the per-call timers, and whole-child wall time
must not be presented as initialization time.

This establishes bounded host frontend/session I/O agreement for these fixtures.
It does not establish JNI, Android, NDK/R8/APK linkage, device acoustics, phone
latency/thermals/contention, in-flight cancellation races, turn-end calibration,
endpoint quality or permission to activate an authoritative endpoint. Separate
encoder/keyguard work and private exports were outside scope.

## Retained evidence

- Original `receipt.json`, SHA-256
  `5806234b80caa3328e9a84a49258ded742a881a1d85fbe55c9cc3b4eed199cdf`.
- Original native/reference and compiler stdout/stderr, plus launcher logs.
- `independent-result-review.json`: independent post-run byte, array, identity,
  output and recorded-cleanup review. The original receipt's pending-review flag
  remains unchanged; the separate review supplies its later disposition.
- `host-phase-observations.json`, `downloads.json`, `execution-binding.json` and
  `cleanup.json`: derived observations, exact transfer/artifact identities and
  verified disposal of task-owned temporary files after review.

Only source, metadata and logs belong in the retained bundle. Downloaded weights,
runtime archives/libraries, compiled probes and generated PCM/feature arrays are
excluded. No candidate files or shared caches were edited or deleted.
