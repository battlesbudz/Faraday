# Opt-in Smart Turn phone shadow wiring — 9 October 2026

> Historical author checkpoint: the body below was recovered exactly from the
> earlier wiring work. After executor rollback, missing support was rebuilt and
> requires fresh admission. Use [current recovery checks](smart-turn-recovery-2026-10-09.md),
> not historical counts or receipts below, for the new candidate.

This follows the independently reviewed library checkpoint
`012d6fb226d97b23e1bafebe607a4d8fded0b1e6`. It adds an actual microphone-to-local-model
observation path, while keeping Smart Turn **off by default** and all existing
endpoint, native proposal/certificate, transcript and action authority unchanged.
The older [library receipt](smart-turn-shadow-2026-10-09.md) remains historical
host evidence for that exact checkpoint.

## Explicit setup and phone use

Voice input settings offer **Enable Smart Turn shadow (8.7 MB)**. The control
explains that this is local probability/timing collection and can contend with
other voice models. It is disabled while a call, chat turn or input test is active.
The existing cancellable settings setup path invokes `SmartTurnModelStore`, which
uses the existing `ModelDownloader` transport, the immutable auxiliary
`SmartTurnModelSpec` entry, pinned size/hash checks and atomic installation under
app-private `voice-models/smart-turn-v3.2`. It is not a selectable assistant model.
No model weights enter source, test fixtures or public build artifacts.

Only successful verified setup enables the preference. Cancellation, bad size,
checksum failure or native initialization failure cannot enable a partial model
or block ordinary voice. Missing/unavailable models leave a metadata-only reason.
Call/capture/answer paths never download or hash a model on their own thread;
native initialization rechecks exact immutable model bytes on its shadow worker.
Disabling persists for the next captured turn and does not alter ASR, captions,
Gemma, microphone profile or baseline endpoint decisions.

This adds no credentials or audio upload. Metrics and diagnostic completion
records contain bounded IDs/counters/probabilities/timing only, with no PCM,
transcripts, prompts or audio hashes.

## Capture observations and budgets

`CaptureShadowObserver` is an optional default-null, failure-contained observation
hook in `AudioTurnCapture`. It sees accepted pre-roll once, then each exact
retained PCM chunk, independently of whether the native encoder has frozen.
It has no endpoint return value and is independent of `NativePauseObserver` and
its certificates. Every observation carries the capture reader's absolute sample
boundary; subsequent PCM callbacks must advance by exactly that chunk's sample
count. Duplicate/gapped/non-PCM16 input revokes the observer for that capture.
A legitimate candidate reset explicitly starts a fresh continuity chain.

`SmartTurnCaptureObserver` keeps a fixed eight-second PCM16 ring. At most three
candidate attempts are allowed per turn, at least 500 ms apart and after at least
200 ms observed pause. These are experimental sampling rules, not endpoint rules.
Each accepted snapshot makes only the bounded recent-window copy required for
immutable inference. Busy, >40 ms capture backlog, severe thermal state and
native-speculation priority skip admission. Raw possible speech/unknown coverage
and corroborated quiet speech invalidate the generation before reading results.
Subsequent resumption is recorded against earlier observations for later cutoff
analysis; this is an observation, not a calibrated false-cutoff verdict.

Before native speculation or endpoint finalization, the capture owner revokes
new shadow work and requests cooperative cancellation. It never waits for native
drain. The worker receives Android background thread priority; one ORT CPU
thread remains pinned. Native encoding, Whisper or Piper can still overlap with
shadow work. No claim of zero contention or a hard real-time cancellation bound
is made.

## Call lifetime and failure isolation

`RuntimeVoiceResources` holds one `SmartTurnCallOwner` across capture turns.
The exact current capture can use the retained session. Call end, explicit stop,
failure, cancellation and runtime-scope completion revoke acceptance without
waiting for inference. `JarvisRuntime.finishVoiceCall` has an exact ended-call-ID
hook so typed-turn termination cannot bypass cleanup; `VoiceTurnFinalizer` is a
second exact-call guard, independent of Gemma quarantine.

