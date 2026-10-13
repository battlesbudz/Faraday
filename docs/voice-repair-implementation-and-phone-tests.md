# Voice repair: bounded commits and phone test protocol

## D1/E2 implementation after build 728 (2026-09-20)

Implemented within existing PR #6; phone effectiveness remains unaccepted.

- D1: added explicitly armed **Record next call audio for diagnosis** beside the
  existing call ZIP export. It captures post-platform microphone PCM, exact natural
  probe submissions, written Piper answer/filler reference PCM and monotonic
  playback-head events. Retention is RAM-only, capped at 4 MB of PCM, 800 KB per
  stream, 16 streams and 1500 timing events. Trimming is reported with frame offsets.
  Export writes WAVs and `audio-evidence.txt` only on the user's save action. Default
  calls retain no audio through this diagnostic. Pause/end/failure stops capture;
  a later call clears old evidence and requires rearming. Clear disarms and erases
  retained audio. Tone cues, volume/fades, pre-platform PCM and calibrated acoustic
  latency are explicitly not captured. This is an input/source comparison, not a
  measured AEC attenuation result. Existing AndroidAudioInput already attaches AEC
  to the active AudioRecord session and logs availability, control and enabled state.
- D/E admission: the natural interruption listener previously used Silero directly,
  bypassing the room-floor gate used by ordinary capture. It now uses a local
  CaptureSpeechGate, without modifying the ordinary-listening noise profile. Exact
  digital zero cannot pass a stale VAD decision. A calibrated floor reduced to zero
  is now persisted; previously the shared profile retained its old nonzero value.
- E2: a probe with at least 240 ms of strong confirmed VAD speech retains the
  existing one-probe lexical settling path. Uncertain candidates require at least
  96 ms admitted speech plus agreeing/extended words from a subsequent probe with
  at least 250 ms additional PCM and a newer capture timestamp. Silence/no budget,
  disagreement, stale results or merely waiting do not confirm it. Work remains
  bounded by the existing three-probe candidate limit, rolling quota and native
  ownership. These are initial engineering criteria, not device-tuned guarantees.
  Short words are not blacklisted; Stop/Hey Jarvis verification is unchanged.
- E2: final correction capture now enables the existing acoustic follow-up guard.
  A validated initial natural probe can corroborate a short correction only when
  the final capture also contains confirmed speech; it is not substituted for the
  final transcript. Rejected candidates clear that corroboration. Existing final
  non-echo text validation and floor-only handling still run before dispatch.
- Speech evidence now counts confirmed 32 ms VAD frames (onset credited once), not
  the entire retained PCM chunk. A long pre-roll with only 96 ms speech cannot be
  misreported as a second of strong speech. This accounting applies to both probe
  support and final follow-up admission.

Developer validation: focused JVM tests cover fresh-input agreement, unchanged
hypotheses, disagreement/empty results, keyword controls, preserved onset, short
corrections, capture rejection/recovery, VAD duration, zero-floor state, bounded
exports and disarming. Full release CI, native packaging and signing are required
before an APK is described as ready. No claim of reduced false-trigger rate yet.

### Next phone card (after release CI passes)

Keep E2B, Moonshine, Piper, phone position and call volume as in build 728. No model
redownload or settings reset. In Development diagnostics, tap **Record next call audio for diagnosis**, then start a normal call. This
records nearby speech too; only export a call intended for diagnosis.

1. Ask for an explanation or story and remain silent through filler and playback.
2. During later answers, make a controlled cough/rustle, then separately say No,
   Stop and a normal correction. Note what you actually said and any false stops.
3. Remain silent briefly after an answer; verify no invented next request.
4. End the session, then **Save latest call test ZIP** before starting another.
   The ZIP should contain `report.txt`, `audio-evidence.txt` and available WAVs.
   Export promptly because PCM is rolling and earlier intervals may be trimmed.

D2 acoustic acceptance still needs this live-call evidence. D3 software AEC remains
conditional. E1 reversible playback is not introduced by this increment; ambiguous
candidates leave output running. F1/F2 full P4/P5/P6 acceptance remains open. Do not
repeat previously completed isolated D2 replay tests or retired voice suites.

## Build 728 false-trigger follow-up and plan reconciliation (2026-09-20)

Status: **planned refinements to D/E/F; no runtime change or new phone acceptance claimed.**
Reviewed the current plan on `audio-pr2` at `ec33fe47de225618f6705152d0137737b7a719d0`
and Justin's two build-728 call reports. Continue under existing PR #6. The
19 September removal of speaker identity remains authoritative over historical
owner/voiceprint requirements below. Preserve Moonshine/Whisper selection, Piper,
single-word interruption and Stop/Hey Jarvis behavior. Release APKs only.

### Evidence and completion boundary

- Call `94d11fc0-3ec5-4d08-971f-d0ae70976b6d` records three natural confirmations
  ("Actually", "Tell me, ah", "Thank you"), with prompt redirection after the first
  two. The last reports 895 ms from detected onset to stop request. Justin reports
  high intentional-interruption success and consistently working Stop. These are
  positive observations, not a counted 99% reliability result or dedicated keyword
  acceptance; the exported natural summaries show zero stop hits.
- Call `fe81f371-1396-41ef-9ee1-42680d72ad8a` records "You" and "Thank you"
  accepted after one probe each; "Thank you" became a subsequent Gemma request.
  "All right" remained at words_settling when the UI/service ended the call; it
  must not be counted as a third confirmed natural interruption.
- Justin reports non-speech/possible self-playback pickup. These reports contain
  no microphone PCM, so the physical source of each suspect transcription is
  unresolved. `echoMatchedWords=0` establishes no textual match, not absence of
  acoustic echo. `floorRms=0` warrants inspection but may reflect suppressed
  input; it is not by itself proof of a broken gate. Native Moonshine VAD bypass
  does not mean the external Silero/acoustic gate is absent.
- Intentional barge-in has encouraging phone evidence. D2 effectiveness, E false
  interruption/recovery and F integrated acceptance remain open. AEC enabled
  flags and a successful intentional interruption cannot close false-trigger gates.

### Existing scope versus additional detail

| Proposed work | Existing plan coverage | Incremental work |
| --- | --- | --- |
| Inspect microphone/decoder/rendered audio and AEC effectiveness | A2/A3, D1; existing isolated echo/recognition diagnostics | Extend/reuse bounded evidence for the actual live-call candidate interval, including filler and inference load, if current tools cannot capture it. |
| Audit noise calibration, external speech gate and communication route | C recognition repairs, D1/D2; communication route already integrated on 18 September | Reproduce build-728 zero-floor and suspect-input cases; fix only demonstrated defects. Do not rebuild routing or blindly toggle native VAD. |
| Fresh evidence before accepting uncertain words | E1/E2 admission and recovery; current 300 ms lexical settling | Explicit new refinement: conditionally obtain another bounded decode using newly arrived audio for uncertain candidates. A timer on one hypothesis is not another observation. |
| Prevent false final transcripts from entering conversation | E2 final request validation and existing final non-echo filtering | Strengthen/test the existing validation contract against acoustic evidence for the final input, covering ordinary follow-ups as well as interruption corrections. |
| Preserve genuine interruptions while measuring false triggers | P4/P5, F1/F2 | Add build-728 regression cases and separate false accepted turns from false playback stops. Keep existing acceptance counts/latency reporting. |
| Software echo cancellation | D3, already conditional | No automatic new dependency. Evaluate only if measured D1/D2 results demonstrate insufficient platform cancellation. |

This is mostly completion/hardening of the existing D/E/F scope, not another
architecture or a replacement plan. The explicit new behavior is selective fresh
confirmation of uncertain candidates; the other additions make evidence and final
admission requirements more precise. Exact coding effort remains unknown until
the affected current source and captured failure audio are inspected.

### Bounded implementation increments within the existing phases

1. **D1/D2 follow-up — reproduce before changing thresholds.** Audit effect
   attachment/control on the active AudioRecord session, actual communication
   capture/playback routes, and calibrated versus uncalibrated/suppressed floor
   handling. Reuse existing diagnostics; add only missing bounded, user-initiated
   live-call capture of microphone input, actual ASR input, rendered-reference
   timing and gate decisions for candidate windows. Include filler, answer and
   reply-to-listening boundaries, route, volume, model/build and thermal state.
   Where Android does not expose pre-effect PCM, label microphone input as
   post-platform-processing; never invent a before/after AEC recording. No default
   raw-microphone export or automatic upload. Preserve failing clips when explicitly
   exported. Gate: distinguish echo, non-speech decoding and pipeline admission
   defects, or clearly retain unresolved source attribution.
2. **E1/E2 follow-up — conditional fresh confirmation.** Inspect the current
   candidate state machine before implementation. For candidates with uncertain
   acoustic/echo support, require a fresh result that includes newly captured
   audio before irreversible interruption. Re-decoding the identical clip or
   merely aging its text cannot count as independent corroboration; overlapping
   decodes are supporting evidence, not statistical independence. Keep the work
   bounded by existing freshness, ownership and scheduling contracts; preserve
   onset pre-roll. Define timeout/rejection behavior explicitly. Clear short
   speech and verified Stop/Hey Jarvis retain their fast paths. Do not impose
   two probes on every request, blacklist "you"/"thank you", require a wake phrase,
   add speaker identity, or pause on VAD/noise alone. Any reversible pause still
   follows lexical confirmation under the 17 September override.
3. **E2 follow-up — validate before dispatch/persistence.** Apply existing final
   correction checks to the actual final speech interval and retained evidence,
   not merely nonblank text or agreement with an earlier suspect hypothesis.
   Inspect both interruption handoff and ordinary follow-up paths. A rejected
   noise/echo transcript must not become a user-history entry, Gemma submission,
   filler response or tool action. Preserve real single-word input. If a candidate
   was only provisionally paused, resume retained audio through E1; a confirmed
   user Stop must never auto-resume the old answer. Validate late-result and
   cancellation races. This is a strengthened existing contract, not a claim
   that final filtering was previously absent.
4. **F1/F2 follow-up — acceptance evidence.** Add deterministic tests for
   one-hypothesis timeout versus fresh corroboration, zero/suppressed calibration,
   non-speech/echo transcripts and final admission, plus short genuine words,
   overlapping speech, onset retention and no late dispatch. Add real audio
   regression fixtures only when their recording/ground truth is available;
   do not manufacture waveforms from text logs. Test affected code and release
   packaging/signing before handing over an APK. A docs-only update needs no APK
   or repeat phone test.

### Focused phone card after the relevant code increment

Use the existing normal-call and diagnostic controls; add no promised UI until
implemented. Keep route, phone position, selected ASR, Piper and volume fixed.

- Jarvis-only: remain silent through filler, answer and the follow-up listening
  period. Count both false playback stops and false new user turns.
- Non-speech: controlled cough/rustle and ordinary ambient noise, with event times.
  No noise-only candidate may stop output or generate a user request.
- Genuine speech: No, Yes, Stop, Hey Jarvis, and a normal correction, including
  early/late overlap and a quiet utterance. Record attempts, misses, retained
  first words and stop-request timing separately from actual acoustic silence.
- Nearby conversation: label as competing real speech, not non-speech failure.
  With identity disabled, do not promise owner-only floor-taking.
- Begin with a short screening card. Only at the acceptance milestone run the
  existing P4 counts and P5 ten-minute false-trigger gate; extend P5's zero-false-
  cancellation criterion to zero falsely dispatched user turns as well. Compare
  matched success and latency against the working baseline and report added
  confirmation cost. Do not claim 90–99% from an uncounted mixed session.

Keep D3 conditional; first determine whether residual playback persists despite
correct D2 configuration. Keep answer-startup latency and shared-conversation
provenance review separate from acoustic fixes. Build 728 intentionally introduced
shared Chat/Voice history, so older entries alone do not establish a new-call
regression; reconcile the intended shared-thread contract before changing it.

## Remove speaker identity from voice calls (2026-09-19)

User decision: no learned voiceprint or owner-identity requirement in the call path.
Removed speaker-model download/load, enrollment, candidate identity rejection,
interruption identity scoring and its playback-embedding buffers. Previously stored
voiceprints are no longer read. Moonshine/Whisper ASR, platform communication-route
AEC/NS, word-based echo rejection and keyword interruption remain in place.
Natural interruptions use the existing 300 ms lexical stability gate, with no
speaker worker, matching score or enrollment prerequisite. Final correction text
uses the same ordinary non-echo gate, without pretending an identity match occurred.
No new echo algorithm or replacement identity model was added.

Evidence: build-720 mixed call a05f53d0 retained five playback stops and nine speech
candidates rejected/uncertain at the speaker check. Those counts are not a measured
success rate and the report contains no microphone recording. Removing the identity
gate requires phone verification that the working communication-route AEC continues
to suppress playback echo. Any person's recognizable non-echo speech can now take
the floor; this is intentional.

