> **Historical development record.** Earlier “current policy” summaries below
> are superseded where they conflict with the current source. Calls now select
> speech-recognition mode or **Gemma audio understanding**; direct Gemma mode
> submits the original recording and optional Whisper captions are display-only.
> It is not restricted to empty-ASR recovery. See the [current audio contract](verification/voice-audio-and-metrics.md)
> and [`VoiceInputMode`](../app/src/main/java/com/battlesbudz/jarvis/v2/voice/VoiceInputMode.kt).
> Preserve the dated evidence below; it does not verify the current candidate.

# Current voice pipeline and remaining acceptance

> 17 September incremental-input update: ordinary voice replies now use text-only incremental Gemma prefill on one native input session per utterance. The old speculative answer/restart path is removed. Optional input processing is deferred for ASR backlog or Android thermal status 5/6 only. See [incremental voice input](voice-incremental-input.md) for the implementation, diagnostics and remaining phone checks. This supersedes older speculation/audio-per-answer descriptions below.

> 17 September update: the build-705 context/short-interruption repair supersedes earlier cross-call carryover and multiword admission policies. New calls start clean; explicit Resume retains saved dialogue. Exact prompts and speaker-before-stop diagnostics are described in [current diagnostics](current-diagnostics.md). Phone acceptance remains open.

Audited 16 September 2026 against builds 694–695 and the release-only branch head `dbc43d17ef60d25b4234eb83bca7979c1f712fe0`; updated with the interruption/answer-priority implementation below. This document is the current policy summary; dated experiments in the older guides are historical. Work stays on `audio-pr2` / PR 6; publishing an APK does not authorize merging it.

## Supported-stack update — 16 September 2026

ASR: Moonshine or Whisper. AI: E2B or E4B. TTS: Piper Northern English Male only.
Initial setup now installs Piper. Retired selections migrate; exact obsolete model
files are cleaned on voice preparation; saved calls/history are retained.
Piper generates its own cached acknowledgements. Experimental tuning and benchmark
routes are removed; fixed natural Piper settings now apply to every call.
See [current settings and diagnostics](current-diagnostics.md). See [migration details](supported-model-stack.md).

## Current user goal and policies

Justin accepts Piper Northern English Male's voice and accent consistency. Preserve its 320-character passage target, 640-character cap, native whole-passage synthesis, natural sentence pauses, normal speed, and current speaker. Piper is now the only supported speech output. Kokoro/Paul implementations and experiments have been removed; see [supported stack and upgrade behavior](supported-model-stack.md).

- One call-owned microphone remains open across command/reply handoffs. The read cursor preserves unconsumed onset audio. External-microphone priority, explicit End/Pause and foreground/background eligibility remain authoritative.
- Ordinary listening uses Android-requested AEC/noise suppression and no additional software gain (`maxGain=1`). Enabled effects do not prove acoustic effectiveness. Silero confirmation, room-floor admission, and ASR are separate decisions. Neither VAD nor volume distinguishes Justin from another person speaking.
- Moonshine uses bounded external speech admission with 240 ms pre-roll and 320 ms trailing context, native VAD bypass, fresh ordinary-command model state, and warm bounded reply probes. This costs model-load time; evidence is still needed before safely restoring resident ordinary-command weights.
- Whisper uses growing batch windows on one native worker. It is not native streaming. Its selected setting is preserved; the current unset preference defaults to Whisper. Ordinary turn admission/endpointing is shared with Moonshine.
- Explicit goodbye/stop-listening controls are handled locally. Ordinary accepted turns pass recognized text to Gemma; recorded audio is used only for explicit empty-transcript recovery. Complete-audio recognition recovery and `[NO_SPEECH]` handling remain available. A rolling long-turn audio tail cannot stand in for the whole request.
- Long speech is accumulated as text across internal 15/22-second segment boundaries; raw audio is bounded at 25 seconds and turns at 120 seconds. Uncertain text seams cannot execute a partial command.
- The current acknowledgement is one cached “One moment, please, sir.” after a 700 ms wait. Once answer PCM is ready, allow up to 250 ms for the clip to finish, then cancel it with a 40 ms fade and release its track before starting the answer. It is excluded from meaningful-answer latency. Older “Um”/repeating-filler policies are superseded.
- Stable ASR text chunks extend a single Gemma input session during speech. No answer draft is decoded or cancelled on speech resumption. A changed committed transcript or compacted prompt rebuilds once at final validation. Optional prefill defers above 200 ms capture backlog or thermal emergency (status 5/6), with no draft cooldown. Final actions still require final request validation. Device latency savings remain to be measured.
- Both selected ASR engines now use bounded natural-interruption probes and ASR-verified playback stop. Whisper borrows only warm call-owned weights atomically and decodes a final-only clip of at most four seconds; no growing-window background worker runs inside its probe. The worker releases its lease before any confirmed correction starts ordinary recognition. Cold/busy models, expired results and exhausted playback/work budgets retain the keyword fallback. Phone duplex parity is not yet accepted.

