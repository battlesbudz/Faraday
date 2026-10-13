# Native frozen-pause capture contract (2026-10-09)

This is a source/policy change toward the existing last-spoken-word to first actual
answer playback goal. It is not a measured sub-second result, APK verification,
physical acoustic validation, or a change to the native SDK/EOA contract.

## Two distinct inputs

`AudioTurnCapture` always retains the ordinary complete bounded PCM recording,
including accepted onset pre-roll exactly once. `RetainedPcmObserver` remains the
no-drop, full-input encoder contract unless a `NativePauseObserver` explicitly
admits a separate immutable `NativePauseProposal`. At a qualifying approximately
300 ms clean pause, that proposal freezes the exact copied prefix with turn ID,
generation, candidate ID, PCM count/SHA-256, absolute capture sample boundary,
policy version, and proposal time. An admitted proposal permanently stops both
PCM delivery and candidate-reset calls to that encoder for this capture. Declined
admission leaves ordinary encoding intact. There is only one attempt per capture.

Capture continues retaining every consumed sample in its ordinary recording and
in a separate bounded 1200 ms reversible continuation tail. Resumed/weak raw
speech, corroborated quiet speech, uncertain coverage, hardware backlog at final
join, tail/window overflow, capacity, explicit stop, cancellation or capture
failure prevent promotion. Invalidated speculative encoders must be cancelled,
drained and checked-closed by their owner; they are never replayed or reset. The
fallback is a fresh ordinary complete-WAV request, not the frozen prefix.

Only `stop()` after the collector and `flowOn` producer join can publish a
`NativePauseCertificate`. It checks exact frozen prefix and continuation bytes,
full-recording count/hash, endpoint identity and completed raw VAD coverage for
EVERY excluded sample. Its `matchesCompleteWav` rechecks the canonical WAV bytes
returned to the turn owner. No PCM count/hash tolerances are allowed. The borrowed reader publishes a sticky
stop receipt before clearing its queue, counting unacknowledged session frames
and observed source backlog. Missing/nonzero receipts reject promotion. This is
an atomic *session admission* cutoff, not a hardware sampling barrier: a frame
still outside that lock is next-reader-owned when admitted, retained and replayed.
A latch-controlled test covers exactly that ordering. A candidate
can include an unfinished frame because those samples are inside its explicit
input; an excluded unfinished frame cannot be certified.

## Raw evidence and reversible endpointing

`FrameSpeechDetector` exposes cumulative received bytes, completed 512-sample
frame watermark, the boundary after its last raw probability >=0.15 frame, and
fresh completed-frame count. Its legacy cached probability is unchanged for
existing consumers, but is never a coverage certificate. Noise gating may zero
a weak raw decision; the raw callback still revokes the proposal synchronously.
Frames are not padded. Actual subsequent producer frames can classify a split
frame across the frozen boundary, while prefetched unacknowledged PCM retains its
existing next-reader replay ownership.

The native-only proposal requires sustained >=240 ms strong VAD, no ASR quiet
corroboration, no pending onset, no buffered audio, a complete bounded recording,
>=300 ms clean raw pause and the existing runtime playback-tail/onset guard.
Unknown playback timing, protected follow-ups, comparison routes and missing
native observers retain the ordinary path. Capacity and silence clocks are not
redefined. Proposal timestamps never replace endpoint timestamps or last speech.

## Completed captions and endpoint policy

Async Whisper's completed result now carries the original *ungated accept-input*
sample cursor captured at decode scheduling. This is separate from its trimmed
model-window size, stable-prefix display words and worker completion time. Capture
receives completed cues during silence even when partial decoding is disabled.
They update a separate endpoint detector, never quiet-speech corroboration,
follow-up evidence or last speech time. A result whose source window predates new
speech loses permission to shorten the independently maintained legacy policy.
No new completed-cue optimization is permitted after ASR segmentation; unresolved
overlap seams cannot assign a newer audio watermark to older committed words.

All newly accelerated final endpoints, including a newly delivered complete cue,
require exact collector-boundary raw coverage plus the native admission guards.
The completed raw quiet suffix must also cover the whole newly chosen margin
(650 ms, or a newly enabled 350 ms cue), even when a weak raw continuation did not
refresh confirmed last speech. Proposal eligibility remains 300 ms; independently
qualifying legacy endpoints, including fixed650, are unchanged.
An unfinished raw tail falls back BEFORE capture stops. This does not slow the
independently qualifying legacy `complete_and_stable` 350 ms endpoint. Known
unfinished/uncertain/hesitation decisions retain 1800/1500/3500 ms. For qualifying
native captions-on turns, missing caption's Whisper 900 ms and complete-settling
1100 ms margins may use 650 ms. Captions-off native already used 650 ms. If a busy final decoder newly reveals
unfinished, uncertain or hesitation wording during a new accelerated endpoint,
that endpoint is revoked and the sealed ASR/pending PCM remain owned until the
1800/1500/3500 ms margin or resumed speech. Older partial cues cannot override
that final guard. Complete
questions with at least four words already qualified without punctuation; that
legacy behavior is preserved.

Default input chunks are 1600 samples; VAD frames are 512 samples. Exact boundaries
coincide every eight input chunks (800 ms of the capture cursor), so a 650 ms
threshold is not a promise to stop exactly at 650 ms. For example the deterministic
1600-sample fixture with speech ending at 900 ms safely stops at 1600 ms (700 ms
silence), rather than the legacy 1800 ms. Other phases retain the 900 ms fallback.
A 512-sample fixture stops at672 ms of silence. Neither figure includes model/TTS
work or establishes device performance. An acoustic-only pause cannot distinguish
all thinking pauses; software VAD probability is imperfect, not proof of intent.

`native_pause_eligibility`, `native_endpoint_cue` and capture-summary diagnostics
record eligibility reasons, raw/collector watermarks, eligible/uncovered frame
counts, proposal count, and actual endpoint cue/margin without logging raw PCM.

## Verification boundaries

Deterministic JVM tests cover frozen/full identity, defensive copies, split 512/
1600 chunks, stale partial-frame probability, raw weak speech hidden by noise
floor gating, real subsequent-frame coverage, unknown coverage, hardware backlog,
tail bounds, exact fallback recording/pre-roll, spent-encoder feed suppression,
resumed audio, explicit stop/cancellation, completed cue freshness and unmapped
segmented seams, protected playback routes and the 350/650/900/1500/1800/3500 cues.
Existing capture, Whisper retirement/resume, interruption, timing, retained-input
and candidate tests run alongside them. See the integration evidence for the exact
revision and counts; no Gradle/SDK/adb install, model run or weight download was
performed for this source checkpoint. Full release JVM/native/Android/device and
real-model latency verification remain required before an APK is called verified.

Final local source checkpoint: 322 expanded JVM tests passed, including the actual
reader-stop replay and latch-controlled admission cutoff regressions. The actual
Factory/capture/resources/Whisper adapter boundary compiled against the cached
Android API with compile-only declarations for unrelated/native constructors;
this is not a full app/native ABI build. Architecture checks and 102 + 82 Python
helper tests passed. One local harness compile initially omitted the existing
`PcmChunkAssembler` source that owns `AudioBacklogException`; that failed log was
retained, the harness source list corrected, and the complete suite rerun.

Native deferred-endpoint drain uses producer-owned cumulative PCM byte positions,
so adjacent frames sharing a capture timestamp cannot hide a weak/unknown tail.
Later real complete silence coverage can resolve partial-frame uncertainty;
missing coverage or fresh weak decisions remain guarded until consumed. The
ordinary timestamp-based drain policy is unchanged.