Phone check: use Moonshine in one ordinary call. Let an answer play while silent,
then cough/rustle, then interrupt later answers with No, Yes, Stop, Hey Jarvis and a
normal sentence. Record which attempts failed, stop the session, and save its call
test ZIP. One mixed call is useful; separate calls are not required for this check.
Regression tests cover no-enrollment single words, short speech, retained onset,
non-speech captions, playback echo, keyword fallback and final correction filtering.

## Build-720 comparison fixes (2026-09-18)

The uploaded Gemma-transcription trial returned no usable transcript; the old path
submitted a placeholder as a text request and counted the resulting clarification
as a complete benchmark. This is now rejected before answer generation. Audio
transcription explicitly disables tools, uses a fresh conversation, and retries
missing output once using the same original audio within the existing 12-second
budget. Explicit no-speech results are not retried. Each attempt retains raw text,
stream-event count, tool-call count and duration in its comparison ZIP. Cancellation
still propagates and native conversation cleanup remains serialized.

Failed recognition cannot produce a valid comparison or WER score. Missing
transcripts remain missing; direct audio answers do not claim a transcript-final
timestamp. The original empty native output's precise cause is not established by
the old ZIP; phone verification of recovery is required.

Whisper's explicit `(buzzer)` / `(buzzing)` captions (and beep/ringing captions) are
removed before word-based interruption decisions. Plain spoken words and words
alongside captions, including No/Yes/I/Stop, remain eligible. Communication routing,
AEC ownership and speaker checks are unchanged.

Phone follow-up: run only the Gemma transcription comparison again and export each
of its three ZIPs, including failures. There is no need to repeat the nine completed
Moonshine/Whisper/direct-audio comparisons. For the caption fix, use Whisper in a
separate call: non-speech during playback must not interrupt; a spoken No must still
interrupt. Save that call's test ZIP. Automated regression tests cover retry bounds,
no-speech/cancellation, invalid scoring and the caption/word gate.

## Phase D2 — live-call communication route integration (2026-09-18)

Implemented the D2 phone-speaker profile in real Voice Calls on Android 12+:
`MODE_IN_COMMUNICATION`, built-in communication speaker, `VOICE_COMMUNICATION`
capture with platform AEC/NS requested, and `USAGE_VOICE_COMMUNICATION` for both
Piper answers and cached filler. Cues follow the owned route too. Call volume now
controls playback; no volume is forced. Android 10/11 retain the legacy media route.
This iteration targets the phone speaker, not Bluetooth/headset route selection.

The route belongs to the hardware capture session and survives command/reply/
follow-up handoffs. Passive wake stays on the recognition route; wake-to-call changes
recorder once before the command-ready cue. Pause/end/error/external microphone
handoff releases the route, and resumed calls reacquire it. Failed route acquisition
fails the turn rather than silently claiming echo cancellation on the media path.
Single-word barge, stop and Hey Jarvis gates remain unchanged: no acoustic-only stop.

Evidence reviewed: build-716 D2 communication-speaker user-only PCM rms 2417,
double-talk rms 2300, Jarvis-only PCM all zero with playback rendered. Build-717
replays confirmed owner words survive, including double-talk. All-zero PCM alone
is not proof of effective AEC; D2 full-call acceptance is still pending below.
No need to repeat completed 717 replay tests. D3 WebRTC APM remains conditional on
these real-call results; no software echo canceller was added in this iteration.

### New phone acceptance checks — one ZIP per call

Use the phone speaker, no headphones. Adjust **call volume while the call is active**.
Run separate calls and stop the session before saving each result in Voice Call →
Development diagnostics → **Save latest call test ZIP**. Save before starting the
next call. ZIP contains retained logs, exact prompts, route and interruption evidence;
it does not contain microphone PCM or measure acoustic echo attenuation.

1. Ask for a long story; remain silent through the filler and entire answer. Must not
   stop itself. In another call, cough/rustle during playback without speaking;
   non-speech must not interrupt. Save each call separately.
2. During an answer, say a single **No**. Repeat in separate calls with **Stop** and
   **Hey Jarvis**. Must stop promptly and retain the beginning of your next request.
3. After an answer finishes, ask a follow-up. Then test keyboard dictation handoff:
   Jarvis yields, dictation works, and Jarvis resumes on the communication route.
   Finally end the call and confirm ordinary phone audio works. Save the ZIP.

These gates check effectiveness AND preserved owner speech. The existing deterministic
“Start interruption test” command can isolate acoustic behavior if a model will not
produce a long answer; at least one ordinary generated answer is also required to
exercise inference load. Do not alter capability prompts or the model selector.

Validation: route lifetime tests cover turn continuity, wake-to-call replacement,
recorder startup failure and cancellation; Android CI runs release tests and signed
APK assembly. Device acoustic effectiveness and external-call priority require phone
validation; an enabled AEC flag is not a pass.


## Live-call recognition and response comparison (2026-09-18)

Build 717's three Android replay ZIPs confirmed the D2 raw-input fix and the host
replay results. Those completed tests do not need repeating. The new **Live
voice-call comparison** card measures the production call pipeline rather than
using the saved-clip replay elapsed time as a latency benchmark.

Choose one path in the diagnostic, arm three turns, then start a fresh Voice Call.
Read the reference question once per listening turn and allow the answer to finish.
End the call after the third answer and save **one ZIP per turn**. Repeat for the
other paths with the same selected Gemma model, question, phone position and volume.
This does not change the saved ASR selection or the model selector. Model downloads
must be ready; setup/reuse is reported separately and the first trial is not assumed
cold. Results remain in memory (latest 12) until exported or the process ends.

| Diagnostic path | Request recognition | Answer and playback |
| --- | --- | --- |
| Moonshine | Normal live Moonshine partials and finalization | Incremental Gemma text, Piper |
| Whisper | Normal live Whisper partials and finalization | Incremental Gemma text, Piper |
| Gemma transcription | Capture VAD, then Gemma audio transcription | Gemma text answer, Piper |
| Gemma direct audio | Capture VAD, no separate transcription | Gemma audio answer, Piper |

These are real microphone turns using normal model ownership, recognition
scheduling, capture endpointing, Piper preload/filler/output and reply interruption
monitoring. Gemma-only **request recognition** still uses the normal Moonshine
interruption listener during the reply. The separate Gemma transcription stage
precedes reply monitoring, as in the normal fallback path. No artificial CPU stress
is added. Fresh prompt context and disabled lookups/device actions control the
comparison; the reference is used only for scoring, never supplied to inference.
At introduction (build 719), this comparison retained the legacy call route.
The subsequent D2 integration above now applies the communication speaker route
to ordinary calls and these comparison turns alike.

Each ZIP contains `input.wav` when captured and `report.txt`: final/partial
recognition, declared reference and word-error percentage, model/build identifiers,
model setup/reuse, thermal state, capture/TTS metrics, incremental-prefill and
barge-listener evidence, final status and answer. Bounded log entries are marked
when truncated. Separate live readings are **not identical waveforms**; listen to
the recording before treating the declared reference as ground truth. Direct audio
answers have no invented transcript/WER and require answer-correctness review.

Timing uses a monotonic clock and separates:

- Gemma answer submission to first nonblank streamed token; also capture VAD
  speech end to that token, including endpointing and any transcription work.
- Gemma transcription submission to its first token, when that path is used.
  Moonshine/Whisper partial hypotheses are labelled partials, not native tokens.
- Capture VAD speech end to the first non-silent **answer** PCM frame passed by the
  AudioTrack playback head. This is the on-device proxy for first audible word,
  not an external acoustic or word-alignment measurement. Filler is excluded.

Missing timings remain null. Interrupted, failed, correction-input or inaudible
trials are marked incomplete; do not average them as successful responses.
`barge_keyword_ready_observed` and prefill/log evidence show which concurrent call
work actually ran. Thermal conditions and recognition paths can differ naturally;
compare those fields alongside speed and accuracy instead of declaring a winner
from one isolated number.

This diagnostic extends D2 measurement. Full-call P5/P6 route acceptance and the
conditional D3 software-AEC decision remain separate gates in the 16-part plan.

## D2 saved-audio recognition comparison (2026-09-18)

The three build-716 communication-speaker ZIPs were replayed locally with the
pinned Moonshine 0.1.5 SMALL_STREAMING and sherpa-onnx 1.13.7 Whisper base.en int8
models. Model artifacts were SHA256-verified. This is Linux adapter evidence,
**not phone timing or full-call acceptance**. See [raw results](measurements/d2-asr-replay.json)
and [reproduction instructions](../scripts/d2-replay/README.md).

| Input path | Owner only | Owner during Piper | Jarvis only |
| --- | --- | --- | --- |
| Old raw diagnostic, native VAD bypassed | Empty | Empty | Empty |
| Call input gates, native VAD bypassed | No, yes, I, stop, Hey Jarvis | No, yes, I, stop, Hey jogger | Empty |
| Raw diagnostic, native VAD enabled | No, yes, I, stop, Hey Jarvis | No, yes, bye, stop, Hey John | Empty |
| Whisper control | No, yes, I, stop, Hey Jarvis | No, yes, I, stop, Hey Joseph | `(buzzing)` |

Punctuation is normalized in this table; the JSON retains exact output. Changing
only partial-update cadence did not fix the blank raw diagnostic. Its integration
bug was using the call's native-VAD-bypassed configuration on a full recording,
without the call's external speech-window gates. Raw diagnostics now use a fresh
Moonshine instance with native threshold 0.5. Normal call and bounded barge-probe
configuration remains unchanged. Do not preserve stale partials as final speech:
the old raw path produced transient words on all-zero audio before clearing them.

The Jarvis-only recording contains all-zero PCM; user-only and overlap retain
speech. This is promising route evidence but does not establish acoustic fidelity
or reliable owner interruption. Names remain inaccurate during overlap. Whisper's
silence hallucination must never become an interruption.

**Next phone check:** in the existing Echo test, choose **Replay saved echo ZIP —
Moonshine and Whisper**, import each existing D2 ZIP, then **Save ZIP** for each
result. Missing models download first. No new recording or microphone permission
is needed. Four fresh, sequential paths run: old raw bypass, actual call input gates,
corrected raw diagnostic, and Whisper. Reports include path, raw transcript, decoder
input hash/length, build and original evidence. Playback, call history, ASR selection,
and live interruption decisions are untouched. Offline adapter replay does not
exercise live endpointing, scheduler pressure, or recovery.

D2 acoustic/ownership P5/P6 acceptance remains open. Do not promote its route to
normal calls or start conditional D3 software AEC solely from these ASR results.


## Phase D2 — communication route candidate (2026-09-17)

D1's build 713 phone ZIP (`jarvis-echo-1789683528887.zip`) contains all three scenarios on
Samsung SM-F956U / SDK 36, speaker output, `VOICE_RECOGNITION` / media playback in normal
mode, volume 15/15. Platform AEC reports enabled and controlled, but Jarvis-only and double-talk
Moonshine transcripts reproduce Piper. The microphone signal clips about 0.58% and 0.54% of
samples respectively. Voice-only produces an empty Moonshine transcript while offline Stop
and Hey Jarvis keyword detections succeed. Microphone and decoder WAV payloads are identical
in all three scenarios. These are PCM/log observations, not a subjective listening assessment
or proof of before/after cancellation effectiveness. The empty voice-only transcript remains
unresolved; do not attribute every recognition failure to echo.

**D1 status:** first speaker evidence received and supports testing a different duplex route.
Headphone comparison and owner speech preservation remain phone gates.

**D2 implemented candidate:** the existing Echo test now offers baseline media routing and an
Android 12+ communication phone-speaker profile. The candidate pairs communication capture,
playback usage, audio mode and explicit built-in speaker selection. A scoped route owner shares
`MicrophoneHandoff`'s lifecycle lock with `AndroidAudioInput` and the priority monitor. Own
communication mode is exempted; outside recording, silencing, calls and dictation still win.
Focus loss or selected-route loss cancels the test. Capture/playback cleanup withdraws our
mode/device/focus requests before offline recognition, including failures and cancellation.
Android resolves the next route rather than us forcing a stale previously observed device.
The existing `VoiceAudioSession` production path is unchanged until the candidate passes P5/P6.

Every completed scenario has its own ZIP export, and a combined ZIP remains available. Reports
include profile, actual routes, volume stream, AEC control details and route release evidence.
No PCM is uploaded automatically or added to normal-call logs. Controlled tests still play a
fixed eight-second reference without live barge-in, Gemma or speaker verification.

**Next phone gate (P5/P6):** run all three communication-speaker tests, saving each ZIP. Match
phone position and perceived volume to the baseline (call and media volume scales differ).
Compare assistant-only residual playback, owner-only intelligibility, double-talk and keyword
hits. Also cancel a test, background it, and check another microphone user can take over and
that a subsequent test/normal call starts without a stuck communication route. Actual incoming
calls and device disconnects require phone screening; JVM tests cannot establish these.

