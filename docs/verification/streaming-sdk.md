# Streaming SDK release boundary

The existing Android APK workflow has a source SDK producer prerequisite.
A successful SDK producer starts APK assembly and full-E2B host quality in
parallel; neither early assembly nor signing establishes final verification.
It uses the exact upstream/source patch under `third_party/litert-lm-0.18.0`.
Implementation and commands are in [the helper guide](../../scripts/streaming-sdk/README.md).

## Observable acceptance checks

- Packaging/helper: the frozen production classes and normal JNI exports are
  present together; old Session/Conversation descriptors, test classes/entrypoints,
  changed source files, fake owner libraries and a missing runtime dependency fail.
- Provenance/helper: changed AAR/class/native/receipt bytes, a stale workflow
  run/attempt/source, wrong producer digest, or unknown ABI fail before Gradle.
- Native/hosted: crosscompile with NDK28.1.13356709 and native API30; inspect all
  seven GPU/runtime roots and recursive closure; retain every ARM64/16KB audit.
- Actual model/hosted: after the verified Android SDK artifact has been downloaded, bounded full-E2B
  native quality probes must complete successfully. A resource refusal, failed
  inference, missing result, or failed export is a failed quality job and blocks verified publication.
  Only allowlisted weightless receipts are uploaded. This is host-model evidence,
  not Android or phone inference coverage.
- Preserved release gates: NDK27 Sherpa/MicroWakeWord, all release JVM/native tests,
  existing signing identity, normal+compact equivalence and signatures, every
  API30/API35/API36 five-profile sandbox/upgrade/layout gate, exact-build receipt
  and all existing publication dependencies stay required.

The successful SDK AAR is selected by its producer's exact artifact ID plus the
current run's source and successful producer timestamp window. Both Gradle
invocations recheck the producer's AAR/provenance hashes and current workflow
identity, retaining the original successful SDK attempt on downstream-only
retries. Exact exported producer artifact names/IDs remain required; a newer
failed producer forbids fallback to an older successful artifact. The matching provenance and selection manifest are retained as
`jarvis-streaming-sdk-consumer`; the consolidated release receipt also verifies
both APKs embed exactly that provenance and retains the SDK and successful
weightless quality evidence. The full producer/source/ELF evidence is retained
separately. New source cannot silently fall back to Maven 0.18.0.

The SDK producer has45minutes and the independent quality job115minutes,
preserving the combined160-minute ceiling. Quality's internal compiler budget is
60minutes, its step63minutes and its model orchestration50minutes; all consume
the same115-minute quality ceiling, including setup and evidence export.
Reassembly/frontend/encoder retain4GiB address space,3.5GiB tree RSS and5GiB
admission. The fixed full-E2B Conversation control alone uses context640,
6GiB address space,4GiB aggregate tree RSS and6GiB effective host/cgroup
admission. Both keep one CPU,240s wall/CPU,128MiB file cap and1GiB live reserve.
Each raw/projected lane is fresh and hash-bound, with max64 output and no cache.
Whole-tree cleanup, including escaped sessions, must be verified before later
work; a persistent build-directory latch fails closed after interruption or
uncertain cleanup. No Android/GPU/default-context setting changes. Four admitted compiler CPUs/jobs retain the6GiB tree-RSS watchdog and
2GiB system reserve. Quality source is reconstructed exactly from the verified
source receipt, pinned SDK, patch and recorded overlays; Android binaries/cache
are not transferred to the fresh quality runner.

SDK and quality artifacts are selected against their own successful producer
job names, exact IDs and retained attempt identities. A quality retry may use an
older successful SDK from this run; a newer failed producer prohibits fallback.
Final binding ties the quality build's source-receipt SHA to both APKs' embedded
SDK provenance. Early APK upload includes an upload-time unverified notice with
the exact run URL; a later passing receipt supersedes it. Published release assets
remain APKs only, and every publication path explicitly depends on quality.

No signing keys are copied to the SDK job, no new secrets are provisioned, and no
model weights are uploaded. SDK provisioning reads EOF if a license prompt would
require accepting new terms; that is a blocker requiring review, never a pass.

## Explicit coverage limits

Helper tests and source inspection are not an ARM64 build. A source-built AAR is
not a signed APK. A signed APK is not a completed sandbox run. Host-model speech
quality does not verify Android GPU loading or physical Fold6 microphone/speaker,
Bluetooth/echo/barge-in, latency, memory pressure, thermal behavior or performance.
Those remain separately identified until measured on the actual device.

Do not claim a release candidate verified until the exact-revision existing
full release pipeline finishes. Do not merge or change any publication gate to
hide a missing stage. Preserve failed compiler, audit, helper and quality receipts.


### Pinned Android sampler dependency metadata

Build 1135 showed that the separate OpenCL/WebGPU TopK plugins require the builtin
runtime table exported by the source-built JNI library. Packaging now adds an
explicit dependency in verified derived copies, with no process-global symbol
promotion and no sampler code/model changes. The producer retains original LFS
pins, exact derived hashes, an 24-check actual-binary contract receipt, full
relocation/hardening evidence and the existing 16 KB audit. Consumers require
those exact derivation and recipe identities. All original Android load tests
remain active, including plugin-first loading. See
`scripts/streaming-sdk/SAMPLER-DEPENDENCY-NOTICE.md` for source/license boundaries.

