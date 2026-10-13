# Smart Turn capture-first follow-up coverage — 9 October 2026

## Behavior and ownership

The existing default-off Smart Turn setting now applies to capture-first follow-up
captures as well as initial captures. `ReplyCaptureBenchmark` attaches the same
`RuntimeVoiceResources.smartTurn` call owner to its `AudioTurnCapture`. No second
model owner, recognizer, inference lane or endpoint authority is introduced.

`CaptureFirstReplyHandoff` passes a read-only eligibility callback tied to its
exact old reply child. It returns true only after that child completes
successfully, including its joined caption/native cleanup and checked reset.
Cancellation and failure cannot qualify. The capture also checks its own exact
job, old parent turn identity, current armed call, typed-input fence, native
release/quarantine state and thermal status before an offer. The old model lease
remains owned by the structured turn; successful reply completion certifies that
its native work is idle, and no next reply enters before capture seals.

Raw microphone retention starts before old listener cleanup as before. The ASR
capture begins after that listener joins, preserving its one recognizer slot.
Shadow eligibility never adds a wait, joins inference, refreshes an ASR result,
changes the acoustic gate or interferes with caption publication. If cleanup
outlasts the capture endpoint, there is no follow-up observation. Skipped attempts
still consume the established per-capture budget; eligibility does not retry them.

Already sealed follow-up input runs call-owner lifecycle checks with
`observeCapture=false`. A valid existing call worker is retained rather than
closed and reloaded. Disabling the setting, unavailable model, changed call,
call end and process teardown preserve their existing revocation/close behavior.
The worker stays retained while old native work drains and cannot be replaced by
a new concurrent worker. Unique utterance IDs fence follow-up generations; their
local capture generation is zero because each ID has exactly one live capture.

The existing limits remain three attempts, 500 ms spacing, a 200 ms minimum
pause, a 250 ms deadline and an eight-second private PCM ring. Native/endpoint
priority permanently revokes the observer for the rest of that capture, including
echo rejection and reader reset. No priority rearm is added. Cooperative cancel
cannot guarantee immediate native exit; old timing evidence retains this limit.

## Evidence attribution

The old reply's existing benchmark row holds `followup_smart_turn_*` metrics and
configuration, separately from its initial `smart_turn_*` values. Provenance
explicitly records `next_capture_in_previous_reply_row`, a correlation-only
`followup_smart_turn_utterance_id`, and `shadow_not_answer_endpoint`. These keys do
not replace or contribute to the old answer's named endpoint/latency fields.
No new row sampling or storage is added to the rearm path.

The exact keys `followup_smart_turn_utterance_id` and
`capture_first_raw_transfer` are excluded from report comparison identity and the
TXT shared-provenance directory. The second value is the raw bridge's per-transfer
observation (`capture_first_raw_ready prefixBytes=... firstSequence=...
lastSequence=...`), not a capture setting. Every raw JSON/CSV row and TXT per-turn
correlation line retains the complete original ID/string. Only these exact keys
are added to the existing exclusions; actual capture profile and Smart Turn mode
still separate comparison groups and shared provenance.

Independent owner review found both omissions after the first candidate: otherwise
identical completed turns incorrectly formed separate groups, including when
shadow was disabled. Report and TXT regressions reproduced each split before its
exact-key repair. One intermediate fixture run also rejected a duplicate turn ID;
the fixture now uses a distinct ID for the real-configuration comparison. All
failed receipts are retained, and final tests preserve both raw values and prove
that real profile/mode differences still separate.

Diagnostic completion records use the exact next utterance ID. Only bounded
probability/timing/configuration metadata and ordinary correlation IDs are added;
no PCM or transcript is exported by this lane. Missing phases remain null or
absent. A frozen benchmark retains its pending completion marker, while late
completion is stored as exact old-request diagnostic metadata, as before.

## Acceptance and verification

- Actual raw bridge, `AudioTurnCapture`, handoff and shadow owner: held caption
  native close/reset allows continued raw speech consumption; zero shadow
  inference before successful completion; later eligible pause permits one call
  worker to observe. The ASR-disabled capture still retains complete native audio.
- Same combined callback graph: capture ending before native drain records no
  observation and cannot delay raw rearm or release next input before cleanup.
- Actual final-verdict/reader callbacks with a real shadow observer: echo rejection
  invalidates the candidate and retains its one reader/recognizer ordering;
  later speech succeeds while shadow remains revoked.
- Exact failed reply completion never produces native eligibility. Existing
  cancellation, typed-input, stale-owner, disabled/model-missing and undrained
  worker tests remain included.
- Same-call sealed input preserves the existing worker/backend; disable,
  model absence and call replacement still close it.
- Namespaced metrics preserve initial values and next-ID completion attribution.

The first combined host run exposed a new fixture clock mismatch: capture and
worker used synthetic time but the owner-created observer used real monotonic
time. Its timeout evidence is retained. The owner now accepts an injected clock,
with the production default unchanged, so all fixture phases share one clock.

Fresh JVM checks and Android API compilation are bound by the accompanying
source-hash receipt. API compilation uses unchanged native/model/platform
collaborator signature fixtures and actual Android framework dependencies.
No weights, downloads, model inference, JNI/NDK/R8/APK execution, Android journeys,
physical audio, measured phone latency or quality claim is made here. Exact-head
hosted release gates and physical-device comparison remain required.