D2 is **not acoustically accepted** and does not change normal-call routing yet. D3 WebRTC APM
remains conditional on insufficient measured platform cancellation. No threshold changes or
acoustic-only pauses are introduced. Build 710's silent “And she's” interruption remains the
regression scenario; the route experiment must fix the underlying input rather than hide it.

**Current interruption requirement overrides the old Phase E wording:** acoustic activity,
noise, coughs, or loudness alone must never pause or stop output. A recognized owner word is
required, with playback-echo rejection; single words remain eligible. Stop and Hey Jarvis stay
supported. Any future reversible candidate pause must follow lexical confirmation, not VAD
onset. Retired Paul/Kokoro work in this historical ledger is not part of the current stack.


> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

Prepared: 12 September 2026. Branch: `audio-pr2`. Source baseline: `93a144c` (build 673 investigation). Status: **A1 accepted; A2/A3 partial; B1 is investigating true Paul LM-context appendability at Justin’s request; B2 awaits a verified quality winner. Kokoro performance work is paused. C1–C3 repairs remain partially accepted; D/E and integrated F acceptance remain open.**

Companion: [research proposal](voice-repair-proposal-2026-09-12.md). Parent roadmap: [local voice implementation plan](local-voice-implementation-plan.md). Continue in the existing Audio PR2 PR; no merge without Justin's explicit approval. This document defines future code work; publishing it does not claim that work is implemented or tested.

## 1. What Justin should do on every test build

The developer must send one signed APK and a short **build test card**. Justin follows that card, not every test in this document. Routine builds get a small screening pack; larger acceptance packs run only at the named milestones. A docs-only build is not a voice fix and requires no phone testing.

1. Install the specified signed APK over the existing installation. Do not uninstall, clear app data, or download arbitrary models. If installation fails, report it and stop.
2. Open Jarvis and verify the displayed build number matches the card. Copy diagnostics if it does not.
3. End any active call. Keep learned speaker preference, permissions, and accessibility configuration unchanged unless the card explicitly tests one of them.
4. Set up the same room, phone position, route, and volume described below. Let the phone return to its normal idle temperature for a cool test. Warm tests have a separate script.
5. Run only the pack listed on the card, in its specified order. With the planned harness, select its named pack and let it set the temporary profile. Do not touch tuning sliders between trials.
6. Mark each trial Pass, Fail, or Invalid. A wrong phrase/timing or external interruption makes a trial Invalid; a Jarvis miss, timeout, crash, or garble is Fail. Keep failures even if a later retry succeeds.
7. Save/export the result before another test can overwrite it. Then attach the report and requested source WAV here. If the new harness is not available, use the existing buttons listed in section 3 and the manual template in section 11.
8. Stop after the assigned pack. More random questions will not improve the controlled comparison. Normal use is welcome afterward, but label its evidence `exploratory` separately.

### Fixed physical setup

- Device: Samsung Galaxy Z Fold 6. Default route: built-in phone speaker and microphone; disconnect Bluetooth/headphones for this baseline. Fold the phone closed, put it on the same table stand, microphone unobstructed, and sit approximately 50 cm away.
- Use the same quiet room and normal speaking voice. Do not whisper, shout, or move the phone unless a named route/noise test requires it. Keep case and orientation the same.
- Baseline playback volume: nearest available setting to 50% on the active playback stream. The harness must record stream type, current step, and maximum step; reuse that exact step. Before the harness exists, take a volume-panel screenshot and note the approximate setting.
- Keep Battery Saver off. Start unplugged with at least 40% charge. Record battery and thermal status; pause an invalid cool-baseline run if it starts already thermally throttled. Never hide a warm-test slowdown by cooling the phone mid-pack.
- Download required models beforehand, then disable Wi-Fi/mobile data for the controlled offline pack. Do not change networking during a trial. Connected messaging/microphone-handoff tests are a separately labeled exception.
- Silence notifications for the pack if convenient, but leave the same device configuration across comparisons. Do not run other benchmarks, videos, or calls. Do not reset learned voice preference to rescue a failed test.
- Restore the user's previous settings after the pack. The harness must snapshot and restore the saved voice/profile; interrupted harness runs must recover the snapshot on next launch.

### Profile contracts

| Profile | Fixed values | Purpose |
| --- | --- | --- |
| `B673-reference-v1` | Paul; Moonshine; native streaming; 4 threads; speed 0.9; fresh decoder; leading period on; selected cushion 200 ms; seed 42, temperature 0.7, 5 generation steps | Reproduce the reported saved profile. Actual effective startup buffering must also be logged; a UI cushion is not the full buffer policy. |
| `Paul-source-control-v1` | Existing 14-run source-audio suite: 2 threads, 1.0 speed, 200 ms benchmark cushion, fixed suite variations | Isolated voice quality; leaves saved call settings intact. |
| `candidate-<commit>-v1` | Explicit manifest on each card, including every difference from the reference | Exactly one experimental change per A/B pair, unless the card identifies an inseparable implementation change. |

The reference profile is a reproduction setting, not a recommendation to keep it forever. `Restore call defaults` is **not** a substitute: defaults can change between builds. Voice quality comparisons use 1.0x playback for both A and B unless speed itself is the variable. Do not infer a synthesis speedup from slowing playback.

## 2. Delivery rules and ordering

Each numbered item below is one bounded, compilable commit, pushed separately after its required checks. Do not mix model changes, routing, and interruption thresholds in the same commit. Mark experimental paths disabled by default until their comparison passes. Keep a known accepted profile addressable. A revert is a forward commit on audio-pr2; never rewrite the branch or require installing an older APK over a newer version code.

Every code push must pass affected deterministic tests and the repository's Android debug/release, native packaging, callback ABI, and signing gates before it is presented as an installable test build. Lightweight docs/link validation suffices for this documentation publication. The existing PR workflow may still run for a docs-only push; its APK is not a behavioral milestone.

The developer, not Justin, owns build logs, race tests, callback ordering checks, branch state, source analysis, and packaging. Justin owns the scripted physical speech and listening judgments. No ADB or desktop is required on Justin's side.

Dependency order: A1 → A2 → A3; then B1/B2/B3, C1/C2/C3, and D1/D2/D3 use A's evidence. D1's measurements should be collected early, even while B/C are investigated. D3 is conditional. E1 requires usable D acoustic results; E2 requires E1 and C recognition readiness; F1 integrates all accepted paths. F2 closes acceptance and synchronizes documentation.

## 3. What exists now versus what must be added

Verified against the source baseline:

| Available now | Exact label or phrase | Use |
| --- | --- | --- |
| Voice settings dialog | `Voice settings` (settings icon), `Copy diagnostics`, `Save latest reply audio` | Current runtime evidence. The latest reply WAV may be overwritten by later speech. |
| Voice comparison dialog | `Voice and response speed` | Open the existing voice/speed dialog. The build card must include a screenshot if navigation changes. |
| Paul source suite | `Compare Paul source audio · 14 runs` | Already fixed and repeats cases; do not run the broad all-profiles suite. |
| Per-run audio export | `Export this run’s audio + diagnostics` | Save the particular comparison run rather than an unrelated latest-call WAV. |
| Normal call test | Say `Start the interruption test` | Fixed Mira/river passage through the ordinary playback/interruption path. It is not a guaranteed two-minute story. |
| Wake test | `Test wake word` | Passive keyword diagnostic; does not prove keywords work over Paul. |
| Recognition comparison | `Compare both on one 8-second recording`, `Copy recognition results` | Optional diagnostic when specifically assigned; do not change the selected ASR. |

**Not available yet:** named test packs, locked manifests, countdown/visual speech cues, automatic run labels, per-trial grading, complete timed story capture, and one bundled test report. A1–A3 must implement these. Do not tell Justin to press a planned button before a build actually contains it.

Until A1–A3 ship, use only the small current-build baseline in section 6. The new pack entry should sit inside the existing diagnostics facilities, not expand the normal voice conversation UI into a tuning console.

## 4. Commit-by-commit implementation plan

All paths below refer to `app/src/main/java/com/battlesbudz/jarvis/v2/voice/` unless qualified. Each gate is evidence required before enabling/promoting the change; a diagnostic commit may be useful even when it records a failing baseline.

### Phase A — fixed inputs, reproducible evidence

**A1 — `feat(voice-tests): add versioned test packs and reversible profile snapshots`**

- Add a small test-session manifest/store and entry point in the existing diagnostics UI. Register the fixed scripts and profiles in this document, assign pack/case/repetition IDs, and record build SHA, model/reference hashes, profile, selected ASR, route, volume, battery, and thermal state.
- Snapshot saved settings before a test, apply a temporary manifest, disable conflicting controls while running, restore on completion/cancel/relaunch. Do not change production inference behavior.
- Developer checks: cancellation/relaunch restoration, stale-run rejection, missing-model failure, correct active-call exclusion, and one case owning audio at a time.
- Justin: `P1-setup`, roughly 3–5 minutes. Start and cancel one pack; verify normal settings return. Gate: a report identifies the exact build and settings, and cancel leaves normal calls usable.
- Rollback: hide pack entry; leave current production pipeline unchanged.

**A2 — `feat(voice-tests): add fixed source and concurrent-load comparisons`**

- Reuse `TtsBenchmarkController`, `TtsBenchmarkSamples`, `VoiceInterruptionTest`, and `BenchmarkProvenance`; extend rather than duplicate. Implement the ordered load cases in P2 and fixed long narration in section 10.
- Add in-app microphone trial capture for deterministic replay in memory and visual prompts for user speech. Keep production call recording policy unchanged. Fixed text must use ordinary TTS/output paths; only bypass Gemma for isolation cases. Label live-Gemma trials separately.
- Developer checks: same text across profiles, declared intentional fixture repeats only, no competing native model owners, cancellation and model release after every condition. Validate audio callbacks are delivered exactly once.
- Justin: P2 screening. Gate: cases run without manual tuning and no result is silently replaced by the next case.
- Rollback: disable load cases while preserving A1.

**A3 — `feat(voice-tests): preserve timed run evidence and export one report`**

- Extend `SpeechAudioTrace`, `PocketStreamDiagnostics`, and interruption timing. Separate native work, load/acquire, finalization, release, callback intervals, queued audio and actual playback-head progress. Include requested/effective startup policies.
- Bundle manifest, trial outcomes, diagnostics and selected source WAVs with stable IDs. Add bounded diagnostic source-audio capture long enough for the assigned story, separate from the normal latest-30-second ring. No automatic disk recording of microphone audio; explicit user export only if a later diagnostic needs it.
- Include `not_measured` for physical onset/stop latency without calibrated timestamps. A visual cue is a prompt, not the time the user actually started speaking. AudioTrack-head estimates are not laboratory acoustic measurements.
- Developer checks: run association across later calls, sample offsets, export after cancellation, source/played duration separation, size limits, no raw mic in default exports.
- Justin: P1 export check plus P2. Gate: another session can reproduce the test from one report. **Milestone A: baseline captured; no fix claimed.**

### Phase B — Paul's generated voice

**B1 — `test(paul): add matching-runtime preprocessing and state controls`**

- In `PocketSpeechPolicy`, `PocketTextStream`, benchmark profiles, and `native/sherpa/pocket-streaming.patch`, expose diagnostic-only comparisons for added leading punctuation, token-aware sentence grouping, and decoder/RNG state. Reuse existing 14-run suite controls where valid.
- Identify the source revision/config matching the January ONNX export before porting modern upstream behavior. Fix voice-reference conversion or conditioning only if parity checks identify a discrepancy, in a separate follow-up if required. Do not globally retain completed text/EOS state.
- Compare full-text input with grouped input while recording any internal splitting. Keep reference, sample text and other variables constant. At most three candidate comparisons per pack; select from evidence, not a full grid.
- Developer checks: legal token bounds, punctuation/numbers/quotes, no repeated submissions, no truncation, same-run collected PCM equals concatenated callbacks. Upstream-vs-ONNX quality is not byte-equivalence.
- Justin: P3, A/B labels blinded and order alternated. Gate: the defect is narrowed to a reproducible variable or explicitly remains unexplained.
- Rollback: all candidates remain diagnostic only.

**B2 — `fix(paul): apply the selected voice-consistency correction`**

- Implement only the winning B1 change and update profile version. If B1 finds no winner, do not invent this commit; issue a measured decision report and remain on the reference.
- Preserve first-PCM latency, native callback order, final words and voice-reference identity. Do not combine a model upgrade or new voice with this correction.
- Developer checks: affected native/text tests and short/long fixtures, repeated turns and fillers isolated from answer state.
- Justin: P3 confirmation on all three passages. Gate: accepted voice stability and no skipped/repeated words. Residual model-level drift is reported, not marked fixed.
- Rollback: restore prior policy through a forward commit.

**B3 — `docs(paul): record accepted profile and unresolved quality limits`**