## Build 694 evidence and changes in this follow-up

Call `490264e9-79e3-481e-8275-7aa87f5a1cfd` used Whisper. Its final completed turn had `issue=null`, 2823 ms detected-speech-end to final transcript, approximately 1800 ms final to answer text, and 2640 ms text to playback. Total substantive-answer delay was approximately 7.27 seconds. Piper synthesis was 846 ms; generated-passage accumulation and playback startup account for other TTS-stage time. No observed playback starvation was reported; the single drain underrun is not evidence of an internal speech gap.

The last unfinished listening turn accepted RMS 104 at VAD probability .644. The actual room-floor gate estimate was not logged for that accepted frame; the separate quiet-gain estimate is not interchangeable. Without microphone audio, this does not establish the physical source of the sound.

Implemented in this follow-up:

1. **Call-scoped noise calibration:** ordinary listening borrows a calibrated floor from the same microphone session, preventing an uncalibrated interval at every reply-to-command boundary. Reply playback listeners cannot update that profile. Reopening the hardware gets a new profile. Quieter confident non-speech can lower the estimate immediately; real immediate speech is not muted by a fixed guard period. `capture_noise_calibration` identifies a new versus reused floor.
2. **Actual recognition audio test:** Settings → Check what Jarvis hears → Record and test recognition uses the selected ASR, production `AudioTurnCapture`, bundled Silero, acoustic gate, and AEC/NS microphone configuration. Listen for eight seconds after readiness. The test deliberately uses a fixed finish rather than adaptive conversational timing and invokes no Gemma or tools. Play the microphone PCM and decoder PCM separately; copy final text, issues, model/build, per-frame raw/accepted VAD, floor/RMS/peak and recognition timing. Moonshine captures its actual post-gate stream; Whisper replaces overlapping windows and retains the latest decoded window. Audio is bounded to 25 seconds, memory-only, and discarded on leaving settings. This isolated test does not reproduce speaker echo during a live reply; live duplex recordings remain an acceptance gap.
3. **Whisper optional-work contract:** `allowPartial=false` now reaches its worker scheduler. Silence/backlog deferrals retain every PCM byte and still allow final decoding, while avoiding new optional windows. An already-running native decode still must finish safely; this change does not guarantee a particular millisecond saving.
4. **Newest-first context budgeting:** the compact voice context now reserves space for the latest exchanges before older summary/topic material. The old final `.take(3000)` could truncate the latest reply. The bounded history remains chronological and uses playback-aware entries supplied by the session controller. Assembled-prompt diagnostics report whether the latest user/reply excerpts actually occur in the submitted text. This fixes a demonstrated code defect; build 694's logs alone do not prove that it caused Gemma's specific “no context” answer.

Focused regression coverage: call calibration continuity/reset and playback exclusion, the reported 104 RMS follow-up, preserved quiet/foreground speech, byte-exact deferred Whisper finalization, bounded streaming versus overlapping batch audio evidence, and latest rap exchange retention under maximal old context. Full Android tests, packaging and signing are publication gates. A successful build does not establish phone acoustic acceptance.

## Implementation continuation: interruption and ready-answer priority

- Connected Whisper to the existing bounded natural-correction and playback-stop path. Existing rolling probe budgets, echo rejection, freshness checks and playback-supply admission remain enforced. Optional Whisper work cannot load a second model or borrow a model already in use.
- Verified final-only “stop listening” results retain end-conversation intent, including the reported numeric decorations. They pass the existing local farewell path without a Gemma answer or a second ASR pass. Ordinary “stop” still means stop the reply and listen for a new request. Echo containing “stop” does not authorize a stop.
- A ready answer no longer waits seconds for cached acknowledgement completion. A nearly finished cue may drain for 250 ms; otherwise a 40 ms fade precedes track release. Explicit stop still pauses immediately. `acknowledgement_answer_wait_ms` and `acknowledgement_yield` make the remaining wait visible. No default Piper passage, sentence-pause, speed or voice changes.
- Regression coverage includes cold/busy/wrong-model rejection, release after active ownership, final-only probe budgets, non-echo stop, end-call intent with numeric artifacts, long-filler cancellation and cleanup before answer admission.

- Historical experiment (now removed from settings and calls): an opt-in **Piper · faster opening (compare)** profile: a complete sentence around 160 characters for the opening, then the existing 320-character target and 640-character cap. Native whole-passage synthesis and natural pauses remain enabled. The current cleanup fixes calls at 320 characters and removes profile selection. Passage-level tests retain boundary and text-retention coverage for development.

## Remaining work, in priority order

