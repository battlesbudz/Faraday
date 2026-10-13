## Live-call false-trigger recording — 20 September 2026

Development diagnostics now includes **Record next call audio for diagnosis** and
**Clear diagnostic audio**. Arm before the test call, end the call, then use
**Save latest call test ZIP**. Arming applies to one call only. Ordinary call ZIPs
remain text-only. Armed recordings stay in bounded RAM until export/clear/new call
or process exit; no automatic disk recording/upload. Pause/end stops recording.

The ZIP adds `audio-evidence.txt` and available WAVs for post-platform microphone
input, natural-probe submissions and written answer/filler PCM, with monotonic
frame/write/playback-head events. PCM limits are 4 MB total, 800 KB per stream;
trimming and offsets are explicit. These are not synchronized laboratory acoustic
measurements: hardware pre-AEC audio, tone cues, applied volume/fades and exact
physical onset/silence remain unavailable. Compare retained intervals by metadata,
not by assuming WAV files start at the same time. Nearby speech is recorded too.

`barge_acoustic` reports actual room floor, signal and VAD admission.
`barge_confirmation` distinguishes sustained speech, fresh audio required and
fresh audio agreement. `followup_speech_evidence` also applies to final correction
capture. These fields establish why a gate passed; phone audio establishes whether
its interpretation was correct.

# Current settings and development diagnostics

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
controls playback; no volume is forced. With the current Android 11/API 30 minimum,
Android 11 retains the legacy media route; Android 10 support was retired on October 6.
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


## Phase D acoustic evidence

With the call and wake listening stopped, open **Voice Call settings → Development diagnostics →
Echo test — Phase D**. Choose **Communication phone speaker** for the D2 candidate (Android
12+), or **Current media route** for the D1 baseline. D2 explicitly selects the built-in speaker;
disconnect headphones/Bluetooth audio first. D2 uses call volume; baseline uses media volume.
Keep phone placement and perceived loudness comparable and inspect the recorded volume values.

Run **Jarvis alone** while completely silent, **Your voice alone**, then **Both voices**. For the
latter two say slowly “No. Yes. I. Stop. Hey Jarvis.” Follow the on-screen listening instruction;
preparing/downloading models is not part of capture. The diagnostic deliberately does not
interrupt Piper on those words: it records their recognition. Use **Stop echo test** to cancel.
Each completed test has **Save ZIP: …**; a combined archive remains available. Save before
clearing recordings to change routes. Leave and reopen after saving if the document picker has
cleared the in-memory results. Run in the foreground; Stop/backgrounding releases capture,
playback, and the D2 route. Native model cleanup finishes before busy state is released.

D2 pairs `VOICE_COMMUNICATION` capture with `USAGE_VOICE_COMMUNICATION`,
`MODE_IN_COMMUNICATION`, explicit speaker selection, and transient communication focus.
The scoped owner is recognized by the microphone-priority policy so our own mode does not
self-suspend. Outside recorders, Android silencing/mute, calls, dictation requests, focus loss,
and selected-route loss still interrupt the test. Route loss cancels instead of silently
continuing on headphones/Bluetooth. The test withdraws its own mode/device/focus requests
before offline recognition, on failure, and on cancellation; Android chooses the next route.
It does not force a formerly observed device over another app's new routing decision.
Normal calls retain their current route while D2 phone acceptance is pending.

Each ZIP scenario includes `report.txt`, `timeline.csv`, `microphone.wav`, optional
`piper-reference.wav`, and available `decoder-input.wav`. Reports include build/commit/device,
selected route profile, route acquire/release evidence, actual route IDs, volume, AEC availability/control/implementation, capture statistics, transcript and
keyword scores/hits every 100 ms of replay. The timeline keeps microphone read frame ranges,
playback head observations and hardware timestamps on the same monotonic clock. Empty hardware
fields mean unavailable; read delivery time is not acoustic onset. Audio is bounded in memory
(12 seconds of capture maximum, 1,200 timing rows); truncation is reported. Save is explicit and
uses Android's document picker. No automatic disk recording or upload is added to normal calls.

Limitations: microphone PCM is **after Android processing**; pre-effect PCM is unavailable.
AEC enabled is not measured effectiveness. Piper reference is exactly the submitted eight-second
clip at 22,050 Hz / 1.0x, with observed played positions, not acoustic loudspeaker output. The
full synthesis text can extend past that clip. Recognition uses the selected adapter on the
whole captured clip **after** recording without VAD filtering; keyword replay also runs afterward.
These results isolate acoustics and model responses, not live CPU scheduling, automatic stopping,
or owner verification. A quiet transcript is not alone proof of effective echo cancellation;
compare the recorded audio as well. This is a D1/D2 controlled comparison, not D3 software AEC. Tests run independently of
Gemma, live interruption gating, and speaker verification. An empty Moonshine transcript in
the original D1 voice-only recording remains a separate unresolved diagnostic finding.