- Record exact candidate, listening scores, runtime/model versions, and source WAV run IDs. Retire diagnostic variants only after their useful evidence is retained; keep one baseline comparator.
- Developer: verify the documented profile equals the effective code manifest. Justin: no extra testing if B2 evidence is complete. **Milestone B: quality accepted, or explicit backend/reference decision required.**

### Phase C — sustainable playback and recognition

**C1 — `fix(voice): separate recognition readiness from decode and freshness budgets`**

- Update `BoundedInterruptionRecognizer`, `InterruptionTiming`, and `VoiceModelSession` to measure acquisition separately while preserving a bounded overall lifetime, audio age checks, native cancellation joins and exclusive access.
- Schedule bounded model renewal away from active candidates when safe; retain the existing SDK stream ceiling. Consume a completed fresh result before rejecting it because of later queue pressure. Do not widen deadlines until observed work justifies a value.
- Developer checks: slow acquisition then successful fresh decode, truly stale input rejection, failed acquisition, model rotation, late result/cancel race, no close-during-native-call. Verify no missed keyword service during renewal.
- Justin: P4 screening, including early and late interruption points. Gate: diagnostics distinguish loading from decode and no longer show repeated unexplained recognition loss.
- Rollback: old scheduling profile; maintain timing instrumentation.

**C2 — `perf(voice): reserve bounded compute for interruption work`**

- Update `DuplexPlaybackBudget`, `NaturalBargeInAudioInput`, and relevant scheduler hooks. Reserve work for admitted candidates and explicit stop verification; overload recovery is a named state rather than indefinite retries. Keep keywords/capture serviced.
- Benchmark 2/3/4 TTS threads under actual candidate load, adding a 3-thread diagnostic profile only here if necessary. Never treat thread count alone as guaranteed priority. Keep optional Gemma speculation behind committed audio/input work.
- Evaluate a candidate-lived recognition stream only if repeated setup is measured as dominant. Do not reintroduce unlimited concurrent ASR. Preserve Moonshine's external speech gate.
- Developer checks: renewable quota, short/long saturation, genuine stop not blocked by natural budget, bounded memory and no native overlap.
- Justin: P2 comparisons and P4 screening. Gate: lower combined-load starvation with recognition still completing, not just faster isolated TTS.
- Rollback: prior admitted-work policy and thread profile.

**C3 — `perf(paul): apply measured audio-production and buffering changes`**

- Use `SherpaKokoroVoiceOutput`, `PaulPlaybackBuffer`, and native measurements to select a bounded startup cushion. Keep PCM writer work short; move avoidable diagnostic I/O away from time-critical delivery where measured.
- If profiling justifies it, make bounded latent-producer/decoder concurrency its own separately reviewed `C3a` commit, then promote in `C3b`. Include queue limits, ordering and cooperative cancellation. If it offers no benefit, omit it and document why.
- Validate rate 1.0 and 0.9 separately, preserving stable pitch/rate per answer. Do not silently slow below 0.9 or replace Paul. If production remains slower than playback, document the resource/backend decision rather than increasing buffers indefinitely.
- Developer checks: callback integrity, no lost final frames, correct playback speed accounting, startup/short answer, starvation recovery and confirmed cancellation under full buffers.
- Justin: P2 extended plus P3 regression for any native synthesis change. Gate: three ≥120-second story runs without observed mid-answer starvation or audible stutter; report generation/load headroom. **Milestone C: throughput accepted.**

### Phase D — acoustic input during output

**D1 — `feat(voice-tests): add route-specific echo and keyword evidence`**

- Instrument `AndroidAudioInput`, `MicroInterruptionKeywords`, and `ReplyVoiceCapture` for actual AEC control/state, route, sample rate, capture timing, keyword score curves and assistant-only versus overlapping input. V2 already requests AEC; this is effectiveness testing.
- Add P5 speaker/headphone comparisons. Where hardware-preprocessed raw audio is unavailable, label that limitation rather than claim before/after AEC access.
- Developer checks: input format/quantization against model contract, capture replay order, effect lifecycle and disconnect. Do not lower thresholds to compensate for an unknown signal problem.
- Justin: P5. Gate: classify acoustic failure versus keyword preprocessing/model failure versus recognition scheduling.
- Rollback: disable diagnostic taps; no production route change.

**D2 — `fix(audio): select the verified duplex route with explicit ownership`**

- Only if D1 supports it, test a communication route and route-specific effects. Update `AndroidAudioInput`, `MicrophoneInterruptionMonitor` and `VoiceAudioSession` so Jarvis's own communication mode does not trigger self-suspension.
- Restore prior audio mode/route on end/failure and still yield to actual outside recording/calls. Avoid treating every keyboard/input screen as microphone use. Preserve quiet speech and screen-off passive operation.
- Developer checks: own/external ownership, effect unavailable, Bluetooth changes, route restoration, permission loss, external dictation and active-call recovery.
- Justin: P5 plus P6 handoff screening. Gate: overlap recognition improves with no own-mode deadlock or handoff regression.
- Rollback: previous route policy, not a blanket disabling of external microphone protection.

**D3 — conditional `feat(audio): evaluate local software echo cancellation`**

- Only if measured platform AEC is insufficient. Pin a suitable WebRTC APM revision/dependencies, verify packaging obligations, and implement capture plus rendered-reference processing with bounded delay alignment and resource use.
- Begin at controlled 1.0x; 0.9x requires an accurate rendered reference or owned time-stretch output. Do not feed original un-stretched PCM as though it were the played signal. Compare hardware versus software processing independently.
- Developer checks: aligned artificial echo tests, real double-talk preservation, resampling/frame sizes, route/delay changes, teardown, APK size and CPU/memory cost.
- Justin: P5 and P2 extended. Gate: better overlapping command recognition without destroying Justin's speech or causing playback starvation. Otherwise remove/disable candidate and document the limitation. **Milestone D: acoustic route accepted, conditional work resolved.**

### Phase E — recoverable barge-in

**E1 — `feat(voice): add reversible interruption candidate playback state`**

- Extend the existing coordinator/output model; introduce bounded candidate pause/duck distinct from permanent stop. Preserve first user words and retain bounded unplayed PCM/playback position. Brief false triggers resume retained audio without restarting synthesis.
- Candidate onset must use the verified acoustic path; minimum duration/energy alone is not proof of external speech. Keep this experimental until noise tests pass.
- Developer checks: pause/resume position, queue backpressure, no double-played callbacks, resume/cancel races, ongoing user speech prevents inappropriate resume, bounded timeout and exact reason codes.
- Justin: P4 natural screening and P5 noise screening. Gate: a real correction receives an audible response and false candidates recover without repeated words.
- Rollback: previous confirmed-only interruption behavior.

**E2 — `fix(voice): prioritize confirmed commands and flush stale output`**

- Keep confident Hey Jarvis independent of the natural recognizer. On a possible stop, pause provisionally and use its reserved non-echo verification path. Confirmed stop means active listening; goodbye means end call and passive wake.
- Flush output promptly before waiting for JNI cleanup; cancel workers cooperatively, reject late frames by answer/session generation, and preserve delivered-history accuracy. Finalized recognition remains required before any new tool action.
- Developer checks: quoted/negated/assistant-spoken stop/goodbye, genuine human overlap, cleanup lag, stale callbacks, active microphone ownership, no new answer or filler after goodbye, stopped content absent from delivered history.
- Justin: P4 full command acceptance plus P6. Gate: command semantics, response latency and no false cancellation pass. **Milestone E: interruption behavior accepted.**

### Phase F — integrated acceptance and closure

**F1 — `test(voice): lock integrated acceptance pack and regression fixtures`**

- Assemble P7 from accepted profiles; keep isolated fixtures separate from live-Gemma tests. Freeze model/runtime versions, route, and samples. Include warm, background, screen-off, first-word, long-turn and microphone-handoff cases.
- Developer: full CI, native/package/signing verification, regression tests for identified failures and installable APK metadata. Do not change thresholds in this test-only commit.
- Justin: P7 in two manageable sessions. Gate: all mandatory rows in section 9 pass; report failures without averaging them away.

**F2 — `docs(voice): publish measured acceptance and remaining issues`**

- Update this ledger and parent roadmap with commits, build numbers, reports, actual results, known limitations and accepted profile. Remove obsolete normal-flow settings only as separately reviewed code work if necessary; do not hide failing diagnostic evidence.
- Justin: no additional pack if F1 evidence is complete. State accepted/failing/not tested for each route and warm condition. Device acceptance is distinct from CI success. Merge remains a separate explicit approval.

## 5. Build test card — required developer handoff

Send this with every requested install:

```text
Build: <number>   APK: <permanent signed link>
Commit: <SHA>   Milestone/commit ID: <C1, for example>
Changed: <one sentence>
Run only: <P4 screening; trials H1, S1, N1>
Expected time: <rough duration>
Profile: <named manifest; list differences>
Phone setup: <speaker/cool/50 cm/exact volume step>
New controls available: <exact labels; screenshot if necessary>
Expected improvement: <observable result>
Known unresolved: <specific failures this build does not fix>
Send back: <report and exact WAV run IDs>
Stop condition: <crash, stuck mic, repeated unwanted cancellation, etc.>
```

Code pushes can happen separately, but do not ask Justin to install every intermediate commit. A milestone card names the precise cumulative SHA tested. If CI creates several APKs, send one selected build and retire ambiguous links. Do not identify a docs-only APK as an improvement.

## 6. P0 — current-build baseline (available before harness)

Use once if fresh baseline evidence is needed; existing build 673 evidence may satisfy it. Approximate effort: 5–10 minutes plus exports. Do not repeatedly reproduce known failures without a diagnostic purpose.

1. Keep or deliberately set `B673-reference-v1` in `Voice and response speed`. Check the label under `Voice calls`, not merely selected benchmark chips. Use `Apply to voice calls` only for this explicit reproduction; record the previous profile first. Do not tap `Restore call defaults`.
2. Start a call with Hey Jarvis. Say exactly: **Start the interruption test.** Let the Mira story finish without speaking. Immediately open Voice settings, `Copy diagnostics`, then `Save latest reply audio`, before starting another reply. Label `P0-story-1`.
3. Start the same test again. After hearing **The lantern glowed beside the river**, say **Hey Jarvis** once. Wait for listening, then say **What is two plus two?** Expected: story stops and answer is four. Record whether the first words were captured.
4. Repeat the story; at the same sentence say **Stop** once. Expected: silence and active listening, not ended call. After two seconds say **What is two plus two?**
5. Repeat the story; at the same sentence say **Actually, tell me what two plus two is.** Expected: story stops and answer is four.
6. End with **Goodbye Jarvis**. Expected: return to passive listening. If it fails, use the UI stop/end control and record a failure. Copy evidence after each trial; later results may displace earlier events.
7. If assigned, end all calls and run **Compare Paul source audio · 14 runs** once. Leave its fixed settings untouched. Export the specified cases using **Export this run’s audio + diagnostics**. Do not use **Compare all profiles · all voices**.

Manual P0 timing is approximate. Do not claim a 500 ms pass based on subjective reaction time. The structured harness introduced in A replaces this manual pack.

## 7. Structured test packs (require named harness support)

### P1 — setup and export, 3–5 minutes

1. Snapshot/screenshot your current voice and call profile.
2. Select the assigned named pack and press its displayed start control. Confirm the manifest matches the card.
3. Cancel during the first passage. Verify the test stops and the previous call profile is restored.
4. Restart, complete one trial, mark its outcome and export its report.
5. Make one short normal call, then reopen the report. Verify the report still belongs to the completed trial and has not become the latest call's data.
6. Pass only if cancellation, restoration, normal use, and durable run association all work.

### P2 — locate playback pressure

Screening: roughly 8–12 minutes; extended pack requires at least three uninterrupted two-minute passages and can be split across sessions.

1. Apply the card's manifest through the pack. Sit still and do not speak except at visual cues. The first run is labeled cold; later runs are warm, never silently averaged together.
2. Run condition A: the same previously generated source PCM plays with live synthesis off and microphone processing off. This checks the playback path.
3. Run B: Paul generates and plays the same fixed text with recognition off.
4. Run C: Paul plus the live keyword/VAD path. Remain silent first, then read the prompted speech sample when requested.
5. Run D: Paul plus bounded Moonshine candidate recognition using the fixed microphone sample/cues. Use **Actually, tell me what two plus two is.** The isolation runner must label whether confirmation is suppressed solely for measuring concurrent load; this is not a production interruption pass.
6. Run E: the normal call pipeline's fixed interruption story. This validates actual stop behavior. A separate live-Gemma question checks normal dispatch; it is not used for identical-waveform comparison.
7. For each case mark audible gaps, clicks/crackle, garbling, repetition and accent changes separately. Play that case's source WAV afterward and give separate source-vs-live ratings.
8. In A/B comparisons the harness alternates order. Do not manually change threads, speed or buffer. Only the card's parameter differs.
9. Extended pass: three complete story trials of at least 120 seconds of playback each, at the proposed production profile, without observed starvation or audible stutter. Let them complete. Keep interruption acceptance in a separate run so intentional stops are not counted as underruns.