The independent encoder replay capsule is optional diagnostic evidence. It
requires a same-build precompile guard, complete compiler/source/output bindings,
reviewed public notices and any required corresponding source. It excludes system
runtime binaries, model weights, audio, activation values and credentials, and
is uploaded separately under a 512 MiB bound. Its availability cannot approve a
release or replace the existing full-E2B prerequisite.

## LFM3-Omni U0: Maven LiteRT 2.3.0 coexistence (2026-11-15/16)

Unit U0 of the LFM3-Omni design (feature/lfm3-omni) adds Maven LiteRT 2.3.0 —
`com.google.ai.edge.litert:litert:2.3.0` and `litert-gpu:2.3.0`, the
CompiledModel API the Omni path needs because LiteRT-LM cannot feed
embeddings — beside the reviewed 0.18.1 bridge AAR above. The only caller is
the fenced `ai/OmniRuntime.kt` stub (kept in proguard-rules.pro so the
linkage survives R8 until U2 wires real callers). Verdict: **the two
runtimes coexist; U2 uses the Kotlin CompiledModel API as designed, and the
ranked JNI fallback is not needed.** Evidence: workflow run 1897
(43767967815) on head 26568d6f finished fully green (SDK producer, full-E2B
quality, signed release build with the full unit suite, all five sandbox
journey profiles, 16 KB check, consolidated receipt) and published the
branch prerelease `faraday-feature/lfm3-omni-build.1897` (app-release.apk
229,456,096 bytes; app-compact.apk 152,948,528 bytes).

Conflicts found, in the order CI surfaced them:

- **No native-library collision exists.** The bridge AAR ships no
  `libLiteRt.so`; the packaged inventory check added in this unit
  (`verifyLiteRtCoexistence` in app/build.gradle.kts, modelled on
  verifyWebShellAsset and wired into assembleRelease) prints every packaged
  ARM64 library and asserts both runtimes present plus exactly one
  `libLiteRt.so` of 5,629,696 bytes (the Maven 2.3.0 build). Run 1897's
  inventory (21 libraries, identical for both APKs): bridge roots
  liblitertlm_jni.so, libnative_audio_owner_jni.so,
  libGemmaModelConstraintProvider.so, libLiteRtGpuAccelerator.so,
  libLiteRtOpenClAccelerator.so, libLiteRtTopKOpenClSampler.so,
  libLiteRtTopKWebGpuSampler.so, libLiteRtWebGpuAccelerator.so,
  libwebgpu_dawn.so; Maven 2.3.0 adds libLiteRt.so, liblitert_jni.so
  (litert-api) and libLiteRtClGlAccelerator.so (litert-gpu). No duplicate
  class or duplicate native path occurred at any stage.
- **The design's suggested dedupe route is barred in this file.**
  `scripts/test_whisper_qnn_package.py` asserts the literal token for the
  pick-first mechanism never appears in app/build.gradle.kts (run 1894
  failed on that word in a comment). If a future bridge build ever ships a
  colliding library name, the resolution must be a packaging exclusion or a
  reviewed change to that guard — not a silent first-pick rule.
- **Lifecycle drag (run 1895).** litert-api 2.3.0's POM pulls
  androidx.lifecycle:lifecycle-runtime 2.10.0, whose bundled lint detector
  is binary-incompatible with AGP 8.7.3's lint (IncompatibleClassChangeError
  on the Kotlin analysis API's KaCallableMemberCall) and crashed
  lintVitalAnalyzeRelease. Both LiteRT declarations exclude
  lifecycle-runtime; the app keeps lifecycle 2.8.7, whose API covers every
  lifecycle type litert-api references (Lifecycle, LifecycleOwner,
  LifecycleCoroutineScope).
- **Play-delivery drag (run 1896).** litert-api's POM also pulls
  com.google.android.play:ai-delivery 0.1.1-alpha01, whose
  play-services-basement 18.4.0 depends on androidx.fragment:fragment
  1.1.0, tripping the fatal activity lint error
  InvalidFragmentVersionForActivityResult in lintVitalRelease. Both LiteRT
  declarations exclude ai-delivery; Faraday delivers models itself and
  never uses Play AI delivery.
- **Kotlin metadata.** litert-api 2.3.0's classes carry Kotlin metadata
  2.4.0 (mv = [2, 4, 0], verified from the published AAR), so the app
  module compiles with `-Xskip-metadata-version-check` (the project's
  compiler is Kotlin 2.3.21), as the design's section 8.1(b) anticipated.

Not changed in U0: `packaging.jniLibs.useLegacyPackaging` (design 8.1(c)).
The normal APK keeps direct-from-APK native loading; the compact APK
already uses legacy packaging. The GPU accelerator's by-path load is first
exercised in U4, which owns any packaging change it proves necessary.

Journey note: run 1897 attempts 1–4 failed only on the pre-existing
test30 StaleObjectException at ReleaseJourneyTest.kt:2115 (one of 82
tests; the same test passed on the other profiles in the same attempts).
Attempt 5 finished with all five profiles green. Nothing in this unit
touches UI, the call overlay, or any production code path.