Current on `audio-pr2` / PR #6, 17 September 2026.

## False interruption investigation (build 709 follow-up)

`speaker_interruption` now includes `playbackScores`, `playbackReference` and
`minimumOwnerMargin`, alongside owner `scores`. A high owner score alone is not
proof that the user spoke. `PLAYBACK_ECHO` rejects candidates insufficiently
distinct from actual Piper audio; missing required embeddings are uncertain.
`barge_evidence` retains `near_echo` fragments and final speaker rejection reasons.
Successful natural stops log `recognizedWords` and `acousticOnly=false`.
Sound or VAD alone must never stop output. See [repair and phone acceptance](voice-interruption-echo-repair.md).

The build 710 follow-up adds `referenceSource=playback_head`, `startFrame`,
`endFrame`, `capturePositionAgeMs` and `referenceAudioMs`. These identify the
actual played samples selected for the candidate's capture timestamp. An
`unavailable:*` reference fails closed; passage-opening samples are no longer
reused for interruptions occurring later in a long answer.

## Everyday settings

- AI model: Gemma 4 E2B or E4B, using the existing verified installer and model readiness check.
- Speech recognition: Moonshine Small Streaming or Whisper base.en.
- Voice: Piper Northern English Male medium. There is no voice/profile selector.
- Default assistant and keyboard microphone-handoff setup.
- Reset learned speaker preference when recognition is favoring the wrong speaker.

Piper uses four synthesis threads, native sample-rate playback (1x), complete
sentences targeting 320 characters, and a 640-character generation cap. Native
whole-passage synthesis and natural pauses remain enabled. Old saved thread,
playback-speed and opening profiles are ignored. The experimental 160-character
opening is no longer applied to calls. The accepted 320-character policy remains
the baseline; this cleanup makes no new phone latency or quality claim.

## Retained development checks

Open **Voice settings → Development diagnostics**:

| Check | Purpose |
| --- | --- |
| Record 8 seconds / play recording | Inspect the actual microphone path for distortion or missing speech |
| Record and test recognition | Compare microphone PCM, actual decoder input and the selected recognizer's transcript; copy its report |
| Test wake word | Isolate wake recognition without running Gemma |
| Copy diagnostics | Export the current call's timing, recognition, speech delivery, interruption, model/build and process-exit evidence |
| Save latest reply audio | Listen to generated Piper audio when investigating gaps or voice quality |

Recording tests are disabled during calls or other microphone work. Model/ASR
changes and starting a call are disabled while a microphone diagnostic is busy.
The selected-model readiness check remains in setup because it verifies whether
the installed model can initialize and answer before calls become available.

Automatic call diagnostics retain useful timing, playback starvation, cancellation,
thermal/scheduling and crash evidence. Thread counts and thermal state may still
appear as measured context in developer logs; they are not user tuning controls.
Saved Voice Calls remain available; only explicit Resume imports their transcript. New calls no longer import recent calls automatically.

## Removed routes

Removed the 144-run voice-profile matrix, manual thread/pace/opening controls,
profile apply/restore, synthetic P1 setup/P2 load packs, benchmark WAV export,
Gemma acceleration and text/audio comparison runners, duplicate two-engine ASR
room comparison, and the unused chat screen's direct 25-second Gemma audio and
simulated-tool tests. Their controllers, replay-only synthesis branches and
dedicated fixtures/tests are removed from the app. Production Gemma audio input,
tools, native scheduling and recognition are retained.

Old benchmark preferences are not executed or migrated into current call tuning.
Existing archived records are left on disk with their original identities; there
is no benchmark browser or replay route. Historical documents remain evidence,
not instructions for the current UI.

## Acceptance still needed

On a phone, upgrade with an old slow/profile selection saved, verify ordinary
Piper playback, exercise E2B/E4B and both recognizers, check stop/correction/goodbye,
and inspect/export a real call. Confirm diagnostics release the microphone on
cancel or leaving settings. E4B memory/latency, acoustic performance and sustained
call interruption acceptance remain open in [the current pipeline](voice-pipeline-current.md).

## Exact model input and call boundaries (build 705 follow-up)

