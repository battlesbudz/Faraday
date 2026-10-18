# Gemma processing measurements — 2026-10-11

## Acceptance and preserved behavior

The goal is to distinguish preparation, retained audio-encoder work, SDK submission,
native callback delivery, guard release, speech synthesis and first answer playback
without claiming unexposed native phases. Measurements contain scalar counts/times
and fixed scope labels, never audio, prompts, transcript or generated text. Existing
model/microphone leases, callback cancellation, checked native idle/quarantine,
confirmed-history and memory-delivery fences remain unchanged. No SDK/ABI changes,
periodic sampler, extra native getter, performance improvement or device result is
introduced.

JVM acceptance includes synchronous callbacks before send returns, empty/control
callbacks before text, native-channel drain before cleanup, missing/reversed edges,
ambiguous reply attempts, cancelled native telemetry, empty Piper PCM, interval
uncertainty, old-record loading and JSON/CSV/copy export. Physical latency, native
model inference and actual playback still require the exact release/device gates.

## Three distinct clocks/scopes

1. **App observations:** `System.nanoTime`; turn stages use that same clock divided
   by 1,000,000, with exact observed edges aligned to the turn origin. Existing
   `ttft_ms` retains measurement-creation origin and includes lazy Conversation
   preparation. `submit_to_first_raw_text_ms` starts immediately before SDK send.
2. **Native encoder receipt:** final sealed candidate, successful checked close,
   reviewed AOSP API30–36 source-family contract and bounded calibration. Mapped
   intervals retain lower/upper uncertainty. Installed/OEM binary attestation and
   acoustic onset remain unavailable.
3. **SDK benchmark durations:** SDK-internal relative phases. They cannot be
   subtracted from app callback clocks, early-preparation wall time or one another
   to invent the missing queue/tokenization/multimodal phases.

Early session + fixed-preface preparation, encoder preparation wait, append/seal
API busy state, append count, cumulative/max work and queued/peak retained PCM
were already recorded. Their existing scope is retained. Session allocation versus
internal fixed-prefill compute is not separately exposed. Append may only buffer
or commit several graph steps; all-candidate append API work is never divided by
final-candidate step counts to create a per-step cost.

## Added edges and interpretation

Per submission records SDK send entry/return, first any callback and corresponding
FIFO consumer, first nonempty raw-text callback and corresponding consumer, terminal
observation, successful consumer drain, and post-cleanup benchmark snapshot. Explicit
callback-to-consumer and terminal-to-drain spans reveal dispatch/consumer delay.
A callback can occur before send returns. A missing terminal/consumer event remains
null; legacy total-duration fields remain readable. “Visible text” in the existing
adapter metric precedes Conversation policy guards and actual delivery.

Piper records first nonblank answer text collection and first **nonempty validated**
PCM, even if an earlier phrase produced zero samples. The first existing playback
observation is reused instead of resampling after diagnostic/storage work. New turn
spans connect callback → consumer → guard → Piper text → PCM → playback. Guard release
precedes queued delivery/memory-fence admission; PCM and playback-head advancement
are distinct, and neither claims microphone-measured acoustic onset.

Native submission events align only for exactly one completed ANSWER submission
with an explicit same-Java-clock contract and no omitted receipts. Drafts, retries,
tool/multiple-answer or incomplete attempts leave native-to-speech attribution
unavailable; caption/transcription-only passes remain independent. Each submission
still exports its own metrics regardless. This prevents associating another pass's
first callback with the heard answer. Nonnegative spans require both observed
ordered edges; no zero-filled residuals are used.

The existing final encoder receipt additionally supplies bounded elapsed intervals:
- Admission → last validated graph-step commit
- First → last validated step commit, including capture/inter-call gaps
- Signed endpoint → last step, negative when the final step preceded endpoint
- Signed endpoint → seal commit

These are elapsed boundary spans, never individual or cumulative compute time.
Missing mapping/endpoint data remains null; existing schema-1 records load with the
additive fields absent.

## SDK correction and safe-derived phases

Pinned LiteRT-LM [`GetTimeToFirstToken`](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/engine/io_types.cc)
uses the first recorded prefill duration plus first recorded decode duration divided
by its token count. It is not current-turn measured first-token time. Fixed-preface
initialization can change that proxy's origin. New records set legacy
`native_ttft_ms` null and use `sdk_first_prefill_decode_ttft_proxy_ms` with explicit
mixed scope. Historical records remain readable and the definitions identify their
old value as a proxy. `nativeFirstOutputTokenMs` and post-first-token exact throughput
remain unavailable because no first-output-token timestamp is exposed.

Successful checked-idle records separately derive `1000 * native_count / SDK_rate`:
- `derived_native_last_prefill_executor_ms`: matching last executor-prefill count/rate;
  excludes acquisition/task queue, tokenization, multimodal encode and input combine.
- `derived_native_last_decode_loop_ms`: matching last whole-decode count/rate; includes
  first token, native setup and callbacks. One output token can have this duration.

These boundaries follow pinned [`Tasks::Prefill` and `Tasks::Decode`](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/core/tasks.cc#L458-L669)
and native benchmark count/rate records; they are not pure GPU busy time. The six-field
Android API supplies no per-phase queue/encoding clocks or turn-freshness identifier.
The existing getter remains after successful checked idle only. Cancelled/error
records suppress native counts/rates/durations even if cancellation happened after
that safe read, because latest completed SDK fields can belong to earlier work.

## Verification

The final focused host run passed **133 JVM tests**, including actual raw-session adapters, native lifecycle, submission measurements, benchmark JSON/CSV/copy exports, mapped encoder intervals and empty-first-Piper-PCM regressions. The separate complete Piper dependency-closure API compile passed. Actual app sources
compile against the reviewed Build 1265 sealed-audio SDK classes, SHA-256
`dbb65a34071c5b16c0abbf20c69d83140b3d4ddf6f19d96ef91a3d503ebdf98e`;
no stock Maven SDK or fake SDK classes are substituted. Host dependencies include
Kotlin 2.3.21, JUnit 4.13.2, coroutines 1.9.0, Android-all 14 and Sherpa 1.13.7.
Only declaration-only AndroidX Keep and the unchanged ActionRequest declaration
are supplied to the focused engine harness. Complete production Piper source/dependency
closure is API-compiled without source stubs; JNI/Android playback is not run.

The integrated Android release build, full release JVM/native/package checks,
Android sandbox and physical Fold6/model/thermal/latency checks are still required
for the final combined revision. No APK is called verified by this host evidence.