### P3 — voice consistency listening

Screening: one A/B pair for a passage, about 3–5 minutes. Confirmation: three passages with two runs per candidate, split if tiring.

1. Use headphones only if the card says `source-listening`; hold that listening route constant across A and B. This does not qualify the phone speaker's duplex performance.
2. Listen to labeled Clip A then Clip B at 1.0x. Both contain identical text/reference and differ by one listed experimental variable. The developer reveals candidate labels only after your ratings.
3. Grade each clip: accent/identity drift 0 none, 1 slight, 2 obvious, 3 distracting; garbling 0–3; repeated/missing words count; naturalness 1 poor to 5 good. Note approximate audio timestamp and words around any issue.
4. Repeat with order reversed. Judge narration separately from quoted dialogue; deliberate expression is not automatically an accent change.
5. Confirmation covers the short opening, paragraph and story fixtures. Pass: no skipped/repeated words, no drift above 1, no garbling above 1, naturalness at least 4, and Justin explicitly accepts the voice. These are proposed acceptance criteria, not claims about current Paul quality.
6. If neither candidate improves the issue, report `neither`. Do not adjust sliders to find a third setting mid-pack.

### P4 — interruption commands and corrections

Screening: H1, S1, N1 and G1 once each, about 5 minutes. Full acceptance: 20 trials per command class, completed in blocks of five; schedule across multiple sessions. A failure stays in its original block. Reruns are new IDs.

The harness plays a fixed story and gives a **visual-only** cue at early/middle/late positions (2, 15 and 45 seconds after actual playback starts, where the passage is long enough). A sound cue could contaminate the microphone. Justin speaks within about one second of the cue. Before the harness exists, use P0's sentence cue. Latency is measured from actual detected speech/keyword timing, not the visual cue.

| Class | Say once at cue | Expected behavior | Follow-up |
| --- | --- | --- | --- |
| H | Hey Jarvis | Stop story, remain listening | When ready, say `What is two plus two?`; expected four. |
| S | Stop | Stop story, keep active call | Wait two seconds, say `What is two plus two?`; expected four. |
| N | Actually, tell me what two plus two is. | Yield, retain whole correction, answer four | Do not repeat if it misses; mark Fail. |
| G | Goodbye / Goodbye Jarvis (alternate each trial) | End active call, return to passive wake | Wait three seconds, say `Hey Jarvis`; new call must activate. |

For each class's 20 trials: 7 early, 6 middle, 7 late. Use ordinary speech at the fixed baseline volume/distance. Record success, first words retained, false resume, unexpected call ending, and answer correctness. Do not say the trigger repeatedly until it works. If nothing happens within three seconds after your phrase ends, record the miss; use UI end after five seconds if needed, rather than listening to the whole remaining story. Distinguish functional success from the stricter latency target.

An actual tool action is not needed to prove barge-in. Arithmetic has a deterministic expected answer and avoids changing your phone or accounts. Separate tool regression checks remain developer-owned unless specifically assigned.

### P5 — acoustic conditions and false triggers

Screening: about 5–8 minutes; complete noise acceptance is a separate ten-minute run.

1. Built-in speaker at the baseline volume: say `Hey Jarvis` five times in the idle wake test, then run five overlapping story trials using the assigned H/S/N class. Do not compare idle recognition with a different microphone position.
2. If requested, repeat at a fixed 75% speaker setting, restoring baseline afterward. Label separately; do not pool with 50% results.
3. If you have headphones, repeat the identical five trials with them. Record whether microphone input is phone, wired, or Bluetooth. If none are available, mark that route Not tested; it does not block evaluating the default phone route.
4. Assistant-only test: remain silent while the fixed passage plays. It deliberately includes the words Sir, stop, and goodbye in quotation/reporting contexts. Jarvis must not cancel itself.
5. At visual prompts during a separate noise test, make one quiet throat-clear, rustle a sleeve, then say `mm-hmm`. Space these by at least ten seconds. Do not shout, clap beside the mic, or improvise noises. Record provisional pauses/ducks separately from permanent cancellations.
6. Full noise gate: ten minutes of the assigned assistant-only/controlled-noise playback with zero confirmed false cancellation; target at most one provisional duck/pause. Any false pause must resume retained speech without duplicated words.
7. Export diagnostics. Source WAV alone cannot tell us what the microphone heard. No microphone audio is exported unless the card explicitly requests a user-initiated diagnostic export.

### P6 — microphone handoff and background behavior

Screening: 5–10 minutes. Do each once; repeat three times only at final acceptance.

1. Passive state: leave Jarvis armed, open a private unsent draft in an app with keyboard dictation. Dictate `This is a microphone handoff test.` Stop dictation. Do not reopen Jarvis. After five seconds say `Hey Jarvis`. Expected: passive wake recovered. Do not send the draft.
2. Active state: start a Jarvis call, then use the same dictation flow. Stop external recording. Expected: return to the prior active call state under the existing resume contract, without duplicate actions or replaying an entire answer. Speak `What is two plus two?` after the listening cue/state.
3. Ordinary keyboard: open an input field and type without activating dictation. Expected: typing alone does not suspend Jarvis.
4. Background: start the fixed story, go Home, then give the assigned interrupt command. Expected: same behavior as foreground.
5. Screen off: end the call with goodbye, lock the phone, wait ten seconds, then say `Hey Jarvis`. Ask the arithmetic question, then `Goodbye Jarvis`. Expected: wake, answer, and return to passive without opening the app.
6. Confirm notification/call state agrees with actual behavior. Report how long recovery took; the five-second check is an initial acceptance target, not proof an app necessarily released its mic at the instant dictation UI disappeared.
7. For eyeVue/Bluetooth, use a separate card and report; phone-route success must not be described as glasses acceptance.

### P7 — final integrated acceptance

Run only after B/C/D/E gates have an accepted candidate. Two sessions are recommended.

- Session 1, cool phone: P1 short check; P3 accepted-profile spot check; P2 extended; P4 full blocks spread over breaks. Use the complete 20-trial counts for final rates, not just screening passes.
- Session 2, sustained use: use the fixed dialogue/story loop for ten minutes without changing settings, then immediately run P4 screening and one ≥120-second uninterrupted P2 story. Record thermal state and battery. No deliberate cooling between warmup and test.
- Run P5 full ten-minute false-trigger pack and P6 lifecycle tests. Separate these from the warm timing pack if the overall session becomes tiring.
- Long-turn script: read the supplied script below with indicated pauses; expect no reply during hesitation and no first-word loss. Run one ≥30-second and one ≥60-second case with explicit visual pacing from the harness, not rushed reading. Ask `What was my final instruction?` afterward; expect the final instruction retained. Mark any truncated/ambiguous recognition as a failure, not a successful fallback answer.
- Live-Gemma checks: `What is two plus two?`, `Explain why leaves look green in three sentences.`, and `Tell me a short story about a lantern beside a river.` These check ordinary dispatch and responsiveness, not identical generated content.
- Stop when the assigned sheet is complete. Do not add broad optional regression tests unless a result identifies a concrete risk.

## 8. Triage after a failed trial

| Observation | Evidence to send | Next developer action |
| --- | --- | --- |
| Accent shifts in source WAV | P3 clip ID, timestamp, words, rating | B preprocessing/reference/runtime comparison; do not tweak playback buffer. |
| Source sounds clean; live has gaps | P2 condition, source export, queue/starvation report | C load/callback/playback analysis. |
| Keyword works idle but not over speaker | P5 route/volume, score curves, effect state | D acoustic analysis before threshold changes. |
| Recognition repeatedly loads/expires | P4 ID plus acquire/decode/freshness timing | C1/C2 scheduling review. |
| Noise causes a stop or repeated words on resume | P5 event/time and playback ledger | E1 candidate/resume review; preserve the failed fixture. |
| Goodbye stops speech but leaves active call | G trial and final state | E2 command-routing review. |
| Dictation ends but Jarvis never recovers | P6 state/notification and diagnostics | D2 ownership/rearm review. |
| Crash or microphone stuck | Last trial/build, screenshot if available, diagnostics after reopening | Stop pack, repair lifecycle first. Mark later cases Not run. |

Do not change models, thresholds, decoder settings, or speaker preference after a failure unless the next card explicitly assigns a controlled comparison.

## 9. Final acceptance gates

| Area | Required evidence |
| --- | --- |
| Voice quality | P3 three-passage acceptance; no skipped/repeated words; accepted identity/naturalness. |
| Playback | Three ≥120-second uninterrupted runs without measured mid-answer starvation or audible stutter; separately one warm run. Count actual starvation, not every end-of-drain underrun counter increment. |
| Keywords | At least 19/20 each for H, S and G at the baseline speaker route. Report classes separately. |
| Natural corrections | At least 18/20 N trials with first words retained and correct response. |
| Response latency | Proposed p95 provisional yield ≤500 ms from speech onset; explicit stop ≤500 ms from keyword completion. Report method/uncertainty. Without calibrated event timing mark Not measured; subjective success alone cannot pass a numeric gate. |
| False interruption | Ten-minute P5 with zero confirmed false cancellations and at most one provisional pause/duck; resume must not repeat speech. |
| Recognition/response | Report actual last-word-to-first-answer, recognition-final-to-audio, and text-to-PCM separately; fillers excluded. Investigate >10% median or >250 ms p95 regression against comparable baseline, rather than silently accept it. |
| Lifecycle | P6 passes three repetitions at final acceptance; test each supported route independently. |
| Developer gates | CI debug/release tests/build, keyword-model checks, ASR packaging, Pocket callback ABI, release signature and upgrade identity. |

Use nearest-rank p95 on available timing trials and disclose sample count; 20 trials provide an initial engineering acceptance sample, not universal reliability. Warm screening must not be presented as a full warm 20-trial reliability result. One failing mandatory area keeps the overall milestone open; optional unavailable routes are explicitly Not tested.

## 10. Fixed speech fixtures

Existing fixtures remain versioned: `short-opening-v2`, `paragraph-v1`, `story-v1`, and the current Mira interruption passage in `VoiceInterruptionTest`. Do not rename/change them without a new fixture version.

### `long-narration-v1` (planned)

Use this exact text for timed output, including quotation marks. The harness may repeat the entire fixture only at a labeled cycle boundary until at least 120 seconds of playback have accumulated, then finish that cycle. Intentional fixture-cycle repetition is excluded from accidental word-repeat scoring. The manifest records cycle count and text hash.

> The lantern glowed beside the river. Mira followed a narrow path through the trees, carrying a map her grandfather had drawn. Across the water, an old clock tower stood above a village of blue rooftops. Its bell had been silent for many years.
>
> At the bridge she met a gardener with a basket of apples. He pointed toward a little house beside the tower and told her that the keeper had left a letter there. Mira thanked him and crossed the bridge slowly. Beneath her feet, the river carried fallen leaves toward the sea.
>
> Inside the house, a copper kettle rested on a cold stove. There were three cups on the table, a blue coat beside the door, and a folded letter underneath a stone. The letter described a silver key hidden in the garden. It also contained a curious sentence: the word stop had once been painted on the tower gate. Mira read the sentence quietly and continued searching.
>
> A small brown dog appeared at the window. It watched her for a moment, then ran toward an apple tree. Beneath the tree was a wooden box. The key fitted the box perfectly, but inside there was only another map. This one showed the clock tower from above, with a narrow staircase drawn around its outer wall.
>
> The keeper's final note quoted his old teacher: “Goodbye is sometimes the beginning of another journey.” Mira put the note in her pocket. She climbed the staircase with the dog waiting below. When she reached the top, the village looked peaceful in the evening light. She turned the handle beside the clock, and the bell rang once. Somewhere along the river, the gardener looked up and smiled.

### Long input script (planned pacing fixture)

The harness displays sentences with visual pacing to create 30- and 60-second versions. It must display pause countdowns but not play audible prompts into the microphone. Justin reads naturally and observes indicated silences; automatic pacing must not require unnatural drawn-out words.

> I am planning a small room with two tables and a chair. [pause 2 seconds] The first table should go beside the window. I am still thinking about the second one. [pause 3 seconds] Put that one beside the opposite wall. The chair should be close to the door. [pause 2 seconds] Actually, change the chair position so it is between the tables. I have one more detail to add. [pause 3 seconds] There should be a clear path through the middle of the room. Please do not take any actions. My final instruction is to summarize the room arrangement in one sentence.

For the 60-second version, add before the final two sentences:

> The room gets bright in the morning and darker in the afternoon. I would like to keep the window easy to reach. [pause 3 seconds] There may eventually be a bookshelf beside the chair, but that is only an idea for later. [pause 2 seconds] The important thing today is the arrangement of the two tables and the chair.

Record actual speech duration. If reading finishes short of the target, classify that trial as the shorter duration rather than claiming a 60-second test passed. Add a new versioned longer fixture if needed; do not ask Justin to improvise extra instructions.