Copy diagnostics retains the latest 24 actual Gemma text-prompt submissions separately
from timing chatter, without the former 1,200/1,500-character truncation. This includes
speculative drafts, final inference, transcription fallback and text retries. Each entry
has turn/submission IDs, model ID, audio/text mode and byte count, native-session/prior-
submission counters, tool-enable state, and the exact submitted text. A consumed draft
has already been recorded at its actual submission; it is not another inference.
These are app-submitted prompts, not a dump of the SDK's internal chat template.

History provenance identifies the current call or explicitly resumed call, total and
selected entry counts, and oldest/newest selected timestamps. The exact prompt shows
which content survived the newest-eight-entry, 300-character user/450-character assistant,
3,000-character context budget, plus any summary and resolved subject. New call IDs clear
summary and subject state. An unrelated current request clears a stale resolved subject.
Prompt snapshots clear at the next diagnostic session and remain app-private until copied.

`audio_text` means Gemma was sent both audio and text; it does **not** count corrections
of Moonshine. `audioCorrectionCount=not_observable` states this explicitly. Recognition
text evidence separately retains ASR/resolved text and the transcription-fallback flag.

## Short interruptions and speaker preference

Stop and Hey Jarvis keyword paths remain. Natural interruption no longer requires a
command, question or minimum word count. A brief word can take the floor, including
No, Yes or I; a lone floor-taking word returns to listening without asking Gemma to
answer it. Echo-only clauses are removed from the captured correction.

The first natural probe can start from 250 ms of buffered PCM after 200 ms from the
VAD candidate onset. There is no three-second speech prerequisite and no extra lexical
stability wait on the speaker-verified path. Buffer age, ASR, speaker embedding and
playback-stop processing still add latency. `speechOnsetToStopRequestMs` measures the
VAD-candidate-to-stop-request interval, not the acoustic first phoneme or final audible
frame. Device measurement is still required.

Before natural interruption stops playback, the same PCM clip used by ASR must positively
match the learned speaker preference (cosine >= 0.65 in an available embedding window).
A missing profile, insufficient embedding or uncertain score leaves playback running;
keywords remain available. Speaker extraction runs off the capture loop and is joined
before releasing its native owner. Logs report MATCH/DIFFERENT/UNCERTAIN, scores, clip
length and compute time. This is a learned voice preference, not verified owner identity.
The existing preference needs consistent speech over at least three distinct activations;
its training-duration rules are not a per-interruption waiting period. Reliable matching
of a 270 ms word amid speaker echo remains a Fold acceptance test, not a guaranteed result.

## Incremental text input (17 September follow-up)

Ordinary voice answers log `mode=incremental_text` (or `voice_text` for a final-only
text path), `audioBytes=0`, and `retainedAudioBytes` separately. Keeping a recording
for recovery does not mean it was submitted to Gemma. Explicit empty-ASR recovery
still records its own audio submission and fallback decision.

The retained per-turn `input_finalized` record reports `reuse`, input chunks,
retained characters, full prompt length and `listeningPrefillMs`.
`input_final_prefill` reports remaining characters and `finalPrefillMs`.
Recent `asr_partial` events now include the actual hypothesis (up to 900 characters,
with an explicit truncation flag), rather than character counts alone.
The full assembled final prompt remains in the independent exact-prompt retention.
It is the concatenated logical text input; Gemma turn delimiters are specified in
[the adapter contract](voice-incremental-input.md). These records cannot prove
what the speaker physically said without comparing microphone audio.

`input_revision` means Moonshine/Whisper revised text already processed by Gemma;
the input is rebuilt once at final validation, never restarted after every new word.
`thermal_emergency` applies only at Android status 5/6; ordinary throttling no
longer disables input preparation. `asr_backlog` can temporarily defer optional
prefill while recognition catches up. No cooldown is carried from failed drafts.

Decode TTFT starts after final explicit prefill. Use `finalPrefillMs` and the
end-to-end pipeline timestamps as well; a reduced decode TTFT alone is not proof
of reduced user-visible latency. See the [phone checks](voice-incremental-input.md).

Build 708 repair adds `input_context_prefilled`: fixed context was processed,
possibly with `chunks=0` because no user words were yet stable. `reuse=true`
can therefore mean context-only reuse; use `committedChars` to distinguish it.
`Voice incremental fallback` records a one-time final-text retry before any
output, without audio submission or unloading the engine. The streaming TTFT now
includes its small final turn-boundary prefill; logical remaining prompt prefill
is still reported separately. ASR ellipses alone no longer produce
`explicit_hesitation`; spoken hesitation words still can.