A nonblocking close is not proof of drain. The same closing worker/session/budget
stays retained until its thread has actually exited. Next-call or re-enable
attempts report unavailable while that owner drains, rather than creating a new
worker. Stale old-call setup and stale finalizers cannot revoke a newer call.
Old capture completion callbacks retain their own observation identity and never
write into the next turn's benchmark.

Recoverable optional setup and diagnostic exceptions are isolated. Revocation
and close do not depend on a successful telemetry write. Existing voice/native
ownership barriers are unchanged, and no shadow join appears on the answer lane.

## Evidence and honest timing

Existing pipeline metrics collect per-observation probability, source age,
initialization/frontend/inference wall costs, actual worker lifetime, skips,
resumption and busy-at-priority counters. Unknown phase costs remain null.
The `post_priority_worker_wall_ms` observation measures unfinished shadow wall
time after priority revocation; it does **not** prove simultaneous Gemma execution,
CPU utilization, or that a rejected speculative proposal ran. Exact worker
start/finish timestamps accompany completion metadata for later correlation.

Benchmark rows intentionally freeze at turn completion. An in-flight observation
is explicitly marked `pending;late_completion_retained_in_diagnostics`, with
unavailable values. After native return, a distinct bounded diagnostic record
`smart_turn_shadow_0_work` through `_2_work` retains all available phase costs,
cancellation and post-priority wall time for its exact original turn, even when
the benchmark row has already frozen. No late callback mutates another turn or
pretends that a frozen row contains measurements it did not receive. Diagnostics
retain only the existing twelve-turn bound.

## Verification and remaining gates

The historical weight-free wiring receipt (not recovered; no current admission)
binds the final production source and local checks: 36 Smart Turn/store/late-record
JVM tests, 324 existing capture/voice JVM regressions, five capture-hook integration
tests, 105 Python helper tests and 82 verification harness tests. The scenario
contract's four focused tests also pass. Production source received independent
ownership/failure-isolation review. These are host checks, not device results.

Local coverage includes the original sixteen library tests plus observer/call
ownership, setup/integrity/cancellation, exact PCM continuity and late finalized
benchmark regressions. Actual capture integration regressions prove exact retained
PCM/pre-roll, observations after encoder freeze, raw resumption, and throwing
observers preserving endpoint/PCM/explicit-stop behavior. Existing capture,
queue/VAD, caption, session and retained-encoder regressions remain required.
Android/Compose API compilation uses actual platform/Compose jars and clearly
identified fixtures for unrelated native/model owners; it is not APK execution.

Two Compose tests cover default-off/busy-disabled behavior and persistent disable.
The named release journey
`test81_smartTurnShadowIsOptInAndDisablePersistsWithoutModels` is required by
`scenarios.json`; it covers the shipping control and recreation without weights or
network downloads. The fixture explicitly keys its composition when switching
from disabled/default-off to a preseeded enabled preference and asserts the
Disable label before clicking, so remembered state cannot trigger model setup.
The two Robolectric Compose tests are added but were not run in this host-only
fixture environment. Existing `VoiceInputSettingsKt` R8 retention already preserves
its exact test-DEX entry point; the new journey references no new production
class by name. JNI's five exact methods retain their existing narrow rule.

At this October 9 checkpoint the main Android suite remained limited to 900
seconds, with all existing assertions and profiles intact. The extra journey's
device/runtime cost was not yet measured. A previous Fold run had only 7.53
seconds of main-suite margin; that observation alone did not justify raising
the limit or removing tests. The later Build 1254 evidence and October 10
reviewed Fold-only 1,200-second aggregate allowance are recorded in
[the feature map](features.md); all 82 journeys and individual deadlines remain.

Pinned Sherpa commit `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` sets C++17 by
default at top-level CMake lines 370–374 (fresh pinned-source inspection) and disables language extensions;
`build_sherpa.py` supplies no lower standard. Source review establishes this
prerequisite. The actual changed-revision NDK build, signed/shrunk test-DEX linkage,
all five Android profiles, settings/download UX, real phone predictions, phase
cost distributions, thermal/resource contention, and premature-cutoff calibration
remain unverified until their respective release/device runs. No default decision
or endpoint activation follows from this wiring commit.