## 11. Results to send back

The harness should generate most fields. Until available, copy this template:

```text
Build / commit:
Test card / pack:
Profile (or screenshot):
Route / volume step / phone position:
Battery / cool or warm / thermal status if shown:
Trial ID / early, middle or late:
Exact words I said:
Pass / Fail / Invalid / Not run:
What Jarvis did:
Did it retain my first words?:
Did the story stop, resume, repeat, or end the call?:
Source WAV: accent drift 0–3 / garbling 0–3 / naturalness 1–5:
Live playback: gap / crackle / repeated or missing words:
Approximate audio timestamp / nearby words:
Timing: measured report value, or subjective only:
Diagnostics attached:
Source WAV run ID attached:
External disturbance or deviation from setup:
```

## 12. Implementation ledger

Update this table after each actual delivery. `Planned` is deliberately not `implemented`.

| ID | State | Commit / APK | Developer evidence | Justin pack / result |
| --- | --- | --- | --- | --- |
| A1 | Accepted | a348eb6; build 675 | CI passed; phone cancel/complete/restart/call passed | Acceptance record below |
| A2 | Implemented; partial phone screening retained | ca3210f, recording fix 46ec232; builds 676–677 | CI passed; 17 focused recording/session tests | A–D retained; E and extended acceptance incomplete |
| A3 | Partial delivery | Endpoint timings and build 685 whole-suite export; Kokoro source/playback separation in current commit | Full manifest/WAV harness not complete | P1/P2 acceptance remains |
| B1 | Specific candidate identified | Suite 48a57deb: runs 5/10 paragraph-one-reset, 13 opening-one-reset preferred by Justin | Single text submission, audio streamed; no proof of zero drift | Formal P3/parity incomplete |
| B2 | Deferred for Kokoro priority | No Paul passage grouping promoted | User prefers Kokoro; 684 Paul path retained | P3 confirm pending |
| B3 | Planned | — | — | Reuse B2 evidence |
| C1 | First repair delivered; phone interruption failed; renewal scheduling remains | 3509b4b; build 678 | 52 focused JVM tests and Android CI passed | Failure record below |
| C2 | Partial repairs delivered; not accepted | Final-only probes and later scheduling repairs | Self-playback still triggers costly probes | Genuine interruption acceptance pending |
| C3 | Partial playback repairs; Kokoro callbacks in current commit | Builds 683–684 buffering/cues; current Kokoro delivery change | One cohesive 684 call, no observed starvation; sustained gate not met | P2 extended/P3 remain |
| D1 | Speaker evidence received; residual playback demonstrated | Build 713 ZIP | Bounded PCM/timeline/archive tests; CI | Headphone/owner preservation pending |
| D2 | Communication speaker candidate; raw-ASR diagnostic fixed; saved-ZIP replay added | D2 candidate, per-test and replay ZIPs | 43 focused host tests; Linux ASR replay; Android CI | Android replay and P5/P6 pending |
| D3 | Conditional on insufficient platform AEC | — | — | P5/P2 — |
| E1 | Planned | — | — | P4/P5 screen — |
| E2 | Planned | — | — | P4 full/P6 — |
| F1 | Planned | — | — | P7 — |
| F2 | Planned | — | — | Reuse F1 evidence |

This work refines the parent roadmap's interruption, source-quality, integrated-load and phone-acceptance phases. It does not roll back completed external Moonshine gating, long-turn handling, microphone ownership, speculative-work limits, or tool-finalization safeguards. Recheck the current implementation before each commit; preserve unrelated changes. Publish measured failures as clearly as successful results.


## A1 delivery card — setup only

A1 adds `Start P1 setup`, `Complete P1 setup`, `Cancel P1 setup`, and `Copy P1 report` at the top of **Voice and response speed**. This initial P1 does not play a passage: audio execution and fuller report exports belong to A2/A3. Do not run the future P2–P7 controls from this build.

Implementation choice: the snapshot and temporary profile live in a separate test-session preference store. Production voice/ASR/profile preferences are never rewritten. Completion, cancellation, dialog disposal, and process-restart recovery discard the temporary test profile rather than restoring stale values over production settings. The model-operation gate is held until cleanup. P1 checks installed Paul file sizes, records actual hashes, and verifies the pinned Paul reference; it does not download models or initialize native speech models. Active audio routes are correctly marked not measured because P1 opens neither playback nor capture.

Once the signed A1 APK passes CI, run only this 3–5 minute check:

1. End the call and stop passive listening. Note/screenshot your saved voice and profile. Open **Voice and response speed**.
2. Tap **Start P1 setup**, then **Cancel P1 setup**. Wait for cancellation to finish. Other tuning controls should return. Tap **Copy P1 report** and save it here as `P1-cancel`.
3. Start P1 again. Wait for **P1 ready**, tap **Complete P1 setup**, then **Copy P1 report**. Save as `P1-complete`. If Paul is missing, report that failure; no download should start automatically.
4. Start once more and wait for ready. Leave the app and close/relaunch it; for a definite process-restart test use Android App info → Force stop → Open. Return to the voice/speed dialog and copy `P1-recovery`. Depending on whether Android destroyed the activity cleanly or killed the process, terminal state may be cancelled or interrupted; it must not remain active. No ADB is needed.
5. Verify the same original voice/profile are still selected. Rearm Jarvis normally, say **What is two plus two?**, then **Goodbye Jarvis**. These are a smoke check, not proof the existing barge-in problems are fixed.
6. Send the three reports plus whether settings returned and the normal call worked. No WAV is required for A1.

Developer evidence: focused session/profile tests cover durable snapshots, completion readiness, cancellation/failure outcomes, process recovery, stale completions, failed persistence, future-pack rejection, and existing profile contracts. Android integration/build/signing must pass CI before assigning the APK. Phone acceptance remains pending until Justin returns the reports. Full source/load comparisons remain A2 work.

## A1 phone acceptance — build 675

Justin completed cancellation (`3b57c303-d804-4702-b1c6-b66453580914`), completion
(`4884d5c9-ecd8-4499-b6fd-647cb1cfcdbc`), and force-stop recovery
(`4273419d-2cfb-4a35-8ff5-6216edae9bdb`). Each report discarded the temporary
profile and retained the same saved settings. He then tapped Start Listening,
said Hey Jarvis, and received a spoken answer to “What is two plus two?”. A1
phone acceptance passed. This does not establish audio quality or interruption reliability.

## A2 delivery card — fixed audio and load baseline

Controls live in **Voice and response speed**: **Start P2 screening**, **Continue**,
**Replay this source at 0.9x**, **Cancel P2**, **Copy P2 report**, and a separate
**Start P2 long narration**. Leave Start Listening off throughout these tests.
Saved voice settings are never overwritten. The temporary profile is B673-reference-v1.

First phone test (screening only):

1. End any call and stop passive listening. Keep the phone in one position in a
   quiet room, with the same output route and comfortable volume throughout.
2. Open Voice and response speed → Start P2 screening.
3. Follow the displayed prompt. After Continue, wait for READ NOW and say
   “Actually, tell me what two plus two is.” once. Remain silent afterward.
   The recording lasts up to eight seconds and stays in memory only.
4. Review the recognized phrase. Continue if correct; otherwise cancel and retry.
5. Continue to the explicit preparation playback, which produces the reference
   audio before comparisons. Preparation is excluded from A/B ordering.
6. Follow each condition's Continue prompt. A plays prepared PCM; B generates the
   same paragraph; C adds live keyword/VAD; D replays the captured microphone
   fixture once through bounded recognition, starting five seconds after playback
   begins; E uses the ordinary reply capture/stop path with the existing Mira
   interruption fixture. Only speak at C/E's READ NOW cues. E is a fixed reply
   integration test; it bypasses Gemma and is not a full normal-call dispatch test.
7. After each condition enter separate notes for gaps, crackle, garbling,
   repetition, and accent changes. Source replay uses the same AudioTrack path
   at the locked 0.9x speed; distinguish source-listening notes from live notes.
   Continue retains the condition and advances; it never overwrites an earlier case.
8. Copy P2 report and send it here. Cancel preserves completed cases. A process
   restart marks the unfinished run interrupted; microphone/source replay memory
   is discarded. Do not change tuning settings between conditions.

A/B order alternates across suite starts after preparation. Both use identical
text; A references the preparation PCM hash, while B performs a fresh generation.
All conditions use fresh native sessions, with model-file caches already warmed
by provenance hashing and previous conditions; none is labeled cold. D primes
Moonshine before playback and uses the production bounded recognition budget.
D suppresses playback cancellation for isolation; its detector finishes after
its first confirmation. C runs keyword and VAD processing without ASR. Neither
is an interruption-acceptance pass. E records whether a real stop occurred.
A separate normal call remains necessary to check live Gemma dispatch.

Long narration is an optional separate baseline: three trials reach at least
120 seconds of playback-head-derived duration each, finishing whole
`long-narration-v1` cycles. Intentional cycles are labeled and individually
measured; if a trial needs multiple cycles, each uses a fresh output session.
Do not interpret aggregate duration across cycles as proof of uninterrupted
continuous playback. Listening quality and starvation still require review;
this control does not automatically pass Milestone C.

The source capture fails rather than truncates above four minutes per cycle.
Reports retain bounded event excerpts (150 events of at most 700 characters per
condition, with an explicit limit indicator), metrics, source/text hashes,
recognized fixture text, notes, and terminal states. Microphone PCM is never
written to disk. Durable source WAVs, 1.0x source exports, comprehensive timing,
and a bundled report remain A3. The normal latest-call audio policy is unchanged.

Developer checks: session recovery/checkpoint tests and bounded PCM integrity
tests pass locally; Android CI must pass before assigning the new APK. Phone
P2 results remain pending. A2 is a baseline harness, not a speech-quality fix.

### P2 recording correction after build 676

Phone run `13bd90c9-d5ae-4fde-8747-370c9fa3ddce` failed before any load
condition with `Check failed.` The recording path counted eight wall-clock
seconds after microphone readiness, although AndroidAudioInput already retained
about 300 ms of startup PCM. That can exceed the former 8.1-second byte guard.
The corrective commit counts exactly 256,000 PCM16 bytes (eight seconds at
16 kHz mono), preserves startup audio, bounds the final chunk, and closes the
microphone as soon as the target is reached. A separate 12-second timeout handles
stalled capture. It displays recording progress and the transcription stage.
Reports retain captured duration, microphone events, stage, and exception details
before recognition so another failure is diagnosable without recording raw mic
audio to disk. Seventeen focused JVM tests pass, including startup backlog,
partial final chunks, early end-of-stream, cancellation, and timeout cleanup.

Retest only the recording step on the corrected APK: leave Start Listening off,
open Start P2 screening, tap Continue at the recording prompt, and read the phrase
at READ NOW. Stop at the displayed recognized phrase and report whether it is
correct. Do not change the saved voice settings. Load-condition acceptance is
still pending.

## Build 677 evidence and next bounded repair

Justin's second P2 run `998b9b0d-2c8c-4b3d-bcf7-22517686e42b` was cancelled
after D. A–D remain usable screening records; cancellation does not erase them.
The microphone fixture captured eight seconds and recognized the full correction.
All completed conditions used the phone speaker (`type=2 id=3`), volume 15/15,
and reported thermal status 3. These are warm-device observations, not a cool
performance baseline. The earlier run's C listening result was invalid because
car Bluetooth connected and Justin could not hear playback.

A–D sounded broadly alike: sentence-to-sentence accents remained, with no
additional pauses or garbling under C/D. The four generated segment PCM hashes
matched across preparation, B, C and D. Each reported zero supply gap and zero
observed playback starvation. The single AudioTrack underrun counter alone does
not establish a mid-speech gap. D made only two probes (1,000 and 1,500 ms of
audio, producing zero and eight characters), then rejected the candidate at
the window limit. There were no decode-budget deferrals in D.

The subsequent normal-call test (`13e96d6c-39b4-4b0e-9158-911e37cdfc69`, turn
`9277cd08-d173-4e0b-8b94-81449f2b5a71`) failed spoken interruption. It logged
nine natural probes, decode-budget deferrals, three window rejections and no
confirmed request. The summary's 28 budget deferrals count rolling-work
deferrals; worker deadline failures also appear separately in the events.
Model rotation took roughly 772 ms before acquisition completed in one probe.
STOP_REPLY occurred, but there was no confirmed barge-in; this is not a spoken
interruption pass. The shown playback interval had zero underruns and a substantial
queued buffer. Its blocking writes do not prove that synthesis itself was slow.

Justin subsequently described the saved WAV as having a subtle click and a new
accent at sentence boundaries. Source WAVs exclude playback gaps, time stretching
and microphone audio. This evidence warrants a separate Paul source/reference
comparison; it does not establish that accent changes are unavoidable or that
this interruption repair fixes voice quality.

### C1 first delivery — recognition timing and candidate completion