| Priority | Work still required | Acceptance evidence |
| --- | --- | --- |
| 1 | Recognition and microphone quality on the Fold 6: onset, near/quiet speech, room noise, competing speaker, decoder-versus-microphone audio | One short recognition recording for the selected engine; compare heard words with transcript and gate decisions. Do not increase thresholds or change AEC modes blindly. If decoder input is clean but errors persist, compare the other engine on the same recording before selecting a model. |
| 2 | Phone acceptance of the implemented shared interruption path; measure Whisper playback stop, natural corrections and echo probe cost | Early/late stop and a real correction during a long reply; echo alone does not interrupt, correction onset retained, playback stop time logged independently of native cleanup. Keep one ASR owner; do not simply enable continuous Whisper decoding. |
| 3 | End-of-turn latency: speaker-check cost, pending decode/finalization, uncertain 1500 ms and hesitation 3500 ms waits | Matched normal, hesitant and resumed-speech clips. Reduce dead work while retaining 2–3-second thinking pauses and corrections. Installed Smart Turn now controls eligible native-audio endpoints, with bounded fallback; real microphone cutoff calibration and phone latency remain unverified. See [the current endpoint contract](verification/smart-turn-endpoint-2026-10-10.md). |
| 4 | First substantive answer delay: passage accumulation and context/audio inference cost; verify the new bounded acknowledgement wait | Separate speech-end → final, final → text, text-wait → synthesis → playback. Use the fixed 320-character natural-passage baseline; any future opening experiment needs a deliberate code change and phone acceptance. Compare text-only vs tandem audio using actual recognition errors as well as latency. |
| 5 | Sustained-call scheduling and cancellation under heat | Warm and later-in-call latency/accuracy distributions, memory bounded, no ASR backlog, no playback starvation, and prompt mic release after stop. Build 694 logged thermal level 4; isolated cool benchmarks cannot establish sustained throughput. |
| 6 | Context and action continuity | Rap/story follow-up keeps the immediately preceding delivered answer; interrupted unplayed text excluded; final request authorizes tools once; long-turn seam corrections retained. New inclusion diagnostics distinguish missing prompt content from model reasoning errors. |
| 7 | Full lifecycle/route acceptance | Screen-off/background wake, external recording/dictation handoff, Pause/Resume, rotation/process restart, phone speaker and supported Bluetooth/EYE VUE routes. AEC enabled flags alone are insufficient. |

The first short test after this build should use **Record and test recognition**, not another whole TTS suite. Say one question, then remain quiet; listen to both recordings and copy the recognition diagnostics. A normal call then checks context with a short follow-up. This is a small evidence step, not a demand to rerun every historical test pack.

## Roadmap reconciliation

Phases 1–2 have implementation and earlier CI evidence; lifecycle and playback-history phone acceptance remain partial. Phase 3 now has bounded probes for both engines, but is not accepted across engines/routes. Phase 4 has segmentation and a turn-detector interface, with semantic endpoint evaluation still open. Phase 5 has work scheduling and comparisons, with selection/performance acceptance still open. Phase 6's current production concern is Piper's accepted voice versus passage latency; Paul/Kokoro are retired, and their experiments are no longer roadmap dependencies. Phase 7 integrated acceptance remains open. Completing more checklist code does not replace proving these flows on the phone.

## Completion boundary

Remaining engine selection, semantic endpoint-model adoption, shorter Piper opening selection and microphone/effect tuning are evidence-dependent decisions, not missing code that can safely be guessed. Keep current accepted voice settings until comparisons establish a better setting. Integrated phone acceptance still includes noisy-room recognition, a longer utterance, a correction during playback, stopping/ending, delivered-context follow-up, sustained warm calls and supported route/lifecycle transitions. Do not mark the entire roadmap complete before these checks pass.

## Response-latency follow-up after build 699

User reports that build 699 feels faster and confirms the recorded stop keyword was **not** an attempted interruption. Its rejection is correct, not a missed command. Three completed replies measured 4.289, 4.424 and 5.528 seconds from detected speech end to substantive answer playback (4.747-second mean). Build 694's four retained replies averaged 7.658 seconds. This is not a matched benchmark: 699 used Moonshine, the 160-character opening, less context and thermal level 0; the earlier call used Whisper and reported thermal level 4. The acknowledgement added zero ready-answer wait in the detailed 699 turns.

Latency-only changes following that baseline:

- The explicitly selected faster Piper opening still targets 160 characters, but after 750 ms of text collection may release an already complete sentence of at least 60 characters. A timer wakes the same native owner when generation pauses; it does not require another token. No incomplete sentence is cut to meet the deadline, so 750 ms is not an end-to-end latency guarantee. Default 320-character and full-reply policies are unchanged; later passages retain 320/640 bounds.
- Voice generation is asked to lead with a short direct complete sentence, usually 10–18 words, followed by the explanation needed. This adds no separate inference pass and does not truncate the generated answer.
- `piper_opening_wait_ms` reports first incoming text to opening submission separately from synthesis. `whole-passages-v3` logs the applicable opening wait policy. Compare this against opening synthesis and first playback before claiming measured gains.
- Tests cover a stalled text stream, incomplete sentences, short acknowledgements, abbreviations, unchanged default/full-reply policies and preserved continuation text. Voice consistency and actual latency require a new phone comparison; the deliberate interruption baseline remains build 699.
