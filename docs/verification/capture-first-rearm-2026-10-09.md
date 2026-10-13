# Capture-first rearm candidate — 9 October 2026

Status: first candidate failed independent review; **not approved for integration or an APK**.
See the [separate repaired successor](capture-first-rearm-repair-2026-10-09.md).
The tests and open gates below describe the original `e784dbb` snapshot.
The integration owner must resolve the open review gates below before publication.

## Behavior and ownership

- A positive, fully sealed `SpeechDeliveryState.COMPLETED` receipt transfers the
  retained recorder to a bounded raw follow-up reader. TTS job termination,
  failed output, cancellation and empty playback do not establish delivery.
- `CaptureFirstAudioInput` owns one retained-session borrower and one producer.
  It copies ordered PCM with exact source sequence/time provenance, retains the
  existing playback prefix and any older pending reply candidate, and fails the
  entire request if the six-second byte bound or required prefix is exceeded.
  It does not rely on the underlying session's 64-frame reader capacity.
  Stopping the old logical listener does not stop this borrower. Acknowledgement
  belongs to actual logical consumption, never raw prefetch or a retired reader.
- Raw recording readiness precedes the old interruption listener's native join.
  The ordinary ASR capture starts only after that listener joins. A sealed next
  input remains under the old exact turn until its caption child has canceled
  or completed, joined, passed native-safe checks and reset confirmed history.
  The existing speculation `beforeNativeMutation` barrier after conversation
  join, drain checks, quarantine and model/microphone leases remain in place.
- The delivered answer is persisted independently before optional caption work.
  New accepted speech or a queued typed input revokes the exact old caption
  ticket. Typed observation uses a nonconsuming StateFlow revision, so immediate
  claim/promotion cannot restore publication or consume a dispatch wake.
- `CaptionPublicationFence` retains completed old caption effects while an
  existing playback-tail candidate has no verdict. Microphone transfer does not
  wait for that publication. Echo rejection makes the backstop eligible again;
  accepted speech revokes it permanently. Queue and controller identity gates
  prevent stale transcript, memory and farewell effects on a newer input/call.
- The opportunistic farewell helper reads only final Whisper values already
  available at its existing post-capture-join check point. It cannot obtain a
  recognizer/lease, trigger decode, refresh, await, or retry a missed snapshot.
  Missing, retired-idle, provisional, failed, stale, wrong-engine/call/input
  evidence gives no decision. Existing anchored intent and echo guards apply;
  this does not claim calibrated ASR confidence. Gemma remains the backstop.
- Recording, ASR capture, caption cleanup and sealed-input release have separate
  markers. `playback_to_capture_rearm_ms` measures the actual raw transfer;
  downstream native wait must be evaluated separately. No device latency or
  subsecond performance claim is made.

## Open independent-review gates

1. Audit a fresh raw ordinary onset before retained-onset acceptance. The current
   pending fence explicitly covers existing reply candidates and retained echo
   candidates; determine whether raw preconfirmation uncertainty needs a
   separate temporary hold to prevent old farewell effects winning that window.
2. Audit ASR-disabled playback-tail behavior. Ordinary Gemma admission must keep
   accepting valid complete audio without a text prerequisite; failed/missing
   lexical evidence must not allow an old farewell to overtake real sealed input.
3. Review the exact Preparation/Finalizer overlay against the complete recovered
   SmartTurn candidate. Local foundation `06f3311536e5cb954971946c7aa5ae0562a73ece`
   contains exact recovered Kotlin support only, not the full SmartTurn build,
   native, license and test-support change. Do not publish that foundation.
4. Run the full release JVM/native/APK and named Android sandbox gates on the
   eventual integrated revision. Validate actual microphone/speaker echo,
   native model drain and device timing with real models/device evidence.

## Fresh local verification

Receipts under [receipts/capture-first-rearm-2026-10-09](receipts/capture-first-rearm-2026-10-09)
bind exact source/dependency hashes and commands; paths in commands identify the
local cached-dependency harness, not a device run.

- 100 JVM tests pass for final-evidence policy, anchored negation/quotes (including
  single quotes), echo eligibility, exact call/revision, typed revision races,
  caption effects/backstop, delivered-answer persistence and checked lifecycle.
- 38 actual-source owner JVM tests pass. They execute the real session, bridge,
  capture and lifecycle helpers with synthetic hardware/model callbacks. The
  nine-second fixture compares all 288,000 PCM bytes across WAV/ASR/observer
  ownership while old Gemma cancel/drain/reset is parked. Tests cover 64+ short
  frames, exact long candidate prefix, six-second overflow, missing prefix,
  deferred acknowledgements, single closure and sealed-input admission barriers.
- Android API boundary compile attempt 017 passes on all 143 complete actual
  Kotlin source files, including every changed production file, Preparation,
  Finalizer, real input/session/capture factories and SmartTurn hooks. Cached
  Kotlin 2.3.21 and the real Android 14 API JAR were used. Unchanged native/model
  and unrelated platform collaborators have explicit compile-only fixtures.
  Receipt SHA-256: `ff3361ede6022a0d36d68ccfdb5335cc29720556db87d65b2fa5f1456c5ac54b`.
- Architecture dependency boundaries and `git diff --check` pass. An attempted
  Python unittest invocation lacked the scripts import path; those suites were
  not rerun before the integration owner paused compute for the review slot.

The focused pending-typed test initially expected `awaitAvailable` to succeed
for an item already claimed/promoted. That availability expectation was invalid;
its correction preserves immediate-claim revocation and separately verifies a
future dispatcher wake. A playback-overlap fixture exposed the ordinary tail
matcher's intentional `onset >= playback-end` boundary; overlap now uses the
existing active-barge final-text resolver. Boundary attempt 015 caught a missing
factory forwarding argument; complete attempts 016/017 verify its correction.
Failed local logs are retained by the integration owner.

## Recovery provenance

The executor reverted during this work. Four recovered files had matching
retained hashes (snapshot helper/test and policy/test); remaining original edits
were reconstructed and explicitly labeled unverified in immutable recovery
artifacts. Fresh candidate tests/compilation establish only the current source
behavior/API compatibility. They do not prove byte-identical recovery of the
lost work. SmartTurn foundation files were separately verified against exact
remote blobs before their local overlay. Historical counts are not current APK
verification.