Justin authorized this repair ahead of A3 and the Paul comparison after the
normal-call failure. This is a bounded first part of C1; model-renewal scheduling,
compute tuning, acoustic qualification and full interruption acceptance remain.

- Acquisition now has a separate 1,200 ms allowance, informed by the observed
  approximately 772 ms acquisition. The existing 1,600 ms decode allowance applies
  only to ASR work. Native calls remain non-preemptible; limits are checked between
  calls and the owner is joined before release or reuse.
- Release time is measured separately. Results publish only after release and
  must still be at most 3,300 ms old (load + decode + 300 ms queue allowance +
  200 ms margin). Confirmation has a bounded additional 400 ms. These wider
  freshness bounds are a candidate change requiring the phone test below.
- A candidate can make up to three probes of growing audio, normally at one,
  two and three seconds, within the unchanged four-start/12-second work quota.
  Retryable failures preserve its onset. New submissions stop at 3.4 seconds;
  an already-running result can finish and pass the existing 300 ms stability
  check before rejection. Retained audio is bounded to 7.5 seconds, while each
  submitted ASR window remains at most four seconds.
- Echo checks, request-intent checks, keyword thresholds, playback admission
  and single native ownership remain in force. Logs distinguish load, decode,
  release and total time, worker deferrals, and candidate decision reasons.

Developer evidence: 52 focused JVM tests pass, including separate load/decode/
release timing, excessive load, stale results after cleanup, recovery from budget
pressure, a complete correction after two incomplete probes, late confirmation,
late echo rejection, exact audio handoff, and keyword access during blocked ASR.
The Android debug/release, native packaging, callback ABI and signing gates must
pass before delivering the APK. Device acceptance is still pending.

**Justin's next test — one normal call, no P2 restart:**

1. Install the supplied signed APK. If ChatGPT's download stalls, open its GitHub
   release page in Chrome and download `app-release.apk` under Assets.
2. Disconnect Bluetooth, use the phone speaker in a quiet room, and keep the
   current Paul/Moonshine settings. Let the phone cool before starting.
3. Tap **Start Listening**, say **Hey Jarvis**, and wait for the listening cue.
4. Say **Start interruption test**. About five seconds into the story, say once:
   **Actually, tell me what two plus two is.** Do not precede the correction
   with Hey Jarvis; this trial checks natural interruption.
5. Note whether the story stops, roughly how long that takes, and whether Jarvis
   answers the correction. If he continues for about five seconds after you finish,
   use the on-screen stop control and report the miss. Copy the latest Voice Call
   diagnostics immediately. A WAV is only needed for a new audio-quality issue.

Stop after this trial. A pass is a useful screening result, not acceptance of
Hey Jarvis, Stop, every route, or long-call behavior. The next test is selected
from this result; do not change sliders or repeat all previous comparisons.

### Build 678 result and C2 first delivery — avoid unused probe updates

Call `93a55d75-9011-43f3-a1e5-e9d3020b5566`, turn
`51c17cef-d83d-4b0e-90e2-6f2516e132a3`, failed natural interruption. Justin
reported a possible skip when he spoke. Four underruns coincided with empty
playback queues and estimated supply gaps of 53, 507, 122 and 243 ms. The report
does not timestamp his speech onset, so it cannot establish that exact timing.
The visible probes spent 2,619, 2,753 and 3,165 ms in decode, with only 0–1 ms
loading and 0 ms release. All four attempts were deferred and no request was
confirmed. The later END_CONVERSATION event is not a barge-in pass.

The acquisition change is functioning as measured, but did not resolve the
slow inference. Competition with synthesis is a hypothesis supported by their
overlap, not an isolated hardware measurement. No temperature evidence in this
call establishes a thermal cause.

Source review found avoidable work before changing CPU thread counts:
[Moonshine Android v0.1.5 Transcriber.java](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/language-bindings/android/java/main/java/ai/moonshine/voice/Transcriber.java)
queues PCM in `addAudioToStream`, then asks whether a transcript update is due.
`stopStream` forces a final update. Jarvis previously used its ordinary 250 ms
cadence while replaying probe PCM, although `BoundedInterruptionRecognizer`
ignores the returned partial text. Native work can therefore be spent producing
unused intermediate hypotheses. The SDK's native ASR runtime already configures
one intra-op and one inter-op thread; no unsupported thread option is introduced.

This commit asks Moonshine to defer periodic SDK updates beyond the bounded
four-second probe window and flush once at finalization. PCM remains in the same
native stream and warm model lease, fed in the existing 250 ms chunks with budget
checks between calls. It does not switch to the SDK batch recovery path or add a
second model. Both natural probes and ASR verification of a stop keyword use this
policy. Ordinary command/follow-up recognition keeps its 250 ms streaming updates;
the cadence is restored on release and when a new stream borrows the model.

The existing 1,600 ms decode limit, playback admission, candidate sizes, echo
checks, keyword thresholds and TTS profile are unchanged for this comparison.
A slow final native call is still non-preemptible and its result will be rejected
if it exceeds the budget. Logs now identify `moonshine_final_only_v1` and split
decode time into `feedMs`, `finalizeMs`, `acceptCalls`, `maxAcceptMs` and
`decodeStage`. This distinguishes unused periodic work from an expensive final
pass. CPU scheduling/thread experiments remain later C2 work if this change is
insufficient; Paul accent/reference comparisons remain separate.

All 57 focused JVM checks passed. They exercise the actual pinned SDK's Java cadence without native
loading: no periodic update over four seconds of probe PCM, ordinary streaming
restored for the next stream, and rejection of oversized/late mode switches.
Worker checks verify all PCM arrives exactly once, one finalization, release
before publication, final-pass overrun diagnostics, and existing echo/cancellation
behavior. Android CI must pass before APK delivery; phone performance remains
unproven until the following trial.

**Phone card:** install the supplied signed APK, disconnect Bluetooth, keep the
same Paul/Moonshine settings, tap Start Listening → say Hey Jarvis → wait for the
cue → say “Start interruption test.” Five seconds into the story, say once
“Actually, tell me what two plus two is.” Report whether he stops and answers,
and whether playback skips. If he continues five seconds after your correction,
use the on-screen stop. Copy the latest call diagnostics immediately. Run only
this trial; do not restart P2 or change sliders.

### Build 679 result and next delivery — inspect rejected recognition

Call `27c352c1-1519-4c89-b685-83b022493586`, turn
`533716b4-1345-44fe-bbf5-4f0af3545a87`, still did not confirm an interruption.
Justin noticed possible skips. The supplied report recorded zero underruns and
its shown queue stayed near two seconds, so the reported sensation cannot be
attributed to measured starvation in this run. This does not disprove an audible
artifact or establish exactly when Justin spoke.

The final-only policy ran. Visible completed probes took 1,570 and 1,594 ms and
returned 27 and 33 characters. Feeding PCM took about 1 ms; finalization accounted
for nearly all decode time. Both results were rejected with
`no_new_request_in_mixed_transcript`. A later 2.6-second window took 1,968 ms total
and exceeded the unchanged decode budget. The summary retained six probes, two
worker deferrals, 29 rolling-budget deferrals, four rejected windows, and no
confirmed request. This is improved diagnostic evidence, not barge-in acceptance.
Build 679's Android debug/release, native packaging, callback and signing checks
passed. Its phone test failed.

Justin authorized a diagnostic-only bounded commit before further tuning. It
retains the first two and latest four completed results examined by the natural
barge-in gate. Each includes recognized text, result age, decision, a tail excerpt
of the exact reference passed to that evaluation, the number of words matched
as contiguous echo, up to four examined fragments labeled by request intent,
and the selected request if any. Matching counts cover clauses examined up to
selection, not necessarily every word in a longer transcript. Excerpts have
explicit truncation markers and original text lengths. `evaluated=false` marks
stale/unqualified results; it must not be read as a new echo evaluation.

These records appear as `barge_evidence_0` through `barge_evidence_5` in **Retained
turn evidence** when copying the latest Voice Call diagnostics. They are emitted
when the reply listener finishes, retained per turn by the existing local
recorder, and do not compete with periodic keyword levels in the rolling event
list. The omitted count identifies older results excluded by the six-result
bound. They cover results reaching the gate; work rejected by the recognition
worker still appears in its timing/deferral events. No microphone audio is saved.
The excerpts become part of the local diagnostic report Justin explicitly copies.

Sixty focused JVM tests passed, including original gate/worker behavior, actual
SDK cadence checks, echo and correction evidence, bounded first/latest retention,
escaped single-line text, and evidence on the real listener-confirmation path.
Android CI must pass before APK delivery. Thresholds, recognition deadlines,
model settings, and playback behavior remain unchanged in this diagnostic commit.

**Phone card:** install the supplied APK, disconnect Bluetooth, keep the same
settings, tap Start Listening → say Hey Jarvis → wait for the cue → say
“Start interruption test.” Five seconds into the story say once “Actually, tell
me what two plus two is.” If playback continues five seconds after the correction,
use the on-screen stop. Immediately copy the latest Voice Call diagnostics and
send them here. No P2 restart, new settings, or WAV is required. This run collects
rejected words; improvement is not expected from instrumentation alone.


### C3 — normal-command capture under recognition load (2026-09-14)

Justin reported ordinary question answering failing on build 679, call
`4b08c211-fb7b-4959-915b-e473681ef095`. This takes priority over the pending
build 680 interruption-evidence phone test. Build 680 passed Android CI and
published successfully; no phone acceptance is claimed for it.

The answered turn `1b8a8b33-f2a8-46a5-b18d-67f0fb5468dc` took 8,278 ms from
recognition finalized to first answer audio: 44 ms to dispatch, 5,935 ms to
first reply text, then 2,299 ms to audio. Last physical speech time is not
retained, so the separately reported extra listening delay cannot be measured
for this turn. Playback recorded 59 underrun increments, 3,287 ms observed
starvation, 4,722 ms estimated supply gaps, and 46,508 ms synthesis for 35,760 ms
source audio. Underrun count is not a count of distinct audible pauses.

The following command recorded preparation deferrals with thermal=4, recognition
backlog growing to 4,900 ms, capture failure, and an incomplete command discarded.
Thermal=4 was measured during that follow-up, not established for every preceding
stage. Optional preparation deferral itself does not suspend committed ASR.

**Authorized bounded change:** separate microphone/VAD collection from synchronous
ASR work, preserve full command audio, coalesce optional intermediate recognition
under backlog, and retain endpoint timing. The existing collector had to finish
`accept` before running the next VAD frame and required microphone backlog to be
empty before accepting a silence endpoint.

`CaptureSpeechQueue` now classifies PCM on a separate coroutine dispatcher and
passes immutable PCM/decision/capture-time frames to the sole ASR owner. Its byte
bound is 25 seconds at 16 kHz PCM16, with explicit failure instead of dropped or
conflated audio. Pending bytes include frames undergoing classification. Both
this queue and the hardware backlog participate in endpoint checks; queued
resumed speech must drain before a pause is accepted. Detector and recognizer
resources are released only after collection and native work have returned.
Cancellation after a native accept cannot publish late partial words.

Normal Moonshine capture suppresses optional Java-side partial decoding when
at least 200 ms remains queued, or confirmed speech is followed by low-VAD silence
(probability below 0.15). PCM still goes through the existing speech gate and
native feed in order. Once current, ordinary partial cadence resumes; `finish`
still forces the final transcript. Segment rollover propagates this policy and
preserves its existing overlap and accumulated words. Suppressed cached hypotheses
cannot act as fresh weak-speech evidence. Other ASR implementations retain their
existing feed behavior. Bounded barge-in probes keep their existing final-only
policy, limits, and cadence restoration.

`capture_silence_detected` distinguishes acoustic silence from recognition lag.
`capture_level` now separates audio-time silence from processing lag and backlog.
`capture_endpoint_timing` is retained per turn as `capture_endpoint`, including
speech-end-to-final, detector-silence-to-final when observed, cumulative final
decode time, and deferred partial chunk count. These are software/VAD timings,
not physical microphone-to-speaker measurements. The existing pipeline evidence
still separates reply-text wait and first audio. No microphone PCM is saved.

104 focused JVM tests passed: actual pinned SDK cadence/suppression/restoration,
blocked recognition with continuing VAD and preserved resumed words, explicit
queue overflow, cancellation during native work without late publication, and
existing capture/endpoint/speaker/segmentation and interruption regressions.
Tests using a synthetic synchronous clock explicitly inject a test dispatcher;
the blocking-recognition tests use the production dispatcher. Android debug and
signed release CI must pass before delivery. Phone acceptance remains pending.

**Scope and tradeoff:** intermediate text may update less often under load.
Native final decoding can still be slow, and other ASR engines may still fail
to keep up. This commit does not fix sustained TTS supply, Paul accent changes,
reply-text latency, or keyword/natural interruption. No new model or user setting.

**One-question phone card:**
1. Install the supplied next APK. Keep current settings and disconnect Bluetooth.
2. Tap Start Listening, say “Hey Jarvis,” and wait for the listening cue.
3. Say once: “Why does the Moon have phases? Answer in two sentences.”
4. Stop speaking and let Jarvis answer. Do not interrupt this test.
5. End the call with the on-screen control and copy the latest Voice Call diagnostics.
6. Report whether the full question was captured and whether it still sat in the
   listening state for several seconds after your last word. Send the diagnostics.

Review the capture endpoint evidence independently of the later first-text and
speech playback delays. A missing/truncated question, discarded command, or
multi-second growing capture backlog fails this step. A quicker response must
not be counted as overall voice acceptance while playback remains broken.


#### C3 CI follow-up — preserve prefetched audio at handoff

Build 681 / `c314be522fef08b99ac2faa3f6b0391cde278001` passed release tests,
packaging, callback ABI and signing, but the debug suite failed
`recognitionEndpointLeavesNextSpeechForTheReplyReader` (463 tests, one failure).
Do not use 681 for the phone test. Investigation found that the new asynchronous
reader could advance the call's consumed cursor ahead of ASR and could still be
running when turn completion was reported. The test also used a scheduler yield
instead of waiting for its first recognition, making its explicit-stop boundary
ambiguous with asynchronous capture.

The corrective commit gives session-backed audio an explicit consumption
acknowledgement. Capture prefetch preserves the frame sequence; only delivery to
the ASR consumer advances the call cursor. QuietSpeechAudioInput forwards these
acknowledgements. Unprocessed prefetched frames remain replayable in the existing
bounded call history; existing history-overflow rejection prevents a partial
handoff if they are no longer retained. Ordinary non-prefetch consumers keep their
original cursor behavior. Capture reports completion only after its producer has
been cancelled and joined. It does not release the shared hardware recorder.

The endpoint/handoff test now waits for the first recognized frame before asking
for explicit completion, and a new test verifies prefetched frames 2 and 3 replay
when ASR consumed only frame 1, through the actual gain wrapper and call session.
114 focused JVM tests pass including all VoiceAudioSession tests. The corrected
Android build must pass both debug and release jobs before phone delivery; use
the same one-question phone card above. No phone acceptance yet.


#### C4 — fixed neutral acknowledgments and bounded sentence recovery

User authorized this implementation after confirming **no interruption attempts
in either build 682 run** and silence after the Moon answer. Those runs are
no-interruption controls, not barge-in failures. Sky: speech-end-to-final 4913 ms,
46 underruns and 4408 ms observed starvation; Moon: endpoint 865 ms, zero observed
starvation (one final-drain underrun). The later Moon follow-up falsely detected
speech, got an empty transcript and entered Gemma fallback. Playback transcripts
included Jarvis's own words and the old filler with an empty reference. These
remain separate findings; this change does not claim to resolve the false
follow-up, sustained synthesis deficit, accent drift or long endpoint delay.

Implemented behavior:
- Replace the bundled “Ummm...” with “One moment, please, sir.”; ship a separate
  “Bear with me, sir.” recovery recording. Both use Paul and pinned checksums,
  require no on-phone generation, and make no agreement or lookup claims.
- Keep the 700 ms acknowledgment threshold. An answer ready before the clip starts
  skips it. Once started, the clip finishes naturally while native answer
  production continues. No repeated startup/stage fillers. Stop, pause and call
  cancellation still interrupt playback; a four-second guard bounds a bad clip.
- Ordinary calls have capacity for eight queued PCM chunks (Paul callback chunks) (typically about 3.2 s)
  plus the one synchronous send in progress and the writer's current chunk.
  This remains bounded; benchmarks retain capacity two. Existing device buffer
  capacity, voice settings and answer PCM remain unchanged.
- A recovery opportunity exists only between completed natural sentence groups,
  after the producer knows another answer submission exists. No end-of-answer
  recovery marker and no filler inserted within a sentence or callback boundary.
  At <=250 ms of device audio and <640 ms of available next-answer PCM, the writer
  waits for the prior sentence to drain. If generation catches up before then,
  it skips recovery; otherwise it plays at most one recovery clip per answer.
  Incoming PCM is retained in order while the clip finishes. Later shortages
  remain measurable; an underrun counter does not trigger repeated speech.
- Both cached phrases enter the spoken reference at playback start. Empty callback
  text no longer pads that reference with spaces. Cached playback is marked active
  for duplex budgeting and retains the same short speaker-tail interval. This is
  reference accounting, not proof of acoustic echo cancellation or reliable barge-in.
- Pause the drained answer track during recovery, resume after the clip unless
  user-paused/stopped. Answer captions, delivery frame accounting and saved answer
  WAV exclude cue PCM. Intentional cue duration is excluded from supply-gap and
  observed-starvation time; the raw Android underrun counter is still reported.

Validation: 21 focused JVM tests pass and cover fast-answer skip, missing cache, complete-cue
handoff, cancellation, once-only limits, prior-sentence drain, catching up before
recovery, PCM arrival during a clip, ordered bounded producer headroom, and pinned
WAV content/levels/edges. Compile against Android 35 and Sherpa 1.13.7 APIs locally;
Android debug/release CI and phone listening remain required for acceptance.

Phone card after the signed build passes:
1. Preview Paul's acknowledgment and recovery phrase in Voice and response speed. Report whether the
   words are clear and the voice acceptable.
2. Start Listening, say Hey Jarvis, then ask “Why is the sky blue?”
3. Stay silent through the answer; end the call with the on-screen control.
4. Send diagnostics and report whether the opening/recovery sounded complete,
   whether a recovery phrase occurred, and whether pauses remained.
Do not expect recovery on every answer: it must remain absent when unnecessary.
A repeat, overlap, lost answer words, or a cue after the final sentence fails this step.


## C5 — quiet recovery, consistent pace, and guarded follow-up capture

Build 683 phone clarification: Justin said only “Why is the sky blue?” and was
then silent. The accepted “Thank you.” and its generated reply were false turns.
The retained microphone transcripts also contain the assistant's own speech.
No microphone recording establishes whether the false final came from echo,
noise, ASR hallucination, or a combination. Do not label these as user interruptions.

Changes:
- At a known completed sentence, wait quietly for 1000 ms of queued source PCM
  when supply is low. Pause the answer track only once the prior sentence drains.
  Resume when headroom arrives, production finishes, or 4500 ms elapses with PCM
  available. Never discard or reorder a pending chunk. A producer failure propagates.
- Only after a 2500 ms drained stall may the pinned “Just a moment, sir.” play,
  at most once per answer. Completion of that clip is preserved. Stop/cancellation
  releases the wait; user pause keeps the answer track paused. Intentional waits
  are excluded from starvation estimates, with separate sentence_rebuffer logs.
- Add selectable 0.85× profiles and matching cached-cue pace, pitch 1.0. Existing
  saved profile IDs and automatic benchmark matrix remain unchanged. Preview and
  Apply use existing speech-tuning controls; no silent settings migration.
- Only automatic post-playback follow-ups require supporting acoustic evidence:
  >=240 ms strong VAD, or >=96 ms plus live recognized words, or the existing
  corroborated whisper path. An isolated unsupported sound is rejected before
  final ASR/speaker processing and capture remains open. Sustained speech can
  still use empty-ASR Gemma fallback. This is a bounded guard, not a guarantee
  against longer echo or all ASR hallucinations. Retain followup_speech evidence.

Validation: focused capture tests cover false final “Thank you” after a 100 ms
sound, keeping the microphone open, and accepting the next real “Yes”. Policy
checks preserve sustained empty-ASR fallback and corroborated whispers. Queue
checks cover silent refill, prolonged cue, completion, ordering and cancellation.
Pinned WAV wording checked with local Whisper base.en; level and edge checks
cannot establish subjective naturalness. Android unit/debug and signed-release
CI must pass before delivery.

Phone card: select 0.85× in Speech tuning, preview both clips and Apply to calls.
Ask “Why is the sky blue?” once, then remain silent through the reply and for
five seconds afterward. Stop and send diagnostics; report any invented follow-up,
awkward phrase, or choppiness. This does not claim sustained TTS throughput or
endpoint latency has been solved by buffering or slower playback.


## C6 — export the complete comparison in one copy

Justin's suite `48a57deb-7fcc-4a3c-b5f6-4260f62340ed` was exported as only run 14
by the single-run copy action. Add **Copy whole suite · N runs** above the selected
result. It reads all saved records matching that suite ID, orders by original WAV
execution number (timestamp fallback), retains failure details and provenance,
and reports saved/completed counts plus missing runs for the 14-run Paul suite.
Existing saved suites work after updating; no repeat test is needed merely to export.
The separate single-run button remains available.

Listening feedback: runs 5, 10, 13 were clearest with no accent changes. Under the
forward-then-reverse case order these are paragraph-one-reset (pass 1),
paragraph-one-reset (pass 2), and opening-one-reset (pass 2). Both paragraph
passes favor one submission. This supports comparing fewer resets but does not
prove all grouped synthesis changes accent.
Do not apply a new voice setting based only on the last-run report. Run 14 was
thermal-limited; inspect the complete suite before comparing timing.

Validation covers persisted suite selection, execution ordering, incomplete suite
reporting, retention of failed runs, and exclusion of unrelated suites. Phone:
update, browse saved text runs if needed, select any run from the completed Paul
suite, then tap **Copy whole suite · 14 runs** once and paste it into the conversation.


### C3 follow-up — Kokoro sentence callback delivery (next APK)

Justin explicitly prioritised Kokoro, whose voice he prefers, while running the existing all-profile comparison. This is one bounded C3/A3 change, not completion of the sixteen milestones. Later C4–C6 headings above are repair cards, not additions to the original A1–F2 milestone IDs. The Gemma latency extension and Paul passage grouping are deferred; neither is bundled here.

- Kokoro non-full-text generation now uses the pinned Sherpa 1.13.7 sentence callback, copies each returned PCM sample once, and never replays the returned full utterance. Upstream contract checked in `sherpa-onnx/csrc/offline-tts-kokoro-impl.h` at tag `v1.13.7`: callback after each native sentence batch, not within-sentence latent streaming. No native/model change or promise of faster sentence inference.
- Native sentences are handed to the writer in at most 400 ms PCM pieces with a 32-entry queue (12.8 seconds queued pieces, plus writer/AudioTrack/native working memory). This allows bounded synthesis ahead and keeps cancellation/backpressure on the native owner. Full-text benchmark baselines and prepared openings keep their existing buffered semantics. Selected voice, thread count, speed and text profiles are preserved; Paul remains available.
- Existing completed-sentence refill handling is enabled for Kokoro at punctuation-ended application submissions. Character/clause splits do not qualify. No sample trimming, mid-clause filler or added audio normalization. A single sentence still must finish native synthesis before its first callback.
- Copied single-run and whole-suite reports now retain `pcm_delivery`, `observed_playback_starvation_ms`, and a Kokoro `source_pcm_summary` measured over consumer-accepted PCM. Near-silence is a 10 ms energy-window measurement, not intelligibility, a measured acoustic pause, or a reason to trim audio. Full-text PCM uses the same analyser with the explicit `consumer_accepted_pcm` scope; the report includes the longest near-silent interval. Source summaries exclude inserted cues, playback time stretching and playback gaps. Incomplete playback is marked incomplete. Existing underrun totals still include drain events.
- Callback parity is checked against returned float PCM in benchmarks; live calls check frame counts and sample rate. Tests cover exact sample order, one caption start, bounded chunks, propagation of consumer closure, recovery boundary eligibility and persisted whole-suite evidence.

Validation before publishing: 38 focused playback/source-analysis tests and 41 report/profile tests passed; the modified Android audio implementation compiled against the Android and pinned Sherpa SDK jars. Full Android CI and phone acceptance are pending at this commit.

Phone screen: retain the already-running old-build suite as a baseline and copy it once with **Copy whole suite**. After installing the next APK, use the preferred Kokoro profile for the same short question and one longer explanation, then copy the call diagnostics. Do not rerun the whole matrix automatically. Listen for pronunciation/voice continuity, missing or repeated words, first-speech delay and pauses. New-build callback timing is not directly equivalent to old whole-phrase completion timing; compare build and `pcm_delivery`. Device sound/throughput and sustained-load acceptance remain pending.

### 15 September — B1: actual speech-LM appendability probe

Justin prioritized Paul's voice consistency over latency and rejected the retained
Mimi/RNG test despite its healthy supply metrics. The isolated host probe in
the retired `experiments/pocket-append` probe (available in Git history) inserts text into
the currently generating LM cache, retaining decoder, RNG and current latent.
Nine hash-matched model cases established mechanical insertion but failed content
completion for mid-generation/word-wise input. Full-text controls transcribed
correctly; two word-wise cases hit the 32-second diagnostic ceiling. No accent
acceptance is claimed from ASR. This remains **B1**, not a B2 winner; Kokoro tuning
is paused and live-call behavior is unchanged. No additional phone suite requested.
