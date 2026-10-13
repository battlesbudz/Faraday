## 2026-09-18 — Selectable Qwen models (existing PR #6)

Implemented 15 pinned Qwen candidates with per-model backends/capabilities, template-aware final-input voice generation, no unsupported audio fallback, bounded context and thought-channel handling. LiteRT-LM upgraded to 0.16.0 for Qwen3.5. Local adapter/regression checks pass; Android CI and Fold 6 acceptance tracked in [Qwen model selection](qwen-model-selection.md). This does not complete duplex/barge-in acceptance or add GitHub coding tools. User reports E4B ~30 seconds to audible reply; TTFT unknown.

# Local voice implementation plan

> 20 September implementation: the [D1/E2 follow-up and phone card](voice-repair-implementation-and-phone-tests.md#d1e2-implementation-after-build-728-2026-09-20) records opt-in live-call audio evidence, shared acoustic admission, selective fresh-probe confirmation, accurate VAD durations and final correction checks. Code/test delivery does not close D2 acoustic or E/F phone acceptance. Software AEC remains conditional.

## 2026-09-20 — Build 728 false-trigger follow-up (current priority)

The [build-728 reconciliation and bounded D/E/F follow-up](voice-repair-implementation-and-phone-tests.md#build-728-false-trigger-follow-up-and-plan-reconciliation-2026-09-20)
records encouraging intentional barge-in evidence alongside unresolved false
interruptions and falsely accepted follow-up text. This is planned hardening
within Phase 3 and Phase 7 / repair phases D–F, not a new voice architecture or a
claim that the fixes have shipped.

Existing scope already covers D1 audio/echo evidence, D2 communication-route
effectiveness, conditional D3 software AEC, E1/E2 interruption/final-input handling,
and F1/F2 regression acceptance. Added detail: bounded live-call candidate evidence
where missing, selective fresh-audio confirmation for uncertain detections,
acoustically supported final admission before history/Gemma/tools, and build-728
false-stop/false-user-turn fixtures. Audit existing code and evidence before
choosing thresholds or adding processing.

The 19 September removal of speaker identity supersedes historical voiceprint and
owner-match requirements below. Communication-route AEC/NS, external speech gates,
recognized-word/echo checks and short-word/Stop/Hey Jarvis support remain.
No noise-only pause, blanket phrase blacklist, mandatory wake phrase or automatic
software-AEC adoption is planned. D2/E/F phone acceptance remains open; positive
intentional-interruption observations do not prove false-trigger rejection.
Current shared Chat/Voice history must be evaluated under its own contract, not
automatically treated as unintended prior-call carryover. Work stays on
`audio-pr2` / existing PR #6. This documentation change requires no new APK or
phone test; future code delivery uses release APKs only.

> Build 710 follow-up: playback comparisons rejected two fragments but accepted another late in a long answer. Fixed the negative reference using passage-opening PCM instead of the candidate's actual playback interval. The speaker check now selects samples by microphone capture timestamp and observed playback head, and fails closed on missing/stale reference data. See [evidence and remaining phone acceptance](voice-interruption-echo-repair.md#build-710-follow-up-compare-the-correct-playback-interval).

> Build 709 phone follow-up: text-only generation now works, but misrecognized Piper echo triggered false natural interruptions and contaminated subsequent user messages. The repair adds whole-clause near-echo filtering, sound-caption exclusion and candidate comparison against actual Piper voice samples as well as the learned owner. Single words and Stop/Hey Jarvis remain supported. See [evidence, implementation and acceptance limits](voice-interruption-echo-repair.md).

> Build 708 phone repair: fixed an empty-input native streaming submission, added prefill on the first partial for fixed context, and stopped interpreting ASR ellipses as spoken hesitation. A pre-output incremental failure retries once with final text on the loaded engine. See [failure evidence and repair](voice-incremental-input.md#build-708-phone-failure-and-repair). Natural owner-matched interruption was observed at 890 ms; repaired generation still needs phone verification.

> 17 September incremental-input implementation: replaced throwaway voice drafts with persistent text prefill; normal replies no longer re-submit microphone audio. Emergency-only thermal admission (5/6), no draft cooldown, and separate listening/final prefill diagnostics are implemented. See [current architecture and phone acceptance](voice-incremental-input.md). This replaces the historical speculation architecture below; device latency/recognition acceptance remains open.

> 17 September follow-up: new calls now isolate history/summary/subject; exact model inputs are retained. Natural single-word interruption requires a positive learned-speaker match before stopping, with 250 ms probe eligibility and no extra word-stability delay. Stop/Hey Jarvis are retained. See [current diagnostics](current-diagnostics.md).

> Maintenance update: APK payload measurement, a reduced Sherpa JNI build profile, optional compact APK packaging and the first app modularization pass are documented in [APK size audit](apk-size-audit.md) and [component map](app-modularization.md). These changes do not close phone voice acceptance items.

> Current cleanup: obsolete benchmark routes and profile controls have been removed; fixed natural Piper settings apply. See [current diagnostics](current-diagnostics.md) and [current acceptance status](voice-pipeline-current.md). Historical test-pack checklists below are not current UI requirements.

## Current status — 16 September 2026

The [current pipeline audit and prioritized remaining work](voice-pipeline-current.md) supersedes the historical policy/status statements below. Justin now accepts Piper Northern English Male with whole passages and natural pauses. The immediate priority is recognition reliability, inspectable decoder audio, preserved recent context and response latency. Paul/Kokoro are retired and removed; Piper is the sole TTS. See [supported stack](supported-model-stack.md). This follow-up implements call-scoped acoustic calibration, an in-app recognition audio test, Whisper partial-work deferral, and newest-first voice context budgeting. Integrated recognition, interruption, latency and lifecycle acceptance remain open.

## Build 673 repair and structured phone testing

The [12 September research proposal](voice-repair-proposal-2026-09-12.md) and
[bounded implementation commits and phone test protocol](voice-repair-implementation-and-phone-tests.md)
define the next repair sequence for Paul voice consistency, playback starvation,
and failed interruption. The latter includes fixed profiles, exact user scripts,
per-build test cards, acceptance gates, and a delivery ledger. All new code steps
in that protocol are planned until their ledger records implementation and evidence.
Use its assigned test packs for this repair effort; retain the historical evidence
and broader requirements below. Publishing these documents does not change app behavior.

Status: implementation started on `audio-pr2`. Phase 0 checkpointing and initial turn timelines are implemented. Source-PCM measurements and the early Paul isolation runner are now implemented; baseline measurements and phone acceptance remain pending.

Prepared: 2026-09-09. Repository: `battlesbudz/Jarvis-OS-V2`. Working branch: `audio-pr2`, existing [PR #6](https://github.com/battlesbudz/Jarvis-OS-V2/pull/6).

Audit baseline: [`eabf77dbc7d8589247558882bb97efdc9678dc7f`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/eabf77dbc7d8589247558882bb97efdc9678dc7f). Recheck affected code against the branch head before implementing a phase. Findings below describe that baseline, not every future build.

## Outcome and scope

Make local conversation on the Samsung Galaxy Z Fold 6 feel continuous: retain opening words, recognize speech while it arrives, wait appropriately through hesitation, respond promptly, accept ordinary spoken corrections, and keep Paul's voice stable. Conversation memory must reflect delivered speech and completed actions.

The reference is current production voice-agent practice, with emphasis on local inference and primary sources from September 2025 through September 2026. There is no single formal industry standard or universal latency threshold for this experience.

Core constraints:

- Keep the core conversation, recognition, speech, and turn decisions on the phone. Telecom, SIP, phone numbers, and hosted call-center infrastructure are outside scope.
- Retain Gemma's audio understanding alongside ASR. A blank ASR result must not automatically discard confirmed intelligible speech. Sound-only input must not become an invented request. The 2026-09-10 text-first decision below permits selective audio verification; tandem capability does not require processing every recording twice.
- Keep Paul as the preferred voice for this tuning effort. Retain Kokoro as the existing comparison/fallback option. Model selection changes require measured evidence; this document does not switch the saved selection.
- Preserve wake activation, spoken goodbye/stop-listening controls, explicit Stop and Pause, background operation, saved calls, and microphone priority for other apps.
- Gap cues are optional, varied feedback about actual processing. They may recur during a genuine wait; there is no once-per-turn cap. A ready response preempts every cue, including the initial Ummm. Never delay a fast answer to insert filler (user correction, 2026-09-10).
- Preserve EYE VUE routing and phone fallback. Test each supported route separately.
- All user acceptance and diagnostics must work on the phone without ADB or a computer. CI and developer host checks supplement those tests.
- Do not expose an expanded model-tuning workflow in the normal conversation UI. Put technical comparisons in the existing diagnostics facilities.
- Continue in `audio-pr2` and its existing PR. Deliver test APKs with the existing permanent signing identity and increasing version code. Merge only with explicit user approval, using squash merge.

## Baseline findings

Paths in the following table are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/` unless another root is shown.

| ID | Confirmed behavior at the audited commit | Main implementation location |
| --- | --- | --- |
| V1 | Normal capture stops before speculative sealing and possible Gemma transcription. A new microphone is opened for reply listening afterward. | `JarvisRuntime.kt`, `voice/AudioTurnCapture.kt`, `voice/ReplyVoiceCapture.kt` |
| V2 | Reply audio is discarded before a Hey Jarvis/stop match. The same keyword gate remains during the 500 ms post-reply grace period. | `voice/KeywordBargeInAudioInput.kt`, `voice/ReplyInterruption.kt` |
| V3 | Generated reply text is checkpointed and can be marked complete before playback finishes. Current-call context includes incomplete generated entries without delivery metadata. | `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `JarvisRuntime.kt` |
| V4 | Endpointing uses text rules: 350 ms for stable complete-looking text, 1,100 ms while settling, 1,500 ms for uncertainty, and 3,000 ms for hesitation/no text. Whisper overrides the no-text target to 900 ms. | `voice/AdaptiveTurnEnd.kt`, `voice/AudioTurnCapture.kt`, `voice/AsyncWhisperSession.kt` |
| V5 | Capture constructs and closes ASR engines; Moonshine loads model files in its constructor. Each output session constructs and releases native TTS. | `voice/MoonshineStreamingTranscriber.kt`, `voice/AudioTurnCapture.kt`, `voice/SherpaKokoroVoiceOutput.kt` |
| V6 | Changed eligible ASR partials invalidate speculative work. Replacement drafts reset Gemma conversation state and resubmit accumulated audio. Sealing may join cancelled work. | `voice/VoicePreparation.kt`, `JarvisRuntime.kt` |
| V7 | Paul emits native PCM callbacks, but text submissions wait for sentence boundaries. Decoder reset defaults to true per submission; startup headroom starts at 200 ms and can increase to 600 ms. | `voice/PocketTextStream.kt`, `voice/PocketSpeechPolicy.kt`, `voice/PaulPlaybackBuffer.kt`, `voice/SherpaKokoroVoiceOutput.kt` |
| V8 | A 25-second PCM capacity triggers turn completion even if speech continues. | `voice/AudioTurnCapture.kt` |
| V9 | Token-driven state and transcript updates repeatedly serialize the stored call collection. | `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceCallStore.kt` |
| V10 | Whisper runs repeated growing-window batch decodes. Stable partials depend on agreement between results; finalization queues behind native work. | `voice/WhisperTranscriber.kt`, `voice/AsyncWhisperSession.kt` |

Existing strengths to preserve include native PCM callback delivery, bounded audio queues, final-transcript tool validation, speculative invalidation on resumed speech, microphone handoff, and separate filler/reply timing. The bundled Paul opening already avoids regenerating the initial interjection.

Historical documentation contains superseded experiments. In particular, `pocket-tts-paul.md` describes continuous decoder state and no startup wait, while the audited defaults differ. `voice-duplex-and-quiet-speech.md` already marks its continuous-ASR experiment as superseded. Do not use either experiment as proof of current behavior or simply restore it: the previous continuous-ASR approach exceeded the phone's processing budget.

## Context for future sessions — lessons from the Apple local-agent resources

Reviewed 2026-09-09 from the user's supplied links. These are architectural references and proposed experiments, not delivered Jarvis features or Fold 6 benchmarks. The video itself was not supplied or reviewed. Keep the phase status and evidence log authoritative; recheck branch head before implementing.

### What the resources establish

| Resource | Verified scope | How to use it here |
| --- | --- | --- |
| [Core AI](https://developer.apple.com/documentation/coreai) | Apple's runtime targets Apple silicon, with CPU/GPU/Neural Engine execution, specialization, caching, and profiling. | Borrow the separation of model preparation, loading, and inference measurements. Its runtime and `.aimodel` artifacts are not drop-in Android dependencies. |
| [Official Core AI Models](https://github.com/apple/coreai-models) | Apple-maintained export recipes, Python primitives, and Swift utilities; the reviewed integration requirements are iOS/macOS 27+ and Xcode 27+. | Study reproducible export and runtime contracts. An open model's original weights may have an Android-compatible path; that does not make its Apple export portable. |
| [Community Core AI Model Zoo](https://github.com/john-rocky/coreai-model-zoo) | Community conversions with recipes and model-specific source-parity evidence; verification strength and tested hardware vary by model. | Require the exact recipe, preprocessing, artifact, runtime patch, and device behind each result. Apply this method to Paul's upstream-versus-patched comparisons in Phase 6A. |
| [SpeechAnalyzer API](https://developer.apple.com/documentation/speech/speechanalyzer) and [Apple's explanation](https://developer.apple.com/videos/play/wwdc2025/277/) | Asynchronous audio input and result delivery; optional volatile results revise earlier text for an audio range before finalization. | Adapt the audio-range/revision contract to our Kotlin pipeline. This is a design reference, not a way to install Apple's recognizer on the Fold 6. |
| [Foundation Models](https://developer.apple.com/documentation/foundationmodels) | Sessions, structured output, and tools; current APIs also support multiple model providers. | Use session/context ownership as a reference. This framework is not itself a speech recognizer or TTS engine, and choosing it does not automatically guarantee local inference. Apple's system model is not an Android replacement for Gemma. |
| [Kokoro-82M Core AI model card](https://huggingface.co/mlboydaisuke/Kokoro-82M-CoreAI) | Non-autoregressive synthesis; its streaming wrapper returns a chunk per sentence. The card reports approximately 0.75 seconds per utterance on M4 Max using CPU execution. | This is an Apple conversion, not evidence of continuous text ingestion or a speed/quality improvement on our phone. It does not establish a fix for Paul. |

### Terminology future changes must preserve

“Streaming” must identify the layer: microphone PCM input, revisable ASR text, LLM token output, incremental PCM within one TTS submission, or playback of completed sentence chunks. Record both TTS submission count and PCM callback count. Neither callback delivery nor smooth playback proves that generation state persists across sentences.

ASR finalization is also distinct from conversational turn completion: finalizing one recognized audio range does not authorize answering while the user continues speaking. Keep generated text, rendered audio, and confirmed/intelligible spoken output distinct.

### Concrete experiments and acceptance evidence

| Work | Phase | Experiment and gate |
| --- | --- | --- |
| Prompt visible recognition | 0, 5 | Represent provisional and committed text with audio start/end positions and revision IDs. Replace revised ranges instead of appending duplicates; show provisional text promptly without a synchronous checkpoint. Measure capture-to-first-visible-text separately from decoder output and finalization. Test revisions, resumed speech, and stale results after Stop. |
| Independent capture and recognition | 1, 5 | Keep timestamped capture independent of heavy decoding and result/UI work with bounded queues and serialized native model access. Measure backlog, retained first words, and finalization wait under concurrent TTS; asynchronous scheduling alone is not proof that an offline recognizer has become natively streaming. |
| Safe early preparation | 4, 5 | Let stable, meaningful provisional text support bounded preparation; reconcile the final utterance before any tool dispatch. Test corrected names and changed action targets. Preserve Gemma audio fallback for real speech while rejecting sound-only hallucinated requests. |
| Model conversion parity | 6A | Compare the same text/reference/settings through upstream and patched paths; retain artifact hashes, export recipe, phonemization, state policy, sample rate, and actual source/converted audio metrics plus listening results. A numerical match is useful evidence, not a substitute for intelligibility. |
| Warm inference and realistic performance | 0, 1, 6B | Separate model load/specialization from warm inference; repeat with ASR, Gemma, and Paul sharing the phone. Record thermal status, route, memory, and first intelligible answer timing. Desktop or isolated TTS results cannot select a live-call default alone. |

These experiments refine the existing phases; they do not add an Apple dependency or require switching models. Retain Paul as the preferred speaker, Kokoro as a comparison, and the measured Moonshine/Whisper choice. Evaluate candidate Android acceleration/export changes separately only when profiling identifies a relevant bottleneck.

### What remains unproven

- The resources do not demonstrate owner-only voice recognition or reliable suppression of TV/radio speech in Jarvis. Evaluate speaker preference, false accepts, missed user speech, and echo handling separately; early background audio must not be treated as proof of the owner's identity.
- A natural demo does not establish interruption correctness, microphone handoff, long-call stability, or history consistent with delivered speech.
- The Core AI Kokoro card does not prove Apple's port is better than our Kokoro path, and its sentence chunks do not explain every Paul tonal change.
- “Free/open-source models” in a video description does not establish that every framework, model, or export shares the same license or platform support. Check the specific component before adopting it.

Resume work by reading the current phase/evidence tables, checking the selected phone profile and build, and choosing the earliest unmet measurement or correctness gate. Preserve existing implementation evidence and record rejected experiments with their reason so a future session does not restore a known regression.

## Intended architecture

Use small, independently testable owners. The following names describe proposed responsibilities; reuse suitable existing abstractions rather than creating duplicate layers.

| Proposed component | Responsibility | Boundary |
| --- | --- | --- |
| `VoiceAudioSession` | Own one active-call microphone, timestamp frames, retain bounded pre-roll, and distribute ordered input to VAD/ASR/turn handling. | End-of-turn changes consumers; it does not release the microphone. Stop, Pause, handoff, route failure, and call end can release it. |
| `VoiceModelSession` | Own warm recognizers, TTS weights, and Paul voice conditioning with serialized native access. | Utterance state is separate from model lifetime; reset acoustic/generation state deliberately. |
| `VoiceTurnStateMachine` | Coordinate listening, candidate end, processing, speaking, interruption, and external suspension. | Speech-start remains observable during processing and speaking; UI state does not determine microphone ownership. |
| `TurnEndDetector` | Combine VAD, transcript/acoustic completion evidence, and bounded pause policy. | Candidate decisions carry a revision and are invalidated by resumed speech. |
| `SpeechDeliveryLedger` | Map generated text spans to PCM ranges and confirmed playback progress. | Caption estimates are not proof that particular words were heard. |
| `VoiceWorkScheduler` | Budget speculative and optional work around capture, ASR backlog, and audio supply. | No concurrent native calls to the same model; no speculative action execution. |

Every asynchronous event must carry the applicable call ID, turn ID, and generation/revision ID. Late callbacks from a stopped or superseded generation cannot change state, play audio, update history, or dispatch actions.

PCM delivery must remain bounded and observable. Keep microphone reads independent of heavy model work. Do not use a dropping broadcast buffer as an accidental speech-loss policy; count overflows and recover explicitly. Evaluate smaller hardware chunks only if measurements show a benefit, preserving the VAD model's required framing.

## Delivery sequence

Each phase is a reviewable change set on the existing branch. Check a phase complete only after attaching its implementation commit and validation evidence. A phase number is not a request to create another PR.

| Phase | Deliverable | Depends on | Status |
| --- | --- | --- | --- |
| 0 | Baseline measurements and efficient checkpoints | Audited head verified | In progress — checkpointing and initial timeline implemented; device baseline pending |
| 6A | Early Paul source-audio and submission comparisons | 0 — comparable TTS baseline and required export fields | Retired Paul work; Piper profile/source comparisons remain available |
| 1 | Call-scoped microphone and model ownership | 0 | Implemented; sustained lifecycle acceptance partial; Moonshine command reset has load cost |
| 2 | Playback-aware history and cancellation | 1 | Implemented; context budget repaired in latest follow-up; phone acceptance partial |
| 3 | Natural interruptions and seamless follow-up capture | 1, 2 | Implemented bounded probes for both engines; phone interruption acceptance remains open |
| 4 | Local turn completion and long-utterance segmentation | 1, 3 | Segmentation/interface implemented; semantic detector evaluation and device acceptance open |
| 5 | Measured ASR policy and bounded speculative work | 0, 1, 4 | Scheduling/comparisons implemented; accuracy and sustained performance acceptance open |
| 6B | Integrated speech quality and audio supply | 6A, 0, 1, 2, 5 | Piper quality accepted by user; passage latency and integrated acceptance open; Paul/Kokoro removed |
| 7 | Integrated phone acceptance and documentation reconciliation | 0–5, 6A, 6B | Current policy/audit reconciled; full phone acceptance remains open |

Phase 6A runs early once its Phase 0 measurement prerequisites are available; it does not wait for natural interruptions, a new turn detector, or long-utterance support. Preserve the correctness dependencies among Phases 1–5. Repeat Paul tuning under concurrent recognition in Phase 6B before selecting integrated defaults.

### Phase 0 — Establish comparable measurements

Extend existing `AsrCaptureMetrics`, `TtsSessionMetrics`, `TurnLatency`, comparison stores, and in-app copy/export. Reuse existing clocks and IDs rather than adding a parallel metrics system.

- Record audio sample position/capture time, VAD speech end, provisional and committed ASR, endpoint decision, finalization completion, speculation seal, first usable reply text, first generated PCM, first non-silent rendered reply PCM, interruption candidate/confirmation, playback stop, and microphone readiness.
- In annotated listening cases, also record the first intelligible substantive answer word. Keep this distinct from first PCM and first non-silent playback: breathing, filler, or garble must not count as a successful meaningful response. Mark intelligibility timing unavailable when it has not been assessed.
- Associate generated-silence intervals and speech-active level measurements with PCM sample ranges and submission boundaries. Report generated pauses separately from playback starvation; report level differences separately from perceived speaker/timbre changes. Retain the measurement windows and method so phonetic-content differences are visible.
- Record stage intervals on a monotonic clock. Show overlaps; do not sum concurrent durations as if they were serial.
- Count model loads, recognizer resets, discarded/accepted drafts, cancellation wait, input backlog, output queued duration, underruns, and unexpected mic reopen events.
- Separate acknowledgement audio from substantive answer audio. Android playback-head progress is a rendering proxy; report route and volume, and do not label it an acoustic measurement at the listener's ear.
- Record build, commit, model/profile, audio route, prompt/case ID, context size, and thermal status. Partition cold, warm, and sustained-session results; report tool turns separately.
- Keep live transcript updates in memory. Debounce checkpoints outside token delivery and immediately flush accepted turns, completed tools, interruption, Pause/End, and lifecycle boundaries. Serialize checkpoint ownership; cancellation must not permit an old snapshot to overwrite a newer one. Preserve existing saved records and document the bounded loss window for an abrupt process kill.

Primary files: `voice/VoiceCallStore.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceTurnCoordinator.kt`, `voice/AsrCaptureMetrics.kt`, `voice/TtsComparisonStore.kt`, `diagnostics/TurnLatency.kt`, `JarvisRuntime.kt`.

Acceptance: fixtures prove ordered checkpoint updates, final flushes, backwards-compatible reads, and correct metric attribution. A baseline phone session exports one trace per turn, with filler and answer timings separate. Profile token-path persistence with small and large saved histories; record costs without running an unbounded synthetic stress suite.

### Phase 1 — Keep capture and models alive for the active call

Status: implemented; [Android build 646](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34434942391) passed (2026-09-10). Build 646 phone evidence confirms warm reuse, retained capture and clean Stop; sustained-memory, rotation, Pause/Resume and external-handoff acceptance remain pending. See the call-ownership implementation entry below. Phase 2 is implemented; Phase 3 handoff work is now in progress.

- Extract active-call audio ownership from the per-turn path in `JarvisRuntime`. Convert `AudioTurnCapture` into an utterance consumer that can finalize without stopping the call microphone.
- Reuse the same audio session through endpoint finalization, speculative sealing, acknowledgement, Gemma fallback, reply generation, playback, and follow-up listening. Preserve bounded onset audio at every transition.
- Keep recognition and TTS weights warm within a bounded call session. Give each engine one native owner and cancellation-safe release. Separate fresh utterance state from resident weights; do not carry a completed TTS text/EOS state into the next utterance without engine support.
- Verify the Moonshine SDK's stream-reset lifecycle against its pinned version. If an SDK cannot safely reuse a recognizer, retain microphone coverage while replacing only that recognizer and measure the cost.
- Preserve `MicrophoneHandoff` and `MicrophoneInterruptionMonitor`: release promptly for external microphone use, restore the previous passive/active mode when available, and never poll by repeatedly acquiring the microphone. Keep handoff quiet timing distinct from conversational end-of-turn timing.
- Keep Pause and Stop authoritative. Backgrounding and keyboard visibility alone must not change microphone ownership. Existing foreground-service eligibility and route handling remain required.

Primary files: `JarvisRuntime.kt`, `voice/AndroidAudioInput.kt`, `voice/AudioTurnCapture.kt`, `voice/ReplyVoiceCapture.kt`, `voice/VoiceCallService.kt`, `voice/MoonshineStreamingTranscriber.kt`, `voice/WhisperTranscriber.kt`, `voice/SherpaKokoroVoiceOutput.kt`, microphone handoff classes. Proposed files: `voice/VoiceAudioSession.kt`, `voice/VoiceModelSession.kt`.

Acceptance: deterministic tests cover finalization without recorder release, ordered pre-roll delivery, route/handoff recovery, Stop during load, and native release exactly once. On the phone, speak during acknowledgement/finalization and immediately after a reply: retain the beginning without a new ready cycle. After warmup, normal turns do not reload ASR/TTS weights unless a logged recovery or memory policy requires it. Memory remains bounded over a sustained call.

### Phase 2 — Reconcile history with playback

Status: implemented; Android debug/release validation passed in build 648; phone acceptance pending (2026-09-10).

- Store generated text, delivered spans, playback state, and interruption separately. Add backwards-compatible record metadata; older records have unknown delivery precision and must remain readable.
- Associate text spans with output sample ranges before writing PCM. Use completed span boundaries for conservative delivery tracking. If the engine lacks word timestamps, label partial-span delivery as estimated and do not treat the caption's intentional visual lead as spoken content.
- On interruption, snapshot playback progress before flushing the track, cancel generation, reject stale queued audio, and reconcile history before constructing the next prompt. Retain fully delivered spans plus explicit partial/interrupted metadata; exclude the unplayed remainder from conversational context.
- Persist completed action outcomes independently of interrupted speech. Never replay an action because its verbal acknowledgement was cut off. Cancel only work that has not completed and whose execution contract supports cancellation.
- Serialize state changes so generation completion, playback completion, cancellation, and End cannot race into duplicate or incorrectly completed transcript entries.

Primary files: `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceSession.kt`, `voice/VoiceCallStore.kt`, `voice/SpokenCaptionTimeline.kt`, `voice/SherpaKokoroVoiceOutput.kt`, `voice/ReplyInterruption.kt`, `JarvisRuntime.kt`. Proposed file: `voice/SpeechDeliveryLedger.kt`.

Acceptance: interrupt before first PCM, mid-span, between spans, and after generation completes but before playback drains. Next-turn prompts exclude the unplayed remainder; delivered tool results remain available; actions execute once. Saved-call resume and old records remain usable. Tests include late callbacks and output-route failure.

### Phase 3 — Enable natural interruptions safely within the phone budget

Status: in progress (2026-09-10). Build 650 validated immediate follow-up handoff and readiness diagnostics. Bounded natural-correction recognition is now implemented for eligible warm Moonshine sessions; Android debug/release validation passed in build 653, but phone evidence exposed natural-path availability failures. Recovery, prompt result consumption and pre-reply stream reservation are implemented below; replacement Android validation passed in build 655; device acceptance remains pending. Keyword fallback remains available; route/AEC and sustained native-load acceptance are still open.

- Use persistent VAD/ASR and echo-aware evidence to detect ordinary corrections such as “actually, use the other one.” Preserve explicit stop controls and the trained keyword path as a temporary fallback.
- Combine acoustic evidence with recognized content and speaker preference where reliable. A brief backchannel should normally let Jarvis continue; a confirmed request should stop it. Do not add a mandatory pause/probe on every noise candidate.
- Validate Android AEC on phone speaker, supported Bluetooth routes, and EYE VUE. An enabled flag is not proof of cancellation quality. If software AEC is needed, provide the actual playback reference, compatible sample rates, and delay alignment; benchmark its CPU cost before enabling it.
- Do not blindly switch to communication mode: distinguish Jarvis-owned mode from external calls so microphone-priority logic does not yield to itself.
- Preserve correction pre-roll before confirmation. A user must not need to wait for a keyword and then repeat the request.
- On natural reply completion, immediately transition the existing capture into ordinary follow-up listening. Remove the keyword-only 500 ms gap. Account for keyword detector warmup when retaining that fallback.
- Keep input capture and output delivery responsive while optional work is throttled. An unavailable/overloaded classifier must not silently drop a command or pretend the listener is fully ready.

Primary files: `voice/ReplyVoiceCapture.kt`, `voice/KeywordBargeInAudioInput.kt`, `voice/ReplyInterruption.kt`, `voice/BargeInGate.kt`, `voice/AndroidAudioInput.kt`, `voice/SpeakerPreferenceGuard.kt`, `voice/MicroInterruptionKeywords.kt`, `JarvisRuntime.kt`. Proposed file: `voice/VoiceTurnStateMachine.kt` if existing coordination cannot express these transitions cleanly.

Acceptance: quiet-room corrections work during preparation and playback without a keyword; immediate follow-up retains its opening words. Echo, fan noise, clothing noise, and “mm-hmm” do not repeatedly stop a valid reply. Test the user repeating words Jarvis just said, low-volume speech, another speaker, and Jarvis saying “stop.” Verify no duplicated correction, action, or audio after cancellation. Do not restore the old continuous-ASR experiment without passing backlog and sustained-playback checks.

### Phase 4 — Improve turn completion and support speech beyond 25 seconds

- Introduce a `TurnEndDetector` interface. Retain the current adaptive policy as a bounded fallback and test baseline.
- Evaluate Smart Turn's local quantized model first, using its required 16 kHz audio window and preprocessing. Confirm exact model artifact, license, checksum, ONNX operators, native-runtime compatibility, memory, and Fold 6 inference cost before integration. Use existing download/setup conventions.
- Run completion inference at candidate pauses, not on every microphone frame. Discard results if more speech arrives or the revision changes. Use confidence and pause history to choose a bounded wait; keep conservative hesitation handling.
- Separate end-of-turn, post-handoff quiet time, and call inactivity. Preserve the existing 20-second inactivity semantics; processing and playback must not consume the follow-up listening allowance.
- Replace the 25-second submit-on-capacity behavior with bounded ASR segments, accumulated committed text, and indexed recent audio windows. Segment boundaries are not permission to answer or execute tools. Keep unstable overlap separate to avoid duplicate words.
- Keep Gemma audio-aware for long turns using the audio/text inputs supported by the pinned runtime. Do not concatenate unlimited WAV data. If the runtime cannot preserve enough audio evidence for an ambiguous portion, request clarification instead of dispatching a partial or guessed command.

Primary files: `voice/AdaptiveTurnEnd.kt`, `voice/AudioTurnCapture.kt`, `voice/RollingAudioBuffer.kt`, `voice/StreamingTranscriber.kt`, `voice/VoiceTranscriptResolver.kt`, `voice/VoiceCallPolicy.kt`, `voice/FinalVoiceToolGuard.kt`. Proposed files: `voice/TurnEndDetector.kt`, `voice/LocalTurnEndDetector.kt`, `voice/UtteranceAccumulator.kt`.

Acceptance: test incomplete questions, punctuation revisions, numbers, “I mean…”, a 2–3 second thinking pause, and resumed speech during inference. Test 30- and 60-second spoken requests with corrections near segment boundaries. No automatic answer at the old capacity boundary, no duplicate/lost committed text, and no partial action execution. The new detector must improve the latency/premature-cutoff tradeoff on the same recordings; otherwise retain the fallback.

### Phase 5 — Choose ASR and speculation policy from device evidence

- Compare current Moonshine streaming with Whisper using the same spoken cases, including onset retention, quiet speech, names, corrections, and long utterances. Report accuracy alongside first partial, finalization, and total reply latency.
- Treat Moonshine as the primary low-latency candidate. Keep Whisper as a measured option or selective recovery path; do not claim its repeated-window decoding is native streaming or change the user's selection silently.
- Expose provisional versus committed recognition internally. For Whisper, make background queue wait and final decode visible; reuse safe results when possible without pretending unprocessed tail audio was recognized. Preserve single-owner native cancellation.
- Start speculative Gemma work on stable meaningful prefixes/candidate pauses with sufficient confidence. Keep at most one active draft; use revision IDs and the existing final-transcript/action guards.
- Use observed draft reuse, cancellation wait, ASR backlog, queued output duration, and thermal status to throttle optional speculation. Do not repeatedly re-encode the full growing utterance when the useful draft rate is poor. Explore supported context reuse only with bounded memory and correct invalidation.
- Preserve the audio fallback when ASR is blank, while avoiding redundant transcription passes when a usable result exists. Partial or draft text cannot authorize a tool.

Primary files: `voice/AsrEngine.kt`, `voice/AsyncWhisperSession.kt`, `voice/WhisperTranscriber.kt`, `voice/MoonshineStreamingTranscriber.kt`, `voice/VoicePreparation.kt`, `voice/VoiceTranscriptResolver.kt`, `voice/FinalVoiceToolGuard.kt`, `ai/LiteRtLmEngine.kt`, `JarvisRuntime.kt`. Proposed file: `voice/VoiceWorkScheduler.kt`.

Acceptance: controlled speculation on/off comparisons show useful latency benefit without worsened recognition or audio starvation. Test a changing action target, blank ASR with real speech, sound-only audio, failed native cancellation, and thermal slowdown. Finalization cannot wait indefinitely for an obsolete draft. Verify correct call memory after any native conversation reset.

### Phase 6A — Isolate Paul's opening and source-audio inconsistencies early

Use the short-opening evidence below as an investigation baseline, not a quality pass. Run these comparisons after the relevant Phase 0 exports are available, independently of the wider conversation redesign.

1. Generate the exact same complete text as one submission and as the current two submissions. Make all text available upfront in both cases so text-arrival pacing does not confound the submission comparison.
2. Keep the two submissions and all other settings identical; change only reset-per-submission versus supported continuous acoustic state. Record actual native behavior and distinguish cached voice conditioning, LM state, decoder state, and RNG state. Do not carry completed text/EOS state forward without engine support.
3. Compare upstream behavior against the patched native callback implementation using the same compatible pinned model, reference, and generation settings. Verify complete PCM coverage, callback/returned-PCM parity where applicable, text coverage, and cancellation. Host parity is not a phone performance result.
4. Repeat candidate profiles under normal and thermally limited conditions, recording repetitions and separating those distributions. Then restore the original paced-text feed to measure realistic text-wait costs.

Hold reference, seed, temperature, flow steps, playback speed, and startup headroom fixed within each comparison. Begin with the uploaded profile's speed 1.0 and 200 ms headroom; this is an experimental baseline, not a new production default. A short run without underruns does not establish sustained-call reliability.

Required cases: standalone “I understand.”; the exact three-sentence short-opening-v2 text below with its current first-two-sentence grouping; a longer paragraph with early and late sentence comparisons; the bundled “Ummm” alone; and the opening followed by an actual answer. The TTS-only answer export excludes fillers, so it cannot validate the interjection.

For each case:

- Align suspected pauses and level changes with words and submission boundaries. Listen for intelligibility, omitted words, unintended pauses, and speaker identity changes; automated PCM checks alone cannot decide these.
- Preserve the grouped-opening regression protection until shorter submissions demonstrate complete spoken output. The earlier build 621 trace produced only 240 ms for “I understand.”; punctuation or a complete text sentence is not proof of usable speech.
- Do not automatically trim generated pauses or normalize each PCM chunk to conceal a suspected defect. Establish whether the pause is appropriate and whether level changes reproducibly follow state boundaries before selecting a correction.
- Do not increase buffers or alter normal speech speed to address silence already embedded in source PCM. Keep supply problems and synthesis problems separately attributed.

Acceptance: a documented comparison identifies which factors reproduce or improve the failure, includes listening/text-coverage evidence and known limitations, and preserves a rollback profile. Unresolved cause remains explicitly unknown. This milestone does not certify live ASR latency, the bundled opening, or long-call performance without their respective tests.

### Phase 6B — Stabilize Paul under integrated load and reduce meaningful-answer delay

- Keep the bundled opening and substantive answer timings separate. The opening must never overlap answer playback or contaminate answer decoder state.
- Carry forward Phase 6A's measured state policy and repeat the winning comparisons with concurrent recognition and sustained-call load. Recheck PCM/text coverage and cancellation after model-ownership or scheduling changes; do not assume the isolated winner remains best under contention.
- Do not equate stable speaker identity with preserving every decoder state. Keep the state policy that passes acoustic and non-truncation checks; update both code and documentation to describe it accurately.
- Reduce text wait by requesting concise natural opening sentences and testing release of short complete openings only after Phase 6A's intelligibility and non-truncation gates pass. Preserve first-two-sentence grouping when a shorter opening fails; do not trade missing words or garble for an earlier PCM timestamp. Do not split Paul input by arbitrary character counts or claim word-by-word text ingestion from PCM callback streaming. Any clause-level change requires the same listening checks as sentence-level synthesis.
- Tune startup headroom using measured supply. Distinguish AudioTrack capacity from the actual playback start threshold. Never infer a fixed two-second startup delay from a two-second-capacity buffer.
- Keep bounded PCM backpressure and one native owner. If synthesis remains slower than playback, reduce competing optional work; increasing the initial buffer only postpones sustained starvation. Keep normal speech speed unless an explicit comparison setting is selected.
- Evaluate newer Pocket exports only as a separate compatibility/quality experiment. A new upstream release is not automatically interchangeable with the pinned ONNX files or custom patch.

Primary files: `voice/PocketTextStream.kt`, `voice/PocketSpeechPolicy.kt`, `voice/PocketVoiceSpec.kt`, `voice/PaulPlaybackBuffer.kt`, `voice/SherpaKokoroVoiceOutput.kt`, `voice/VoiceResponsePolicy.kt`, `voice/PaulOpeningAudio.kt`, `voice/SpokenCaptionTimeline.kt`, `native/sherpa/pocket-streaming.patch`, `scripts/check_pocket_stream.cpp`.

Acceptance: the same multi-sentence cases retain a recognizable Paul voice without garbled starts, missing/repeated text, or recurring boundary glitches. Verify clean interruption/restart and a long answer while recognition remains active. Record listening results and sustained synthesis throughput on the Fold 6; host speed alone cannot select the default.

### Phase 7 — Complete integrated acceptance and reconcile documentation

- Run the matrix below on a signed update APK, including background and external-microphone transitions. Fix regressions on the existing branch and attach build-specific evidence.
- Review native ownership, coroutine cancellation, event revision guards, tool execution, and stored-history migration together. Re-run only checks affected by a fix or required by CI.
- Update `voice-session.md`, `voice-endpointing.md`, `voice-keyword-interruption.md`, `voice-duplex-and-quiet-speech.md`, `streaming-voice-preparation.md`, `pocket-tts-paul.md`, `voice-playback-buffering.md`, and `per-reply-latency.md` where the implementation changes their claims. Mark historical reports as historical rather than rewriting their measurements.
- Remove superseded production paths once their replacement is accepted and a rollback checkpoint exists. Retain only useful regression fixtures and explicit fallback behavior.
- Record implementation commits, passing checks, phone results, remaining limitations, and the selected default policies in this document. A checklist entry without evidence remains pending.

Acceptance: all correctness gates pass, performance results are reported honestly, the existing PR is ready for review, and the user has a working APK link. Publication of this plan does not authorize merging the PR.

## Measurement goals and acceptance matrix

These are initial engineering goals for short, warm, local, non-tool turns on the Fold 6. They are not industry guarantees, current measurements, or promises for long reasoning/tool calls. Phase 0 establishes the baseline; if a goal is infeasible, record the measured limiting stage rather than hiding delay with an acknowledgement or weakening recognition.

| Measurement | Initial goal or gate |
| --- | --- |
| Last reference speech to first intelligible substantive answer word | Aim for p50 at or below 1.5 seconds and p95 at or below 2.5 seconds in annotated listening cases in the defined warm subset; report endpoint, ASR, model, and TTS contributions. Retain first PCM/non-silent rendering as separate operational metrics; do not substitute them when intelligibility is unassessed. |
| Confirmed interruption to playback pause/flush | Aim for p95 at or below 150 ms at the app/rendering layer; report Bluetooth/route tail separately. |
| User interruption onset to rendering stop | Aim for p95 at or below 700 ms for clear corrections; separately report confirmation delay and false interruptions. |
| Missing opening words / premature endpointing | No failures in the deterministic transition fixtures; list every phone-case failure rather than averaging it away. |
| Playback supply | No recurring underruns or audible starvation in the agreed sustained test; exclude intentional stop and final drain from starvation counts. |
| Paul quality | No garbled starts, omitted/repeated phrases, or unacceptable voice changes in the agreed listening cases. Automated PCM checks alone cannot establish this. |
| Action/history correctness | No duplicate actions, stale-generation execution, or unplayed reply remainder represented as delivered speech. |
| Resources | Bounded input/output/history work, no per-turn model reload without a reason, no progressive memory growth or crash in a 15-minute session. Log thermal effects. |

Use deterministic prerecorded inputs for reproducibility and live speaking for interaction quality. Reference speech-end/onset timestamps must be manually annotated or otherwise independently derived for benchmark clips; a VAD's own timestamp is an operational metric, not ground truth. Collect at least 30 comparable warm turns for preliminary p50/p95 and report the sample count; do not present the tail estimate as a fleet-level guarantee. Use a smaller targeted subset for route and cold-start checks, then repeat only failures or changed paths.

| Case group | Phone scenarios | Required evidence |
| --- | --- | --- |
| Start and follow-up | Cold start, warm call, immediate response after Jarvis, speech during acknowledgement/finalization | Retained first words; mic timeline and model-load counts |
| Pause handling | Finished question, incomplete question, hesitation, 2–3 second thinking pause, revised ending | Endpoint evidence and premature-cutoff count |
| Interruption | Ordinary correction during generation and playback; explicit stop; interruption before first audio and near final drain | Confirmation/stop times, retained correction, playback-aware history |
| Noise and focus | Fan, clothing noise, background speech, quiet voice, backchannels, Jarvis echoing a keyword | False/missed interruption counts and transcript accuracy |
| Long turns and tools | 30/60-second speech, long answer, battery/app request, action target correction | No capacity-triggered action, bounded resources, action completion recorded once |
| Lifecycle and routing | Home/lock screen, Messenger voice recording and return, Pause/Resume, Stop during load/recovery, supported Bluetooth/EYE VUE and phone fallback | Correct mic ownership/restored mode, no restart after deliberate Stop |
| Sustained quality | 15-minute mixed session; same Paul cases with and without concurrent recognition | Audio export when explicitly requested, supply gaps, thermal status, memory, listening notes |
| Persistence | Interrupt, end, reopen saved call, read older records, recover from abrupt termination | Correct migration, delivery metadata, documented checkpoint loss bound |

Raw microphone audio remains bounded in memory for normal use. Benchmark recording/export is an explicit diagnostic action using existing app-private/export facilities; retain no new routine raw-audio history and upload nothing automatically.

## Rollout and recovery

- Preserve a known working signed APK and commit before replacing a major audio path. Keep rollback controls internal to diagnostics/build configuration while evaluating changes.
- Isolate ownership, history migration, turn detection, speculation, and TTS policy changes into reviewable commits. Deploy correctness prerequisites together when partially enabling them would corrupt history or permit stale actions.
- Keep current endpointing and keyword interruption as temporary explicit fallback modes until replacements pass their route-specific tests. Report fallback use in diagnostics; do not present it as natural duplex.
- On backlog or native failure, preserve completed actions and accepted history, cancel the affected generation, and recover the smallest failed component. Never silently discard the start of a command or replay a partly executed action.
- If local completion inference, ASR, and TTS cannot run within the phone's budget, first reduce optional speculative work and classifier cadence. Do not introduce a cloud dependency or another large model as an implicit fallback.

## Evidence and decisions log

For each completed phase, append: phase, commit, APK build/link, device/route, test case IDs, sample count, metrics before/after, acoustic observations, known failures, chosen default, and rollback commit. Keep absolute timing results tied to their build and conditions.

### 2026-09-09 — Phase 0, first implementation increment

Implementation: [`5c67941`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/5c67941ce22e2239b296e1fa5ae05f757d3aca0d), based on `933760a`. Android validation: [build 625](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34412236930): debug and release unit tests/assembly passed, along with native keyword validation, native packaging checks, callback ABI checks, release signature verification, and APK publication. [Signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.625/app-release.apk) / [release details](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.625). Rollback code baseline: `933760a`.

| Item | Implementation and evidence |
| --- | --- |
| Partial transcript checkpoints | `CoalescingVoiceCallStore` coalesces transient updates over a nominal 500 ms window on one background writer. Controller memory still updates immediately. Identical state updates no longer save again. |
| Boundary ordering | Completed transcript updates, call endings, and cleanup flushes submit the latest snapshot immediately. Revision checks reject stale queued writes and prevent deleted calls from being recreated by a pending timer. Microphone handoff schedules its flush on IO so persistence does not block the main callback. |
| Persistence observability | Runtime summaries expose cumulative progress updates, coalesced updates, writes, write duration, and failures. A failed background save remains available for a later progress/boundary retry; it does not spin. |
| Initial turn timeline | `VoiceTurnTrace` uses the existing turn ID and monotonic offsets for capture readiness/release, recognition finalization, preparation seal, audio fallback, reply dispatch/text/audio, confirmed interruption, stop request, and cleanup completion. Existing ASR comparison export includes available stages; diagnostic summaries also cover turns without comparison entries. |
| Focused automated validation | 25 Kotlin/JUnit tests passed, covering new checkpoint/trace tests and existing controller, coordinator, and latency tests. Includes burst coalescing, final-save ordering, stale timers, deletion, in-flight writes, failure retry, lifecycle flush, and late callbacks. This standalone run does not compile the full Android runtime; Android CI is a separate gate. |
| Independent review | Fixed wrapper property typing and moved handoff persistence off the main callback. Review found no further substantive stale-write or lock-order issues. |
| Phone validation | Pending. No latency improvement, acoustic stop time, cold/warm baseline, or route behavior is claimed from unit tests. |

Limitations and next work:

- The existing SharedPreferences backend still serializes the full saved-call collection per actual checkpoint. Its `apply()` submits disk persistence asynchronously: immediate checkpoints are not an fsync guarantee. Abrupt termination can lose the latest partial window plus scheduler/storage delay; 500 ms is a scheduling target, not a proven crash-loss bound.
- Timeline events are operational timestamps. Recognition finalization is not reference speech end; playback-stop request is not physical silence. Missing events remain unavailable, and first reply audio excludes acknowledgement/filler. Queue depth, acoustic/render-stop confirmation, model-load counts, and the remaining Phase 0 metrics still need implementation.
- Generated-versus-heard history, microphone ownership, and interruption policy are unchanged by this increment. Phases 1–7 remain pending.
- Complete the remaining Phase 0 instrumentation and collect the defined phone baseline before marking the phase complete. Then proceed to call-scoped microphone/model ownership and playback-aware history.

#### Phone smoke checks for this increment — not yet run

Use the same selected ASR, Gemma, Paul settings, and audio route for both builds. Keep raw audio export optional; **Copy diagnostics** supplies the new summaries without a desktop or ADB.

| ID | Action | Check and retain |
| --- | --- | --- |
| P0-SAVE | Ask a short question, let the answer finish, end the call, reopen it from Voice Calls. | Final transcript is present once; capture the checkpoint counters and available `Voice pipeline` stage offsets from Copy diagnostics. |
| P0-INTERRUPT | Interrupt a longer spoken answer with the currently supported stop command, then end and reopen the call. | Latest partial text is retained according to current history behavior; canceled timers must not replace the final saved record. This does not validate playback-aware history yet. |
| P0-HANDOFF | During an active call, start and finish a Messenger voice recording, return to Jarvis, then end the call. | Microphone handoff remains responsive; the saved call includes the latest text; note any checkpoint failure diagnostic. |
| P0-RESTART | After normally ending a call, close and reopen Jarvis. Separately try an abrupt process termination during a partial answer. | Completed history survives normal restart; record actual partial loss after abrupt termination instead of assuming a 500 ms bound. |

For each case record build number, phone/OS, route, selected models, pass/fail, and copied diagnostics. These smoke checks do not replace the 30-turn baseline or the full acceptance matrix.

### 2026-09-09 — Paul short-opening-v2 review and plan amendment

Source: user-supplied `jarvis-voice-short-opening-v2-758e3eda-20e9-4b9c-a30d-2937fbdbf4ea.zip`; run `d0a7e071-00e7-438e-a4b8-7bb9c833d355-12`. This is one exported TTS benchmark pass, not a live ASR/Gemma comparison. The export identifies `streaming-voice-state-v5` but does not identify an exact APK build or commit; do not assign it to build 625 or another build by inference. Audio is not added to the repository by this documentation change.

Input: “I understand. I can keep up with what you are saying and process your requests. I am ready when you are.”

| Evidence | Observation and limit |
| --- | --- |
| Profile | Paul, pinned `sherpa-onnx-pocket-tts-int8-2026-01-26`; two threads, speed 1.0, reset true, leading period false, buffer 200 ms. Pass 2; simulated text feed of four characters every 32 ms after model readiness. |
| Conditions | Thermal status 4 at start/end, `thermal_limited=true`. Keep separate from normal-condition latency baselines. |
| Startup and throughput | Model load 1,803 ms; first text to PCM 1,257 ms; first text to playback 1,757 ms; synthesis 4,657 ms for 6,400 ms source audio (RTF approximately 0.728). These are benchmark timings, not last-user-word-to-answer latency. |
| Delivery | Two text submissions, 18 PCM callbacks, no repeated text calls, zero underruns and zero reported supply gaps. The first submission groups the first two sentences; the second starts at frame 124,800 (5.2 seconds at 24 kHz). Delivery callbacks are not independent text regenerations. |
| Generated pause | Approximately 0.89–1.86 seconds is near-silent using 10 ms RMS windows below 0.001 of full scale. This approximately 0.97-second interval exists inside source PCM and inside the first submission, before the 5.2-second reset. Listening/word alignment is still needed to determine whether the pause is inappropriate. |
| Level variation | RMS over 1.9–4.9 seconds is approximately -22.63 dBFS; over 5.2–6.2 seconds approximately -15.49 dBFS, a roughly 7.1 dB difference. Different phonetic content confounds the comparison; this is not proof of changed speaker identity. No clipped PCM16 samples were found. |
| Scope | Source PCM excludes filler, playback gaps, time stretching, and microphone recording. This review used numerical inspection, not verified listening. Neither “Ummm” quality nor perceived tonal consistency is established. |

Decision: split Phase 6 into early isolation (6A) and integrated validation (6B), add intelligible-word timing and source-pause/level attribution, and retain short-opening regression checks. Warm-model reuse and ASR finalization work remain necessary for live latency, but cannot explain a pause embedded in a TTS-only export. No synthesis default or completed-phase status changes solely from this evidence.

### 2026-09-09 — Phase 0 source measurements and Phase 6A comparison runner

Based on the updated plan at `0d8c195`. Initial implementation: [`34bb525`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/34bb525302c76d525f5570a02acfc46ff05b8d57); [`08a0ebd`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/08a0ebd4e68749d34bf32247eefa2df0331d3e10) adds callback sample parity. Android validation: [build 630](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34415858421): debug and release unit tests/assembly, native keyword validation, ASR native packaging, Pocket callback ABI checks, release signature verification, and APK publication all passed. [Signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.630/app-release.apk) / [release details](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.630). Rollback application baseline: [build 625](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.625). No production voice policy or saved selection changes in this increment.

| Work | Delivered behavior |
| --- | --- |
| Controlled submissions | **Compare Paul source audio · 14 runs** in existing Voice and response speed diagnostics runs seven cases twice, reversing order on pass two. Standalone “I understand.”, the exact short-opening-v2 text, and the existing paragraph are covered. Compare one versus two fixed submissions with reset, then the identical two submissions with retained state. All text is supplied upfront after model readiness through the same native callback path; exact text coverage is checked before dispatch. |
| Fixed settings | Two threads, speed 1.0, 200 ms startup cushion, no leading period; pinned Paul reference, seed 42, temperature 0.7, five flow steps, and current native chunk settings. The state comparison changes decoder, RNG, and chunk-startup state together; it cannot identify decoder-only causality. Live first-two-sentence grouping remains protected. |
| Source-PCM attribution | Benchmark-only `pocket_source_pcm` events report per-submission source frame ranges, near-silence intervals, active-window RMS dBFS, and PCM16 rail counts. Windows continue across callback boundaries and restart at submission boundaries. Analysis uses floor(sampleRate / 100) samples per window (240 at 24 kHz), PCM16 divided by 32768, RMS below 0.001 for near-silence, and includes the last partial window. Speech-active windows are an energy proxy, not verified speech. |
| Bounded measurements | Only accumulators are retained; at most 128 near-silence intervals are emitted per submission, with omitted count and complete totals. PCM16 rail counts include both ±32767 and -32768; these are saturation proxies, not proof of float-domain clipping. Interrupted submissions are marked incomplete. Source measurements describe accepted callback PCM; the WAV remains bounded to 180 seconds and trimmed to playback-head progress. Match its exported start/end frames before aligning audio and measurements. |
| Reproducible exports | Existing per-run ZIP includes source analysis in its boundary log and immutable result metadata with planned submissions, input delivery, build version/code, CI checkout commit, device/Android SDK, actual model/reference/vocabulary hashes, generation settings, and native policy provenance. CI checkout SHA may be the PR merge commit; local builds report it unavailable. Hashes are collected once before timed runs, with cancellation checks. This pre-reads model files, so the suite is not a cold-filesystem benchmark; each case still creates a fresh native instance. |
| Callback/returned PCM parity | Benchmarks hash the exact float32 little-endian samples across callback boundaries and compare with the returned array, in addition to existing frame-count/rate checks. Same-length corruption, reordering, missing/duplicate frames, and late callbacks have fixtures. A mismatch fails the run and remains visible in its exported trace. Checks retain no duplicate utterance and add benchmark overhead included in synthesis timing. This verifies the current patched path only; upstream parity and intelligibility remain unassessed. |
| Honest quality fields | First intelligible word timing remains unavailable and intelligibility is explicitly not assessed. PCM energy, source silence, callback delivery, and non-silent playback are not automatically promoted to speech quality or word coverage. |
| Validation | 35 focused Kotlin/JUnit tests passed, covering source-window invariance, source offsets, truncated reports, saturation rails, unavailable intelligibility, paired-text consistency, immutable run metadata/profile isolation, artifact hashing and cancellation. Independent review completed; full Android validation passed in build 630 (linked above). Reviewer corrections: count symmetric PCM16 rails and describe the actual combined synthesis-state reset. |

Phone procedure: end the active call, select Paul in **Voice and response speed**, tap **Compare Paul source audio · 14 runs**, and use **Export this run’s audio + diagnostics** for the relevant cases. The comparison does not apply its experimental settings to calls. Stop remains available and completed records remain saved. Fourteen recordings fit within the existing 24-run cache; older recordings can expire after further benchmarks.

Compare `opening-one-reset` against `opening-grouped-reset`, then `opening-grouped-reset` against `opening-grouped-retained`, with matching pass and thermal conditions. Repeat the paragraph pairs and listen to `standalone-reset` for missing words. Record whether each word is intelligible, suspected pause locations, and perceived voice changes; numerical levels alone are not a verdict. Keep thermally limited results separate. The existing paced profile test restores text-arrival pacing for follow-up comparisons; its natural grouping must be read from its trace rather than assumed identical to fixed groups.

Still pending: phone listening/text-coverage results, normal versus thermally limited comparisons, first intelligible answer annotations, upstream-versus-patched parity, bundled Ummm and opening-plus-answer evidence, and integrated ASR/Gemma load. No root cause or winning state policy is selected by this implementation. Phase 6A and Phase 0 remain open; broader Phase 0 metrics and Phases 1–5/6B/7 remain on the roadmap.

### 2026-09-10 — Live repetition repair and gap-cue correction

Evidence: user-supplied build 630 live-call trace, turn `aa50ba9f-29cb-4928-835c-e1a08e44ad29`. The initial model pass took 5,338 ms (first raw token 4,232 ms); repetition repair then took approximately 29,058 ms and withheld its output until completion. Endpoint-to-first-accepted-text was 35,680 ms. Ten cached “One second.” cues played before the first Paul answer submission. Only one answer submission appears before the user's explicit Stop, and no first answer PCM is logged. This trace does not establish repeated Paul synthesis or explain all profile-dependent sound differences.

User clarification overrides the old always-initial-cue policy: use varied cues during real gaps, allow recurring feedback, and prioritize a ready answer over every filler. The initially proposed once-per-turn cap was rejected and is not implemented.

Implemented in `f7508a0` (streamed repair and gap-only cues) and `8784965` (new-stage cue priority). [Android build 633](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34420702803) passed debug and release unit tests, assembly, and signed release publication. [Install signed build 633](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.633/app-release.apk); its release targets `878496508b477f3f4284e06b9f4e2a5b90bee91e`. Phone validation remains pending:

- `VoiceRepetitionRepair` streams repetition-checked sentences from the read-only retry. It stops at two accepted sentences, 1,600 input characters, or a 10-second generation budget. Tools are disabled and the retry never enters action dispatch. Timeout/Stop discards unfinished text; parent cancellation propagates. The existing native cancellation/terminal-callback cleanup remains mandatory and can add its existing wait beyond the generation budget; this is not a strict ten-second wall-time promise. If nothing usable is accepted, the existing short failure response remains available rather than starting another repair.
- `DelayedAcknowledgement` waits an initial 700 ms gap, then spaces further cues by 3,500 ms after cue playback. Ready answer PCM cancels and joins any active cue, including Ummm, or any pending gap wait. It never waits for filler cache preparation. The initial cue is no longer compulsory.
- Recurring cues rotate “One second.”, “One moment please.”, and “Just a second.” Available stage-specific cues correspond to recorded processing stages: recognizing the recorded utterance, generating an answer, or preparing answer audio. Generic phrases make no fabricated lookup/action/success claims. Adjacent duplicate cached clips are avoided; unavailable clips are skipped or replaced by another available neutral clip.
- Missing clips are prepared and cached on the existing serialized native owner only while no answer text has arrived. Ready token processing wins over queued optional preparation. Optional synthesis uses the stable Java callback and stops at a native PCM callback boundary when answer text arrives; partial filler PCM is discarded and never cached. Paul filler preparation is restricted to before the first answer submission; the answer then initializes its own session. Native code owns one stream, so this restriction must remain to avoid replacing active answer state. Native work is not instantly preemptible before its next callback; playback priority is enforced once answer PCM is ready. Diagnostics report the observed stage and chosen cue. There is no new cloud or telecom dependency.
- Focused Kotlin/JUnit validation: 26 tests passed for fast-answer skipping, cancellation of an active initial cue, missing-cache behavior, recurring variations, stage changes, streamed repair, two-sentence/character limits, timeout suffix removal, Stop propagation, final-only replies, and existing repetition guards. Independent review found and corrected uncancellable optional filler synthesis and control syntax after prose in repair output; full debug and release Android CI passed in build 633.

Phone acceptance pending: repeat a short question (no unnecessary initial filler), the story request and a follow-up that triggers repetition repair (checked text must become available before a long repair completes), a sustained generation gap (varied cues), Stop during a cue/repair, and another-app microphone handoff. Compare timing and quality separately. The original 14-run Paul source-audio comparison remains available; this live-control fix does not select a winning Paul profile or mark Phase 6A complete.

### 2026-09-10 — Preferred Paul profile and measured latency priorities

User listening evidence: build 630 profile `threads-4-native-stream-speed-1.0-reset-true-period-true-buffer-200` was the best of the tested profiles, with a better Ummm and no reported garble in that sample, but remaining voice variation. Preserve this as the user's comparison baseline: four threads, native streaming, speed 1.0, fresh decoder per sentence group, leading period enabled, 200 ms base cushion. This is a user preference, not an objective quality winner or a newly changed global default.

Evidence: text run `06292b4f-418e-4de3-a401-e55410947b3c` and live call `a231baea-138c-4d79-88d3-a6b82cb36eb4`. These are build 630 measurements, before the gap-cue/repair correction above.

| Observed cost | Evidence | Interpretation |
| --- | --- | --- |
| Final recognition to first accepted answer text | 5,805 ms and 4,996 ms in the two complete latest turns | Largest measured post-recognition delay; first raw token alone takes roughly 3.8–4.7 seconds, followed by about 0.9 seconds to checked sentence publication. Prepared and native TTFT use different origins and must not be added. |
| Accepted text to first PCM | 812 ms and 811 ms | Paul startup is material but smaller than answer generation. |
| First PCM to confirmed playback | Approximately 766 ms and 553 ms from TTS summaries | Includes 454 ms and 402 ms of explicit cushion plus playback setup. |
| Final recognition to first answer playback | 7,405 ms and 6,381 ms from monotonic stage offsets | Compare with speech-end-to-playback of 9,583 ms and 7,345 ms; additional recognition/endpoint work occurs before finalization. Do not add model load or cumulative checkpoint totals again. |
| Sustained synthesis | Standalone 28,838 ms for 29,360 ms audio (RTF 0.982); latest short live reply 3,861 ms for 2,960 ms audio (RTF 1.304) | The standalone run has little throughput margin; live short-utterance startup and contention can matter. These are different workloads, not a controlled slowdown measurement. Standalone thermal status was 4 throughout and flagged limited; live thermal status is absent. |
| Audible opening | First two live PCM chunks total 640 ms, with peaks approximately 0.0085 and 0.0053; the next chunk peaks at 0.513 | Playback confirmation is not first intelligible speech. Measure leading quiet audio before claiming perceptual latency; low peak alone does not prove silence or justify trimming speech. |

Source-confirmed causes and next experiments (not yet implemented):

1. **Measure and reduce repeated Gemma work while preserving the ASR/Gemma tandem.** `JarvisRuntime` sends audio plus text to `generateAudio` even after successful Moonshine recognition; `gemmaTranscriptionFallback=false` only excludes the separate fallback transcription pass. Speculation resets the native conversation and sends updated audio/history again. This trace starts drafts at 18, 50, 73, and 129 characters; the final draft begins only about 392 ms before final recognition. Instrument audio duration, time in queue, conversation reset, inference startup, and draft reuse/discard costs. Benchmark a more selective stability policy and shared-prefix reuse only where native APIs support it. Prompt character counts exclude the audio workload. Do not silently replace the required tandem with transcript-only inference.
2. **Make selected versus effective buffering explicit before reducing headroom.** `SherpaKokoroVoiceOutput` applies `PaulPlaybackBuffer.target(base)` in live calls but bypasses adaptation in benchmarks. The shared adaptive buffer adds 100 ms after observed starvation and removes 50 ms after a clean turn, with a 600 ms target cap. Thus the selected 200 ms base became 500/450/400/350 ms in these live turns. Export base, adaptive increment, effective target, and adaptation reason together; scope adaptation by relevant profile/route. Benchmark PCM-supply-aware release against the timer. The 2,000 ms AudioTrack capacity is not a compulsory two-second delay. A single drain-only underrun is not evidence of audible mid-answer starvation.
3. **Measure first speech and preserve voice quality.** Add a conservative source-speech onset metric alongside first PCM and playback confirmation, and compare period-on/off using matched text and thermal conditions. Do not blindly strip the first 640 ms, lower synthesis steps, increase playback speed, remove the leading period, or alter decoder policy based on this trace. Thread-count and thermal comparisons need matched sustained live workloads, not only isolated text runs.
4. **Reduce repeated model setup only with safe ownership.** Paul reloads for each live turn (about 2.1 seconds here), mostly overlapping microphone capture. Reusing a model across turns may reduce CPU/memory churn, but is not an automatic two-second reduction in post-speech latency. Retain per-submission decoder isolation and mandatory cancellation cleanup; benchmark contention and memory before adopting session reuse.
5. **Track response correctness separately from speed.** The reply “What changed in the latest follow-up?” closely echoes wording in `VoiceResponsePolicy`; this suggests instruction leakage, not proven acoustic corruption. The claimed “Help us location” is unsupported by any action result. Evaluate prompt simplification and conversational acknowledgements against repeat/replay, correction, story, and ambiguous-transcript cases. Do not interpret this call's ASR transcript as verified intended user speech.

Acceptance still pending: repeat the preferred profile under cooler and sustained conditions, compare the same questions before/after the current build, and record speech-end-to-first-intelligible-answer, streamed repair latency, mid-answer gaps, voice consistency, and response relevance. No speedup figure is claimed from source inspection alone.

### 2026-09-10 — Subsecond Gemma target and spoken tool progress

Status: approved direction added to the plan; not implemented or benchmarked by this documentation update. Supplements Phases 0, 1, 5, and 6B. Preserve the current Paul comparison baseline. The user's earlier approximately 0.53-second Gemma first-token benchmark is a reference to reproduce, not proof that the complete voice workload already meets that latency.

**Target:** under 1,000 ms from a ready text request to Gemma's first nonempty answer token in a warm, ordinary conversational turn. Record queue wait and native generation TTFT separately, and report median/p95 with prompt size, audio duration, model/runtime identity, thermal state, and concurrent workloads. Tool progress or filler does not count as the first answer token. Continue measuring speech-end-to-first-intelligible-answer independently: recognition, sentence commitment, synthesis, quiet PCM, and playback can add latency after inference begins. Cold starts and long-context/tool turns must be reported separately rather than hidden in the target.

Implementation sequence:

1. Reproduce the earlier text-only benchmark inside Jarvis with matched model/backend/settings and a warm engine. Confirm the benchmark provenance before comparing numbers.
2. Add costs incrementally: representative conversation history, audio input, active ASR/TTS/interruption listeners, then speculative revisions. Attribute time to request queueing, context preparation/reset, audio processing where observable, first token, checked sentence, first PCM, and first speech. Do not label the whole five seconds as text decoding or infer unsupported native substage timings.
3. Make reliable ASR text the normal fast input to response generation. Use Gemma audio understanding selectively for blank, unstable, ambiguous, or conflicting recognition and speech/sound requests requiring audio. Establish evidence-based uncertainty signals; do not assume Moonshine provides calibrated confidence. Retain the recording until the turn is resolved. This explicitly permits a text-first path while preserving the required Gemma/ASR tandem and empty-transcript fallback.
4. Reduce cancelled drafts, repeated audio/history processing, and unnecessary conversation resets. Keep expensive model state warm under a single safe native owner; reuse context only where the pinned runtime supports it. Do not create two concurrent Gemma instances by default. If verification changes the request, invalidate stale drafts and queued speech; never dispatch a device action from an unvalidated speculative transcript. Already delivered speech cannot be silently retracted.
5. Benchmark against build 630/633 with matched questions, ordinary context, cooler starts and sustained calls. Record observed gains and accuracy regressions before marking the target achieved.

**Natural tool progress is a required part of the voice design.** Tool execution should run asynchronously while short, grounded progress text is delivered to the same speech pipeline. This can overlap lookup I/O with speech without requiring two Gemma engines. Do not assume the pinned Gemma API continues decoding after a structured tool call: finish that generation at its supported boundary, dispatch the tool, emit progress from actual runtime events, and resume grounded answer generation when results arrive. Optional model-written wording must be bounded, read-only, and must not delay the lookup or the final answer; concise event-based wording is the dependable baseline.

| Runtime evidence | Permitted speech | Release condition |
| --- | --- | --- |
| A lookup is selected and about to be dispatched | “Let me check that.” | A genuine wait exists; omit if the answer is already ready. |
| Wikipedia lookup actually starts | “I'm checking Wikipedia now.” | Matching tool-start event for the current request. Never say this for a local-only lookup. |
| A local knowledge-base lookup actually starts | “I'm checking my saved information.” | Matching local lookup-start event. Do not imply network activity. |
| A result is received and usable | “It looks like…” followed by supported findings | Relevant result is available and the spoken claim is grounded in it; do not invent findings while waiting. |
| Lookup fails or finds nothing useful | A concise truthful failure/no-result explanation | Actual failure/no-result event; no false completion claim. |

All examples are optional wording, not an obligatory script. Coalesce redundant events, vary wording during genuine waits, and allow recurring useful updates rather than a once-per-turn cap. A ready answer takes priority over pending progress and can cancel current cue playback. Use one serialized speech queue so progress and answer audio never overlap or duplicate. Bind every event, tool result, and speech item to the turn/request revision; Stop, interruption, or a corrected request must cancel or discard obsolete work. Record spoken progress separately from delivered answer content and confirmed tool outcomes so it does not pollute answer memory or latency measurements. Existing final-intent/action authorization rules remain authoritative; routine progress must not introduce extra user confirmation prompts.

Acceptance cases: instant result (no unnecessary preamble), slow local lookup, slow Wikipedia lookup, multiple tool steps, timeout/failure, result arriving during progress speech, Stop and correction during lookup, and stale results after a new turn. Verify natural continuity, truthful source/action wording, no premature factual claims, no progress/answer overlap, and no duplicate tool execution. Preserve phone-only diagnostics and the offline conversational path; Wikipedia remains an explicitly network-dependent tool.

### 2026-09-10 — Warm Gemma input-cost comparison implemented

Implemented in `f784e86` and validated by [Android build 636](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34424197963): a new **Compare Gemma text vs audio** action in the existing voice diagnostics dialog. It reuses the existing Gemma benchmark lifecycle and exclusive model-operation guard. Finish a spoken turn and end/disarm the call before starting. The three modes are a short text request, the request with captured conversation context, and the exact same contextual prompt plus the captured audio. Three passes rotate the first/middle/last position of every mode. The same GPU Gemma engine stays loaded; each measured case gets a fresh conversation, with tools unavailable and no executor or speech playback. Text and audio warmups are outside measured requests. Live routing and the applied Paul profile are unchanged.

The latest eligible spoken input is retained as one process-local sample, bounded to 1 MiB audio, 4,000 transcript characters and 32,000 contextual prompt characters; invalid/oversized samples are rejected rather than truncated into mismatched text/audio. The sample is replaced on the next eligible turn and cleared when voice diagnostics start a new session or the process exits. Audio is not written to preferences or exported. Benchmark results persist generated output, prompt/audio hashes, input sizes/duration, source turn and capture time, runtime/model/build provenance, model loading, conversation reset, thermal start/end, process memory, raw-text TTFT, filtered first answer text, checked sentence time, and SDK submission/first-message-callback timings. The copy controls export the selected result.

The target flag uses filtered first answer text under 1,000 ms, and is unavailable if there is no accepted answer, a tool call, or detected repetition. Raw SDK text and first native callback are explicitly separate: callbacks can contain control/tool output. SDK submission time is not labeled audio-encoder time. Unobservable encoder internals and live request queueing remain unavailable. The short-text case is a reproducible in-app baseline, not a claim to reproduce the exact earlier OGAM prompt/configuration. Three passes are exploratory; 30+ comparable phone turns are still required for the plan's preliminary p50/p95 gate.

Stop propagates through native cancellation and cleanup. Each measured generation has a 30-second budget plus the existing native cleanup wait; an inference failure is saved and ends this comparison rather than reusing that native instance. Model initialization/warmup errors are also saved. Existing acceleration comparisons remain available. Live inference diagnostics now record actual text/audio/image input mode, SDK submission duration, and first native callback without adding an inference pass.

Validation: four focused Kotlin/JUnit tests passed for sample bounds, independent ownership of captured bytes, exact text/audio pairing, and rotation of case order. ECG-guided source review checked native ownership, cancellation, input pairing, metric scope, model-operation guards and UI integration. Full debug and release unit tests, assembly, native packaging and signing checks passed in build 636. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.636/app-release.apk); the release targets `f784e86c7b0b9888680920be9c9c57cee91b0202`. No subsecond result, overall speedup, or phone quality acceptance is claimed yet.

Next: run the new comparison on the Fold 6 to distinguish contextual prompt and audio costs; then implement the approved selective audio/text-first and speculation policy with those measurements. Concurrent spoken tool progress remains planned, not implemented by this increment. Pipecat is a coordination reference; the production implementation remains Kotlin with Gemma-native tool requests and existing tool executors. Source references verified in this session: [Pipecat's actual task and intermediate-result implementation](https://github.com/pipecat-ai/pipecat/blob/main/src/pipecat/services/llm_service.py), [its function-calling examples including speech from a handler](https://docs.pipecat.ai/pipecat/learn/function-calling), and [Google's Kotlin manual tool-calling and streaming examples](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md#manual-tool-calling). These establish reusable coordination patterns and runtime building blocks, not a verified complete Gemma E2B/Android/Paul implementation or a Fold 6 performance result. Inspect version compatibility before adopting an API from current upstream into the app's pinned LiteRT-LM 0.12.0.

### 2026-09-10 — Compact voice prompts and shorter answer openings

Implemented in `81ccd60` and `239a00b`; [Android build 639](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34428025214) passed. Phone verification is pending. Build 636 matched comparison `24e24c97-bafd-4c22-9dbe-0dbc49108869`, repeat 3, showed first answer text at 367 ms for 143-character SHORT_TEXT, 2,880 ms for 6,370-character CONTEXT_TEXT, and 3,499 ms for the same contextual prompt plus 7.4 seconds of audio. Checked text arrived at 2,144 / 4,084 / 5,283 ms respectively. All three cases reported thermal status 4 with ASR/TTS idle. The observed context difference was 2,513 ms and the added-audio difference 619 ms; these single-pass differences are diagnostic evidence, not guaranteed additive savings. Subsecond warm short-text generation is demonstrated; full-context voice has not met that target.

The production voice path now builds one compact policy instead of combining the generic chat instructions with another long voice policy. Live, speculative, captured comparison, factual retry and repetition-repair prompts use the same builder. Voice instructions are included in prompt budgeting and reported prompt length, rather than appended after those calculations. For the same empty-history dog request, the actual builder produces 998 characters versus 2,976 previously (1,978 fewer, approximately 66%). This fixture measures prompt overhead, not the user's full 6,370-character captured prompt or a measured latency gain. Historical live promptChars omitted the appended voice policy, so cross-build length comparisons must account for that change.

Compact context headings and exact duplicate topic-anchor removal reduce additional input without lowering the existing recent-history, summary or context budgets. An older topic outside the recent window, or details absent from the truncated recent entry, remain included. Recall, pending story acceptance, current corrections and verified tool evidence remain represented. The voice policy asks for a complete 4–8-word answer opening, then requested detail, and starts stories directly. This targets the measured delay between first token and checked sentence while retaining sentence-based repetition checks and Paul's natural sentence input. Model compliance and audible improvement require phone testing; no arbitrary mid-sentence release was introduced.

Validation: eight focused Kotlin/JUnit tests passed for context preservation, exact duplicate removal, long/older topic retention including duplicates beyond the context cap, seed omission, recall/story handling, verified results, and separation from ordinary text-chat policy. Source review checked all voice prompt call sites and removed external voice-policy appends. Android debug and release unit tests, assembly, interruption keyword verification, ASR native packaging, Pocket callback ABI and release signing checks passed in build 639. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.639/app-release.apk); its release targets `239a00be91e3bc075b46798fa666b4560cc85ccd`. Audio routing, native conversation ownership/reset behavior, history budgets, gap-only answer-prioritized cues and the Paul profile are unchanged. This patch adds no inference pass. Selective audio routing, safe context reuse and asynchronous spoken tool progress remain separate planned work.

Phone acceptance: repeat the same dog question and captured text/audio comparison; compare first answer, checked sentence and first intelligible speech separately. Also check a correction, a recalled detail, a multi-turn story, a verified phone action and a lookup. Do not mark the subsecond full-context target or voice-quality gate complete until these are measured.

### 2026-09-10 — Early grounded sentences and inter-sentence gap cues

Implemented in `58024b6` and `2a75584`; [Android build 642](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34431698546) passed. Phone acceptance is pending. Build 639 call `c0740b6f-efae-4499-a01d-810989068665`, rock-and-roll turn `b2f0e918-cfd7-4b53-be84-173fdbf2b388`, exposed a full-answer hold on factual/reference responses. Although the lookup succeeded, the first checked text was released only after the 8,315 ms generation finished. The input contained 7,754 prompt characters and 380,844 audio bytes; native first token took 5,349 ms. Endpoint to first checked text was 13,352 ms, and reported speech-end to playback was 18,064 ms. A short answer alone cannot remove input processing, lookup or endpoint delays. The logs do not expose every substage needed to attribute all of that time.

With reference evidence already supplied and no current action intent, voice answers now publish complete sentences through the existing protocol/repetition checks as generation continues. Each sentence also passes the existing knowledge-gap phrase filter. Unverified local-factual drafts still require their existing verification/fallback path; tool results retain their execution gates. Once sentences have been delivered, a later knowledge-gap fragment is suppressed without replaying or replacing that delivered answer. If all draft sentences are rejected, the final safe fallback can still be delivered. This is evidence-conditioned streaming with the existing phrase checks, not a new semantic fact verifier or a guarantee against hallucination.

Pocket now releases a complete short opening even below four words, instead of joining it to the next sentence. Subsequent short sentences keep their grouping policy. Gemma remains one generation stream; Paul starts the opening while subsequent text is generated. No second Gemma pass, extra model instance or arbitrary word/token-based TTS submission was added.

A boundary marker on the bounded native PCM queue lets the single playback writer distinguish a completed sentence group from temporary starvation inside a sentence. After previous answer PCM has actually drained, 600 ms of genuine waiting can trigger cached `Ummm...`; recurring gaps may cue again after 3,500 ms. Native synthesis and Gemma continue while the cache plays. The writer cancels and joins the cue before writing newly available answer PCM. A ready chunk, channel completion, Stop, pause/interruption or producer failure takes priority; cues never mix with answer playback, never require native synthesis, and are excluded from answer text, PCM trace, captions and answer-frame counts. Cue playback time is excluded from the supply-gap estimate. Existing underrun counters can still include an empty track at a sentence boundary/drain, so they are not a count of audible defects. Explicit `sentence_gap_filler` / `sentence_gap_filler_finished` events identify these waits. PCM completion is published before native release so model cleanup cannot trigger a trailing cue. Benchmarks remain filler-free.

Initial gap cues now prefer the cached requested `Ummm...` before later stage/variation cues. The 700 ms initial gap threshold and answer priority remain. In the supplied log, `Ummm...` started late, shortly before first answer PCM, without a speech-frame-rendered confirmation; audibility cannot be inferred from the playback-start event alone. The new preference does not force an um ahead of an already-ready answer.

Limitations: inter-sentence cues cover real pauses; they do not insert words into mid-sentence underruns or manufacture pauses to finish a filler. The supplied Paul run synthesized 7,040 ms of audio in 8,317 ms with 248 ms estimated supply gaps. Sustained synthesis slower than playback still needs performance/headroom work. The full-context first-token target, acoustic clarity and continuity remain phone acceptance gates.

Validation: 32 focused Kotlin/JUnit tests cover gap timing after drain, immediate-ready and completed streams, no mid-sentence cues, repeated waits, cancellation/producer failure cleanup, short-opening release, sentence filtering/fallback, repetition handling and initial recorded-um preference. Android debug/release unit tests and assembly, interruption keyword models, ASR native packaging, Pocket callback ABI and release signing checks passed in build 642. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.642/app-release.apk); the published release targets `2a7558404c3b37799c9bbff9a63d1f86689fa6e9`. Phone checks: short answer without filler, longer answer with a deliberate genuine sentence gap, the same reference-backed question, Stop/interruption during an um, and no duplicate or clipped words when the next PCM arrives. Inspect first checked text and first answer playback independently from filler events.

Open evidence-dependent decisions:

1. Smart Turn artifact/runtime suitability and whether it improves real turn completion on the Fold 6.
2. Effective AEC and natural-interruption policy per supported audio route.
3. Moonshine versus Whisper accuracy/latency tradeoff for the user's speech and room.
4. Safe model/session reuse supported by the pinned native APIs.
5. Paul's decoder-state policy, short-opening release, and minimum reliable startup headroom.
6. Speculative reuse threshold and sustained resource budget.

These are implementation experiments with defined gates, not blockers to beginning Phase 0 or requests for the user to choose internal algorithms.

## Primary references

Publication dates below identify recent research. Continuously updated documentation is labeled separately. Research results and laptop/cloud timings are not Fold 6 benchmarks.

| Source | Date/type | Use in this plan |
| --- | --- | --- |
| [Moonshine v2](https://arxiv.org/abs/2602.12241) | 12 February 2026, research | Streaming recognition designed for edge-device latency constraints. |
| [LTS-VoiceAgent](https://arxiv.org/abs/2601.19952) | 26 January 2026, research | Semantic triggering and incremental reasoning as alternatives to wasteful speculative restarts. |
| [FastTurn](https://arxiv.org/abs/2604.01897) | 2 April 2026; revised 13 July 2026, research | Combining acoustic and streaming semantic evidence for turn decisions. |
| [X2Streaming-TTS](https://arxiv.org/abs/2608.18661) | 19 August 2026, research | Incremental text commitment and acoustic continuity; research reference, not an approved Android dependency. |
| [Smart Turn](https://github.com/pipecat-ai/smart-turn) | Current project documentation reviewed 9 September 2026 | Local audio completion candidate; quantized model and input-window contract. |
| [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) | Current upstream documentation reviewed 9 September 2026 | CPU synthesis, streaming output, and retaining model/voice state. |
| [LiveKit turn handling](https://docs.livekit.io/agents/build/turns/) | Current production documentation reviewed 9 September 2026 | Turn completion, interruption handling, and backchannels as behavioral references. No LiveKit/telecom dependency is required. |



### 2026-09-10 — Phase 1: call-owned capture and resident models

Implemented:

- `VoiceAudioSession` owns one hardware reader across ordinary command/reply handoffs. Recognition finalization detaches its consumer without releasing the recorder. Raw PCM stays in a bounded six-second in-memory ring. Handoffs replay only unconsumed frames; after completed answer playback, the next command can replay the post-playback tail even if the keyword listener consumed it during cleanup. A buffer overflow rejects the incomplete command instead of silently executing a clipped transcript. Gain processing receives copies and cannot modify the retained raw audio.
- `AudioTurnCapture` stops consuming immediately when its endpoint completes. The new `capture_consumer_released` timeline stage describes the returned utterance consumer; historical `capture_released` records remain readable. Actual hardware release continues to use `Microphone: capture_released` diagnostics. Borrow/return logs explicitly report `recorderRetained`.
- `VoiceModelSession` and `CallModelSlot` retain selected ASR and TTS weights with an exclusive native lease. Pocket/Kokoro synthesis uses one call-owned executor; each answer retains fresh utterance/segment state and existing Paul conditioning/reset policy. Diagnostics and benchmarks retain isolated engine instances. Configuration changes, inference failures and cancelled TTS invalidate resident resources; call end releases them after borrowers finish.
- Moonshine 0.1.5 reuses loaded weights with a fresh explicit `createStream` / `startStream` / `stopStream` / `freeStream` lifecycle and fresh application transcript state per utterance. The pinned [Maven SDK source archive](https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5-sources.jar) confirms those APIs, but retains a private completed-line map across streams. The pool therefore rotates the recognizer after eight leases, logged as `bounded_sdk_cache`; blank-result recovery also replaces it. This is a bounded reuse policy, not a claim of indefinite SDK cache reset. Whisper retains its recognizer while creating fresh per-utterance workers and decode streams, joining pending native work before returning its lease.
- Pause keeps the current conversation and releases the microphone. Stop and external microphone interruption explicitly close the hardware session; existing availability gating restores listening after handoff. AEC remains requested throughout the retained session, including command listening. Native model release waits for active calls to finish; capture release runs independently so a slow native cleanup need not retain the microphone.

Validation: 49 focused Kotlin/JUnit tests pass with assertions/coroutine stack recovery enabled, including existing turn capture coverage and 13 ownership tests. New cases cover finalization without hardware release, ordered handoff, post-playback onset replay, immutable retained PCM, overflow rejection, external-close cause propagation, competing-reader rejection, cancellation, exclusive model reuse, bounded/configuration rotation, failed construction and Stop during a blocking load with exactly-once release. Initial CI exposed an exception-identity assertion that was incompatible with coroutine stack-trace recovery; the test now checks the preserved exception type and message. Full Android debug/release unit tests and assembly, interruption keyword models, ASR native packaging, Pocket callback ABI and release signing checks passed in [build 646](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34434942391). Implementation commits: `2e65328`, `5b98a1b`, `e1f0b2c`. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.646/app-release.apk); the published release targets `e1f0b2cd9040129c84bfd4836d709e3e6ccc9c3e`.

Phone acceptance remains required: sustained multi-turn calls with `reused=true` after warmup; logged Moonshine rotation without lost onset; speech during sealing and immediately after answer playback; Pause/Resume, Stop during load, external microphone handoff and supported route changes. Check sustained memory and AEC acoustically on each route. This phase retains the keyword interruption gate; ordinary keyword-free interruptions and playback-aware conversation history remain Phases 3 and 2 respectively. Six seconds bounds transition buffering; it does not promise arbitrary speech retention throughout a long answer or make native inference instantly cancellable. No latency reduction has yet been measured on the phone for this change.


### 2026-09-10 — Build 646 phone acceptance evidence and remaining regressions

User reports more stable speech. Call `4016fb2a-293a-4eb7-a261-6709f852baa0` confirms warm Moonshine/Paul reuse through uses 2–5, TTS load 0 ms, retained microphone consumers and orderly Stop (hardware release about 85 ms; models released within 238 ms). The story turn reached first Gemma token in 965 ms, first checked text in 1,462 ms after endpoint, and answer playback in 2,544 ms after speech end. Birds playback still took 5,407 ms. First text to PCM was 257–301 ms and to playback 592–643 ms. These are playback/frame measurements, not measured first intelligible words.

Profile: `threads-4-native-stream-speed-0.9-reset-true-period-true-buffer-200`. Keep that exact profile in comparison provenance; 0.9× playback adds headroom, so do not attribute all improvement versus 1.0× runs to code. The story's one underrun appeared at final drain with zero estimated supply gaps; distinguish terminal drain increments from underruns during speech. No repeated TTS submissions were reported.

Open regression cases: unexplained ASR fragment `you` (source unknown), 2.44-second ASR first partial, roughly three-second keyword readiness after capture is ready, long-call memory and eight-use Moonshine rotation, Pause/Resume and external mic/route handoffs. Preserve these checks through Phases 2–3. Phase 2 remains playback-aware history; ordinary interruption readiness remains Phase 3. Do not mark all Phase 1 acceptance complete from this one call.


### 2026-09-10 — Phase 2: playback-aware conversation history

Implemented in this increment:

- `SpeechDeliveryLedger` associates submitted text segments with continuous answer PCM frame ranges before enqueue. Pocket callback chunks extend the same segment; only a sealed segment whose end has passed the actual playback head counts as fully delivered. Kokoro/prepared PCM uses its complete segment range. Caption estimates, fillers, queue length and wall-clock time do not establish spoken words. Partially played segments retain their index/frame metadata; their exact words remain unknown and are excluded from prompt text.
- Each reply has a stable ID, generated text/completion, separate delivery status, frame spans, revision and independent tool receipts. Generation completion no longer marks a tracked reply complete before playback. Playback snapshots occur before interrupt flush; failure cleanup pauses the track before its final snapshot. Terminal ledgers reject late PCM and progress updates. Store updates target the original call/reply IDs, reject stale/terminal delivery updates and never recreate a deleted call.
- Active-call, recent-call and saved-call-resume context uses completed spoken segments plus interruption metadata. Unplayed generated text stays available in the saved record, visibly labeled as generated when playback is incomplete. Old records remain readable with unknown delivery precision; old incomplete assistant drafts are excluded from prompts. Resume refreshes the saved record after audio retirement so a stale screen snapshot cannot override the final delivery receipt.
- Mobile action receipts are captured inside the synchronous execution block, before cancellation can discard its return during dispatcher handoff. The receipt preserves the actual tool result independently of audio; it does not execute or resume an action. Future prompts can see completed results even when the acknowledgement was never spoken. Existing final-transcript tool validation and one-call execution paths remain in place.
- Pending lookup confirmation is reconciled with delivered speech after cleanup. An unspoken generated offer cannot arm a later “yes.” Checkpoints update on speech-segment/state transitions rather than every playback tick.

Validation: 38 focused Kotlin/JUnit tests pass with assertions enabled, including 17 new cases for no-PCM interruption, partial/between-segment delivery, generation versus playback completion order, route failure, stale callbacks, immutable terminal snapshots, archived/deleted call identity, action receipts, saved-call refresh, JSON/legacy compatibility, malformed-metadata rejection and unspoken/spoken lookup offers. Full Android debug/release CI passed, and the signed APK was published as [build 648](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.648), from source commit `8456b034d3beff48de17c8acfab4c364f96b9ef2`. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.648/app-release.apk). Phone acceptance remains pending for interrupted playback and saved-call resume; frame delivery does not establish exact spoken words or acoustic audibility.

Phone acceptance: interrupt a long answer before first audio, mid-sentence, between sentences and after generation finishes but before audio drains. Ask what Jarvis said and inspect the saved call; unplayed text must not be treated as spoken. Execute an app/volume/battery request and interrupt its acknowledgement: the tool result must remain available without automatic re-execution. Resume the saved call and compare its context. Repeat with Paul at the established 0.9× profile; retain Kokoro compatibility. Frame delivery is not proof of acoustic audibility or word-level timing. Natural keyword-free interruption and faster keyword readiness remain Phase 3.


### 2026-09-10 — Phase 3 first increment: immediate follow-up and honest readiness

- Removed the fixed 500 ms keyword-only continuation wait after a reply completes. An already confirmed correction still wins a simultaneous reply completion and is retained once; otherwise the listener returns its microphone lease immediately so ordinary follow-up recognition can begin after required cleanup.
- Kept the existing playback-end timestamp and retained raw-PCM replay. Audio already consumed by the keyword listener after playback ends, plus audio arriving during cleanup, reaches the next command reader without reopening the recorder or replaying pre-boundary reply audio. Added handoff duration, replay duration and first retained timestamp diagnostics. This removes a deliberate wait; it does not claim zero cleanup/model setup time or preserve speech that started before the playback-end boundary.
- Replaced the ambiguous `barge_listener_ready` message with `barge_capture_ready`. Keyword readiness now separately reports model construction time, actual input audio processed, elapsed warmup, maximum inference work and maximum observed backlog. A listener ending before warmup explicitly reports `ready=false`; neither capture nor keyword readiness claims natural-speech recognition readiness.
- Preserved the current Paul 0.9× baseline, decoder policy, fillers, explicit stop controls, single recorder, ASR ownership and playback-aware history. No continuous-ASR probe has been re-enabled. The plan's backlog and sustained-playback acceptance gate remains necessary before enabling keyword-free interruption on the Fold 6.

Validation: 70 focused Kotlin/JUnit tests passed with assertions enabled, including five new cases for immediate completion, simultaneous correction/completion, exact-once retained follow-up audio, measured keyword warmup and a listener ending before readiness. Existing playback-history, action-receipt, microphone ownership, interruption, echo-gate and keyword tests remain green. Full Android debug/release validation passed in [build 650](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34504092102). The [signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.650/app-release.apk) was published from source commit `962e2101b0be20d82a30c7f2099ef63df7b0bf31`; phone acceptance remains pending.

Phone acceptance for this increment: finish several short and long replies, then immediately ask a follow-up without a keyword; verify its opening words and that no reply audio becomes user text. Repeat after an interrupted reply, Pause/Resume and external microphone handoff. Compare the same Paul 0.9× profile with build 648. Use the new keyword input/work/backlog evidence to distinguish required detector context from actual processing delay. Continue Phase 2 saved-history checks. Remaining Phase 3 work: budgeted natural-correction recognition with retained onset, echo/backchannel rejection and safe overload fallback, followed by sustained concurrent-playback validation and device/route AEC acceptance.


### 2026-09-10 — Build 648 evidence clarified by the user

The user confirmed that after ending call `e74595b3-dcb8-402d-b75c-86a78aa8b6e6`, they did not attempt another wake. The following zero-score passive-listening interval therefore does not demonstrate wake failure and does not justify changing wake thresholds, preprocessing or restart behavior. The completed reply provides positive Phase 2 evidence: delivered characters advanced 0 → 48 → 99 and became COMPLETED only after all 153600 frames played. It does not validate interrupted-answer history or saved-call resume. Keep those acceptance cases open.

### 2026-09-10 — Phase 3: bounded natural-correction recognition

- Added `NaturalBargeInAudioInput` for Moonshine replies. A persistent VAD on the reply's existing microphone qualifies candidate speech; the trained keyword detector continues processing each input chunk independently of ASR. Silence does not create an ASR stream. Whisper keeps its keyword-only path; no engine selection or voice profile is changed.
- `BoundedInterruptionRecognizer` owns native ASR construction, inference and release on one optional worker. Capture submits no queued second window while that worker is busy. Each native call receives at most 250 ms of input before another cancellation/resource check; one native call itself cannot be preempted. The 700 ms worker budget is checked between calls and after finalization/release, not a guarantee that a blocked JNI call returns within 700 ms.
- Admission requires reusable, unborrowed Moonshine weights below the existing eight-stream rotation boundary. A probe cannot initiate a cold model load or force rotation. During answer playback it also requires at least 900 ms of queued source PCM. Playback-pressure, native failure/budget excess, more than 600 ms input backlog or input older than 800 ms disables natural probes for that reply, retaining keyword controls. Budget checks continue between inference calls so playback gets priority.
- Retain 400 ms of onset context and at most four seconds of candidate PCM. At most two bounded snapshots are attempted per candidate and four per reply. Reject candidates before their onset rolls out of the buffer; discard stale/revision-mismatched results. These deliberately conservative limits mean keyword-free interruption is not guaranteed for every utterance or throughout a long reply. `barge_natural_unavailable ... fallback=keyword` records degraded operation instead of claiming full readiness.
- Fresh recognized request/correction evidence must pass the existing echo/request gate and a 300 ms settling period with recent VAD evidence. Noise and backchannels alone never pause output. On confirmation, stop reply playback, join/release the probe owner, and replay the retained correction into ordinary final-turn recognition before accepting live continuation. Keyword confirmation stops playback before waiting for an in-flight native probe; final ASR cannot compete for that probe's model lease.
- `NaturalCorrectionText` rechecks final recognition, removes only leading echo, and retains subsequent corrections, negation, casing and punctuation. A provisional request cannot dispatch a tool. Empty/echo-only final recognition is discarded rather than converted into an audio-only fallback action. Existing final speaker preference, final tool guards and Phase 2 delivered-history/action-receipt handling remain in effect; speaker preference is not claimed as a pre-interruption identity guarantee.
- Retained build 650 keyword warmup/input/work/backlog diagnostics and added per-candidate admission/result/staleness/degraded-mode evidence. Readiness is scoped to the candidate, not a claim of always-available duplex recognition. No speculative model output is spoken and no filler/Paul conditioning change is included.

Validation: 100 focused Kotlin/JUnit tests pass with assertions enabled, including 18 new tests and the existing microphone-handoff suite. They cover exact-once onset replay, no ASR work on silence, echo/backchannel rejection, synthetic sustained input with bounded probe count, stale worker results, worker failure/close failure, playback pressure, exclusive native ownership, keyword progress during blocked native decoding, warm-only admission, later app-target corrections and cancellation preservation. Android debug/release tests, assembly and packaging checks passed in [build 653](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34519245660). The [signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.653/app-release.apk) was published from source commit `83707103e6ae65fd786be25550b0467031d55ee5`. Synthetic workload tests establish queue/ownership behavior; they do not establish Fold 6 RTF, acoustic echo cancellation or audibility.

Phone acceptance: use the established Paul four-thread 0.9× profile and Moonshine. During preparation and a long spoken answer, try “Actually, use the other one” and “Can you tell me about dogs?” without a keyword. Also try an app request followed by “actually” with another target, then a cancellation; no provisional or superseded target should execute. Try “mm-hmm,” background fan/rustling noise and Jarvis repeating the user's words. Explicit “Hey Jarvis”/“stop” must remain usable when probes degrade. Inspect `barge_probe_result` work time, natural-unavailable reason, capture backlog, TTS supply gaps and Phase 2 delivery/history after interruption. Continue phone speaker/Bluetooth/EYE VUE AEC, alternate-speaker, quiet-speech, sustained-memory, rotation and external-microphone acceptance. Preserve immediate follow-up from build 650. Do not mark Phase 3 complete until these device checks pass.


Build 652 validation follow-up: Android debug CI passed all 391 tests and assembly. Release CI reported one failure in the existing `MicrophoneHandoffTest.cancellationBeforeRecognitionStartsReleasesPriority`; the other 390 tests passed. The test joined the cancelled job without explicitly awaiting its independently executing completion handler. It now awaits an explicit cleanup-completion signal before asserting that dictation priority was released, retaining the cancelled-before-start and released-priority assertions. No production microphone policy was changed. Replacement build 653 passed both Android jobs and published the signed APK; build 652 has no published release APK. Device acceptance remains pending.


### 2026-09-10 — Build 653 natural-interruption failures and corrections

Phone evidence confirms natural interruption was unavailable in the tested replies: temporary `playback_budget` caused sticky keyword-only fallback, a probe that finished in 649 ms was only consumed/discarded about 3.9 seconds later, and the next probe hit `probe_model_not_warm` after the eighth Moonshine stream. There is no successful natural-interruption confirmation in this call. Short responses made testing harder, but do not explain these code failures. Paul remained ahead of playback with no estimated supply gaps; preserve the selected voice profile.

Implemented corrections:

- Poll completed recognition on every capture frame, including silence and inactive candidates, before new submissions. Retain revision/age checks. Let credible words finish their 300 ms confirmation window before scheduling a larger recognition snapshot, so newly arrived results cannot be immediately replaced by another probe.
- Treat temporary playback pressure as a deferred probe, close its native stream on the worker, and allow a fresh candidate after a 500 ms cooldown when playback has budget again. Check budget before opening a stream. Genuine native/release failures and decode-budget overruns still fall back to keywords; cancellation still joins the owner before final correction recognition.
- Reserve command-plus-four-probe capacity when acquiring command Moonshine weights. Rotate before command recognition if needed, rather than exhausting the eight-stream SDK bookkeeping bound during the reply. Probes remain warm-only, single-owner, and capped at four per reply. This can move an occasional model load into command startup; it does not increase the memory/stream bound or guarantee unlimited natural recognition throughout a long reply.
- Add the exact local voice command **“Start the interruption test.”** It sends a fixed long story through the normal call TTS, interruption listener, delivery ledger and saved history. It bypasses answer generation and tool lookup for that exact command only; negated, quoted or extended requests do not match. Duration depends on speech profile (roughly half a minute or longer), not Gemma's response length. No new voice tuning or forced filler.

Validation: 105 focused Kotlin/JUnit tests pass with assertions enabled. Regression tests cover pressure recovery in the same reply, result consumption during silence, delayed credible-result settling without a replacement probe, reservation through the full four-probe budget while retaining the eight-stream ceiling, and exact test-command dispatch. Existing echo rejection, final correction arguments/cancellation, keyword fallback, blocked-native ownership, delivery/history and microphone tests remain required. Android debug/release unit tests and assembly passed in [build 655](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34537730235). Release signing verification passed; the [signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.655/app-release.apk) is published from source commit `a0cc985438c04b69ff00f9ce51b075bbe12618d1`. Phone acceptance remains pending.

Phone acceptance: say “Start the interruption test,” wait about two seconds after story speech starts, then say “Actually, tell me about dogs.” Repeat using “stop,” and repeat across several calls to exercise model rotation. Confirm playback stops, the correction retains its first words, and saved history distinguishes delivered text from the unplayed story. Inspect deferred/retry events, `barge_natural_ready`, `barge_speech_confirmed`, model-rotation reason and TTS supply gaps. If the four-probe budget is exhausted, keyword fallback remains expected and must be visible in diagnostics; sustained natural availability and acoustic echo/alternate-speaker acceptance remain open. Phase 3 is not complete.


## Follow-up to build 666: interruption recovery and Phase 4 long turns

Implementation on `audio-pr2`, 11 September 2026. This entry supersedes older execution-status summaries for these changes. Build 666 phone feedback reports a substantially better experience, working Hey Jarvis, and unreliable natural interruption. Phase 3 phone acceptance remains open.

### Experience fixes

- Natural interruption suspends during excessive capture backlog, then resumes after 500 ms of recovered fresh input. A temporary 700 ms backlog no longer disables natural interruption for the entire answer. Queued probes older than 300 ms are dropped before loading ASR. Result freshness now accommodates the existing 1600 ms worker budget. Keyword listening remains independent.
- Retain the four-probe per-reply limit and 900 ms audio-supply gate. These still limit interruption opportunities under sustained synthesis pressure; this change is not a claim of reliable full duplex on the phone. A final compact natural-barge summary records recovery, pressure and rejection evidence.
- Explicit final-clause stop requests bypass answer generation and keep the call listening. Quoted, negated, and task-specific uses such as stopping music are excluded.
- Prompt diagnostics break down the assembled answer prompt into base/request, context/dialogue, resolved subject and references. They do not persist the verbatim prompt or claim to account for native retained KV context. The old 7283-character prompt cannot be reconstructed from the shared logs.

### Phase 4 implemented portion

- `TurnEndDetector` separates completion policy from capture. Adaptive fallback gives unfinished/hesitant speech 3500 ms, preserving the existing 350 ms stable-complete endpoint.
- Internal ASR segments rotate at a quiet boundary after 15 seconds, or at 22 seconds with a bounded 1200 ms overlap. Segment boundaries never dispatch a turn. Native streams are closed before replacement; whole-utterance text is accumulated with conservative overlap reconciliation.
- Raw recognition/fallback audio retains only the latest 25 seconds; transcript storage is bounded at 12000 characters. At 120 seconds, or on an ambiguous/unrecognized segment boundary, the reply asks for shorter repeated parts and disables action dispatch for that request. These are capacity failures, not automatic submission of an incomplete command.
- Beyond 25 seconds, speculative audio work is invalidated and the final complete transcript uses the text inference route with voice response policy retained. A truncated audio tail is never presented as the full request to Gemma. Short utterances retain the existing audio/text path. This necessary long-turn safeguard does not implement Phase 5's broader selective-audio policy.
- Speech arriving while final ASR is running defers endpoint acceptance and resumes recognition. Silence arriving during finalization does not repeatedly finalize a sealed native stream.

### Smart Turn evaluation and remaining gate

Reviewed the upstream [Smart Turn inference implementation](https://github.com/pipecat-ai/smart-turn/blob/main/inference.py) and [model repository](https://huggingface.co/pipecat-ai/smart-turn-v3). The current v3.2 candidate is quantized, BSD-2-Clause licensed and consumes Whisper-style features from an 8-second, 16 kHz window, not raw PCM directly. The model repository revision observed was `f766f81d3cfdf7737ac64aad813d91bbfd56bf93`. Exact preprocessing parity, selected artifact checksum/operator compatibility, Android runtime packaging, and Fold 6 latency/memory measurements remain unverified. No learned detector is promoted or bundled in this change. Phase 4 remains open for that evaluation and device acceptance; its bounded long-turn/fallback implementation is delivered here.

### Validation

160 focused Kotlin/JUnit tests pass locally, including 30/60-second segmented transcripts, bounded PCM beyond 25 seconds, conservative seam failures, retained corrections, exclusive native ownership, endpoint resumption without double finalization, stop-policy exclusions, and transient backlog recovery. Full Android compilation, release unit tests and signed APK packaging are required in the PR workflow. Device acceptance still needs: hesitant 30/60-second speech without premature reply; correction after a segment boundary; natural interruption during both early synthesis pressure and later buffered playback; and stop/Hey Jarvis at varied timing and volume. These logs do not establish acoustic quality or successful natural barge-in.


## Build 668 phone regression: endpoint livelock with speaker preference

User call `61129ade-57cd-4e1a-99d3-746d45169fd1` on build 668 using Kokoro exposed an interaction the previous endpoint-race tests missed. Kokoro completed its reply and command capture was borrowed with a 219 ms follow-up handoff. The subsequent turn repeatedly computed the same speaker preference embedding (~282–335 ms), then deferred the endpoint because microphone PCM had accumulated during that computation. VAD reported silence for 11–21 seconds despite a 350 ms target. The turn lasted ~68 seconds and was interrupted by END_CONVERSATION. This is a capture finalization livelock, not evidence of Kokoro failing to return the microphone or an external-microphone conflict.

Fix: a pending finalized endpoint reuses its already accepted speaker decision while consuming queued silence. Speech resumption invalidates that pending endpoint and still requires a fresh speaker decision. ASR finalization remains cached by SegmentedTranscriber; queued real speech must still be consumed before submission. Cancellation/completion is checked after native speaker and ASR work before publishing final results. The deferral log is emitted once per pending endpoint rather than flooding diagnostics on retries.

Regression evidence: added a deterministic microphone-backlog test in which every speaker check creates 300 ms of new audio. It fails on build 668's code with the turn still pending after silence drains. Added a separate resumed-speech/rejected-speaker test to verify the fix cannot carry an earlier speaker acceptance over new speech. Focused suite: 162 tests; full Android debug/release CI and signed APK verification remain publication gates. On-device acceptance must confirm one speaker check per candidate pause and prompt handoff to reply after silence, with both Kokoro and Paul. Earlier 160-test success did not cover microphone backlog produced by the speaker check itself and did not establish device correctness.


## Build 669 phone feedback: stop verification and bounded recovery

Call `2a464b4d-9f71-49c9-858d-a545f6ecc187` showed three cancellations 644–685 ms after answer audio began. Two have explicit stop-keyword evidence; all three opening phrases begin with “Sir.” Kokoro generated full opening phrases, so the one-word delivery is cancellation rather than one-word synthesis. Speaker leakage causing false stop hits is a strong hypothesis, not acoustically proven by these logs. The fixed speaker-preference endpoint loop from 668 completed normally in this trace. Natural probes also returned empty text or exceeded the 1600 ms worker budget, after which natural interruption was disabled for the reply.

Implementation: while answer audio is playing, a stop-keyword hit now requests bounded Moonshine verification on up to two seconds of recent PCM. Playback continues until fresh ASR explicitly supports a stop request and the spoken reference does not itself contain “stop.” Verification uses the same exclusive native worker as natural probes, never a competing recognizer, with at most two stop probes in addition to the existing four natural probes per reply. Unverifiable, stale, over-budget and echoed candidates leave playback running. Hey Jarvis retains immediate priority, including simultaneous keyword hits. Reset a detected keyword's native latch so rejection cannot repeatedly spend verification work on the same event. Record native keyword probability/diagnostics with each stop candidate for later device evaluation.

A confirmed keyword stop is handled as a control: stop output and return an empty correction immediately to the retained-microphone follow-up listener. Do not wait for post-stop noise to finalize as an audio-only request and do not submit that noise to Gemma. Normal user-turn audio/text recognition and the Hey Jarvis follow-up path remain available. When the selected recognizer lacks the bounded Moonshine verifier, playback-time raw stop hits are withheld; Hey Jarvis remains the explicit interruption fallback. This limitation is deliberate and must be visible in diagnostics, rather than claiming verified stop support for Whisper.

Decode-budget overruns now release native ownership, discard the result and report a retryable reason. A fresh natural candidate may run after the existing cooldown and within the unchanged probe count. Native failures and model-release failures still disable the optional worker; this is not unbounded retry or a larger concurrent inference budget.

Validation: 167 focused Kotlin/JUnit tests pass, including false Sir/stop detection without cancellation, ASR-confirmed stop after model release, assistant-spoken stop rejection, verification-budget rejection, immediate Hey Jarvis despite recognizer pressure/failure, and a fresh natural candidate succeeding after an overrun. Full Android debug/release CI, signing and native packaging checks remain publication gates. Device acceptance is still needed with actual Kokoro/Paul speaker playback, overlapping human stop requests and silent rooms. The additional ASR verification introduces stop latency and may reject real stop requests when its bounded budget is unavailable; it does not claim microphone echo cancellation has been solved.


### Build 670 follow-up — empty Moonshine streams and renewable interruption work

The same-recording comparison contains intelligible speech (Whisper produces words), while Moonshine returns zero lines/partials with 83 ms work for eight seconds. Normal Moonshine recognition succeeds with Kokoro selected and repeatedly fails with Paul selected. This establishes a voice-dependent reproduction to preserve; it does not yet identify a native TTS/ASR interaction conclusively. Upstream Moonshine VAD has shared recurrent detector state and a second speech threshold; SDK 0.1.5 exposes `vad_threshold=0.0`, already used by the isolated batch recovery path. The streaming path now disables that additional native gate and honors Jarvis `observeSpeech` instead. It holds at most 1.2 seconds of initial PCM, releases the pre-roll at confirmed onset, and retains subsequent audio including pauses until the outer endpoint/segment boundary. Same-recording diagnostics explicitly remain able to decode bounded PCM without a live VAD caller. A policy marker accompanies normal-turn recognition summaries. This is a targeted fix for decoder starvation, requiring the Paul/Kokoro same-recording device comparison before declaring the underlying interaction resolved.

Natural and verified-stop probes now share a renewable allowance of four submissions per rolling 12 seconds, with at least 500 ms between starts. Empty results consume bounded work, but cannot exhaust a reply-lifetime allowance. Existing candidate freshness, maximum two submissions per candidate, native decode deadline, playback-supply checks, single-owner worker, echo rejection and immediate Hey Jarvis control remain in force. A useful fourth result is retained for confirmation. Resident Moonshine weights may rotate after eight SDK streams on the same worker, so SDK bookkeeping limits do not silently become another lifetime interruption cutoff. A true native/model-release failure still disables the worker for that reply.

Gemma fallback inference now has a 12-second coroutine deadline and a fixed, tool-free repeat-request response on timeout. Native cancellation cleanup finishes before conversation reset and may extend the deadline; synchronous JNI cannot be safely forcibly preempted. Diagnostics distinguish timeout from successful transcription rather than treating the final stage marker as success. Kokoro groups short introductory sentences toward a 40-character phrase instead of paying a complete synthesis startup separately for “Certainly, sir.” and another short preamble. No internal Paul PCM is trimmed or assumed to be missing words based on amplitude alone.

Validation: 183 focused Kotlin/JUnit tests pass, including bounded onset retention, bypass for already-recorded speech, probe renewal/rate limits, and successful natural interruption after four empty results in the same reply. Existing false-stop, echo, backlog, cancellation, endpoint and delivery-ledger cases remain passing. Full Android compilation/tests, signing, ASR packaging and TTS callback ABI checks are release gates. Device acceptance: repeat identical recorded input with Paul and Kokoro, confirm non-empty Moonshine text without Gemma; test natural interruption late in a long reply, silence/assistant speech without false stop, intentional stop and Hey Jarvis; repeat the Kokoro short-preamble test and check actual speaker output. Paul’s generated low-amplitude pause and acoustic echo quality remain device-evaluation items, not proven solved by these changes.

## Build 671 follow-up: duplex scheduling, farewell control, and Phase 5 implementation

The user confirms Moonshine recognition with Paul is restored by the external speech gate. Preserve that policy. Build 671 still failed natural/keyword interruption on the phone. The retained goodbye turn shows an admitted probe revoked by the old 900 ms playback-supply requirement, not by the renewable work allowance. Keyword processing was current but no stop hit was recorded; this is not evidence that simply lowering keyword thresholds would be safe. Story-specific evidence was displaced by later short turns.

### Corrections implemented

- Separate optional ASR admission (450 ms queued source PCM) from continuation (80 ms emergency floor). A probe admitted before AudioTrack creation is no longer revoked just because the first 240 ms callback starts playing. Preserve the 1600 ms native-work budget, freshness checks, single native owner, cancellation joins and verified non-echo stop evidence. This is a scheduling correction, not a measured full-duplex performance claim.
- For ordinary Paul playback, wait for actual accumulated callback PCM reaching 640 ms, completion of a shorter phrase, or a bounded 1000 ms startup deadline. Previously a 200–350 ms timer could expire while only the initial callback was available. Benchmark profiles preserve their existing startup behavior. Native delivery remains sentence-based with bounded PCM backpressure and exactly-once callbacks; no guessed silence removal, arbitrary Paul character splitting or seed change.
- Reserve a separate renewable two-submission/12-second stop-verification allowance, so natural probes cannot spend all stop capacity. Natural probes retain four submissions per rolling 12 seconds. Hey Jarvis stays independent of ASR work admission.
- Prime reply keyword detectors with up to 3100 ms of already-consumed microphone history. The history ends strictly before the new consumer's first replay frame. Historical hits are discarded and never become commands. Only subsequent live/replayed-unconsumed capture can confirm interruption. This avoids discarding valid detector context at every turn handoff; cold capture can still require normal warmup. Native thresholds are unchanged. Log per-keyword scores/readiness and diagnostic counters during reply capture.
- Accept direct polite farewells such as “Uh, no thank you. Goodbye.”, “Goodbye”, and “Goodbye Jarvis” before answer generation. Exclude reported, quoted, negated, or instructional mentions. Matching remains a constrained whole-request grammar, not substring detection inside stories.
- Retain compact keyed TTS, PCM, pipeline and interruption evidence for up to 12 turns, separately from per-chunk rolling logs. Add observed empty-playback-queue duration, excluding drain/interrupt intervals, alongside estimated PCM supply gaps. This is a sampled playback starvation measure, not microphone-recorded acoustic silence. Saved evidence contains no microphone PCM or full prompts.

### Phase 5 code delivered

`VoiceWorkScheduler` is call-scoped and throttles optional preparation based on capture backlog, current queued playback, Android thermal status, draft reuse and native cancellation wait. Allow at most two starts per turn and space call-level starts by at least two seconds. Repeated poor reuse backs off for 15 seconds; cancellation waits of at least 500 ms back off for 30 seconds. Moderate-or-worse thermal status, capture backlog over 200 ms and queued answer playback defer speculation. Feedback is logged, while committed recognition/final replies bypass this optional-work policy. Goodbye/stop controls do not start speculative drafts.

Keep the existing single active draft, revision invalidation, matching final transcript, silent opening authorization, and final tool guards. Keep Gemma audio input for ordinary short turns, blank-ASR recovery with its deadline, and the complete-text safeguard for long turns. The user's ASR choice is never silently changed. Existing same-recording Moonshine/Whisper comparisons remain available; Whisper now labels background results provisional and reports their queue wait, while final complete-tail recognition is labeled committed with reuse and final decode timing.

### Validation and remaining device gates

211 focused Kotlin/JUnit tests pass, including a spoken goodbye passing natural interruption while echoed goodbye is rejected, admission/continuation across playback startup, bounded historical keyword priming without control callbacks, no overlap between keyword history and new capture, polite versus quoted/reported/negated goodbyes, scheduler resource/cancellation/reuse backoffs, and finalization with speculation denied. Existing natural/stop echo rejection, renewable budgets, input ownership, long-turn endpointing, draft revisions and Whisper tests pass. Full Android debug/release compilation/tests, ASR packaging, callback ABI and release signing remain publication gates.

Phase 5's scheduling code is implemented; its device performance acceptance is not complete. Compare the same recordings and long/hesitant/corrected requests on the Fold 6, both with useful prepared replies and scheduler-deferred replies. Verify actual Paul story intelligibility, repeated words, early and late natural/stop/Hey Jarvis interruptions, call exit for polite goodbyes, and idle/noise rejection. Keyword priming does not prove speaker echo cancellation or solve every keyword miss. Phase 4 Smart Turn model/preprocessing/runtime evaluation remains open; no unevaluated model was bundled. Phases 6A/6B source-audio and integrated-load comparisons and Phase 7 device acceptance remain required. Implementing more phases is not a substitute for these tests.


### Piper B1 audition follow-up — longer passages and natural pauses

Build 689's Northern English Male suite shows ample isolated synthesis headroom but Justin reports phrase-boundary voice shifts and insufficient sentence spacing. The bounded follow-up adds a Piper-only 320-character passage profile (640-character hard cap), disables Sherpa's hidden per-sentence VITS batching for those bounded passages, and restores natural generated pauses (`silenceScale=1.0`). A wait-for-full-reply comparison is also bounded at 640 characters per invocation. Existing applied small-opening profiles remain selectable. See `piper-northern-english-male.md` for reproduction and acceptance steps. This remains a B1 quality audition, not completion of duplex, latency, or device-acceptance phases; larger context may delay initial playback and does not guarantee accent stability.

### Build 691 recognition follow-up — confirmed speech and bounded quiet evidence

Justin accepts Piper's passage voice and accent consistency. Preserve Piper's model, passage sizes, generated pauses and playback pace. The remaining report is delayed endpointing, incorrect/repeated words after silence and background pickup. Build 691 shows a room-floor frame (RMS 44, Silero probability 0.525) resetting speech, endpoint delays up to 4310 ms with final decode only 150–367 ms, and assistant audio transcribed by interruption probes but rejected as non-requests. Those rejected echoes do not establish false follow-up turns. The initial 12-second Gemma fallback and later Piper passage accumulation are separate latency sources.

This bounded capture correction:

- Applies a capture-only weak-VAD noise check, learning a rolling low-percentile floor only from probability <0.15 frames. Weak detections (<0.8) at <=1.8 times that floor cannot refresh speech or request partial decoding. There is no absolute loudness cutoff; stronger VAD and quiet speech above the floor remain available. Original PCM still reaches ASR and retained Gemma audio. It is not an echo canceller and does not reject all background speech.
- Requires confirmed acoustic frames to refresh strong-speech timers, accumulate strong follow-up evidence or reopen a finalized segment. Previously any probability >=0.5 could bypass the 96 ms confirmation after onset and start another segment. A possible onset receives a bounded 96 ms confirmation window without resetting the silence timer; the 1.2-second pending pre-roll and fresh speaker check remain for genuine resumed speech.
- Keeps the last lexical hypothesis across absent/deferred decodes. Cached words and punctuation changes cannot become fresh quiet-speech evidence. Quiet corroboration uses microphone capture time and a bounded 150–500 ms freshness window during an utterance, with the existing stable multiword whisper onset path preserved.
- Uses 1800 ms silence for unfinished-word/fragment cues, retaining 3500 ms for explicit hesitation or thinking requests and 350 ms for complete stable questions/answers. No-text fallback and manual stop behavior remain unchanged.
- Logs the speech-gate policy and rate-limited weak-noise rejections. Silence-to-final timing omits an earlier producer silence event when later consumer-confirmed quiet speech makes it stale.

The playback-to-listening handoff already uses the playback boundary and retains unconsumed onset PCM. Keep that boundary and apply the corrected confirmation to follow-up candidates; do not add a blind post-playback mute window that loses a real immediate reply. Barge-in echo rejection, keyword controls, native Moonshine gate bypass, single-recognizer ownership and Gemma audio fallback remain in place.

Validation: 87 focused Kotlin/JUnit capture, endpoint, quiet-speech, follow-up, handoff and cancellation tests pass locally, including genuine onset during finalization and an isolated noise spike that must not create another segment. Full Android CI remains the APK publication gate. Device acceptance: ask a short question, remain silent after the answer, repeat softly, then try an unfinished request with a natural pause and an explicit “hold on.” Copy latest Voice Call diagnostics once. Verify reduced extra waiting, no silence-triggered new request, and preserved opening words/quiet speech. Model transcription accuracy and real-room echo rejection still require device evidence; this change does not claim all recognition errors are eliminated. This is an endpoint/capture reliability follow-up within the existing voice implementation plan, not completion of the outstanding device-acceptance phases.


### Build 692 follow-up — conversational intent and long-turn recognition work

Justin accepts Piper's voice and conversation improvement. Preserve the Piper passage profile, sentence pauses, model and playback speed. The last retained turn described an assistant that “will open apps”; it was not an instruction to open an app. The broad `open` matcher enabled `actionGuard`, withheld the generated conversational response for roughly 35 seconds, then replaced it with the phone-action failure despite `action=none actionResult=none`.

This bounded correction recognizes directed app-opening, volume and battery requests at clause starts, excluding quoted examples and descriptive/future/negated wording. Generic confirmation resolves only the immediately preceding specific request/offer. Genuine actions retain the existing execution/result guard. The exact reported transcript is a regression case; ordinary conversational generation can stream again without that false action hold. This does not promise that Gemma's factual answers are corrected.

For recognition, optional partial decoding gets a cooldown proportional to measured work (250–3000 ms after work taking at least 100 ms). Cheap audio feeding does not extend that cooldown; all PCM still reaches the recognizer and finalization never waits for the optional budget. Moonshine uses 8-second soft boundaries at quiet gaps and 12-second forced boundaries, with the existing 1.2-second forced-boundary overlap, complete-text accumulation and single native owner. Other engines retain 15/22-second limits. Committed segment text updates the endpoint hypothesis even when partial decoding was deferred; cached hypotheses remain excluded from fresh quiet-speech evidence. Diagnostics retain maximum recognition work and backlog, rather than only the zero backlog after final drain.

The weak-VAD room estimate now requires at least three low-VAD observations spanning 200 ms and expires observations by captured time after three seconds, including during speech. This addresses the retained 1694 RMS floor persisting while later input was much quieter. The strong-VAD path and original PCM remain intact. It is not general background-speech separation or acoustic echo cancellation.

Validation includes the exact misrouted description, real direct commands and immediate confirmations, stale-noise expiry, partial-work pacing with uninterrupted PCM/finalization, and 30/60-second segmented text with overlap and no concurrent native owners. Full Android unit tests and signed APK CI remain publication gates. Phone acceptance: repeat the conversational description and a 30–60-second explanation with a correction near the end; verify the full request is answered without an action error and compare speech-end-to-final time and peak backlog. This remains a reliability/latency follow-up within the existing plan; sustained-device and barge-in acceptance remain open.


### Build 693 regression follow-up — shared recognition admission and Moonshine input isolation

Justin reports missed near-field speech, room speech/noise being accepted, numerical hallucinations around end-call commands, and repeated retention failures. Whisper reproduced the problem: its final transcript correctly contained “Can you tell me what is the capital of Idaho?”, but an acoustic tail reopened the finalized recognizer and an empty second segment set `unrecognized_segment`. Therefore this is not solely a Moonshine model-selection problem. The recordings are not included in the report; the code and diagnostics establish pipeline defects, not which speaker each physical sound belongs to.

- The shared capture floor no longer becomes zero simply because a spoken passage outlasted the low-VAD calibration window. New quieter non-speech observations lower it immediately. After calibration, room-level high-VAD tails also need measurable signal above that floor (1.1 times for strong VAD, 1.8 for weak VAD); no absolute volume cutoff is introduced. Regression tests replay the reported 131 RMS room floor, 132 RMS/.806 and 141 RMS/.596 tails, and preserve loud foreground speech. ASR speech observation now uses confirmed acoustic decisions rather than any probability above .15.
- Ordinary Voice Calls stop applying the additional software quiet-speech amplifier (`maxGain=1`); the old path amplified 362 RMS input by 2.26 before VAD/ASR. This removes that artificial emphasis on quiet room input, but is not directional beamforming or speaker authentication. The original call audio still reaches Gemma under the existing completeness rules.
- Moonshine's external gate now bounds both ends of speech: 240 ms onset pre-roll, 320 ms trailing context, bounded resumed-speech pre-roll. Previously it stayed open forever after the first onset while the native gate was bypassed. Cadence counts only audio actually forwarded. Already bounded diagnostics/probes retain their explicit no-live-VAD path. Each ordinary command/segment starts with fresh model/SDK state, releases the prior borrower before loading, and leaves the resulting model available to bounded reply probes. Event handling checks stream identity. This is deliberate isolation; the logs do not prove model-cache corruption caused the report.
- Revert the new Moonshine-specific 8/12-second split limits to the existing 15/22-second limits. The shorter forced seams introduced in build 693 increased exposure to overlap disagreement. Keep the full-text safety check rather than accepting an uncertain truncated command.
- If finalization/resumption or a segment seam fails while the entire <=25-second recording is still available, retry that whole recording through the chosen ASR before rejecting the request. Only a nonblank, non-caption result clears the issue. A rolling tail from a longer utterance cannot clear it. Recovery runs on the existing exclusive model owner and is included in final-decode timing.
- Explicit `[NO_SPEECH]` from audio fallback remains a nonverbal result and returns to listening, instead of being changed into a placeholder and sent through a second answer-generation pass.
- Separately, deterministic end-call recognition tolerates the exact reported “1. Stop listening. 2.”, “10. Stop listening.” and “Stop listening. Yeah” while retaining negation, reported-speech and quoted-example exclusions. This supplements the acoustic fixes; it is not a Gemma prompt workaround.

Piper synthesis, passage sizes, speed and pauses are unchanged. Validate both selected ASR engines with normal-volume near-field questions, room silence, a nearby correction during finalization, and explicit end-call phrases. Diagnostics now report Moonshine input versus decoder audio duration and fresh command state. Actual transcription accuracy, competing speakers and device acoustics remain phone acceptance checks; no model switch or claim of perfect background rejection is made. This remains a bounded reliability repair in the existing implementation plan, not completion of outstanding duplex/device acceptance.


## 2026-09-16 — Selectable E2B / E4B local AI

Implemented persistent model selection in setup and Voice settings, routed inference and benchmarks through the selection, added a pinned E4B artifact, isolated caches and validation, and guarded switching during active work. Failed/interrupted native validation remains recoverable from setup. See [AI model switching](ai-model-switching.md) for the source, lifecycle behavior, and pending Fold 6 acceptance tests. Android CI validates compilation/unit tests; E4B phone memory and voice latency are not yet measured.


## 2026-09-16 — Piper-only output and dependency retirement

Removed Kokoro/Paul model installers and selection, dedicated native synthesis
branches and Paul patch, bundled Paul recordings, retired-only host experiments,
Paul profile controls/tests and Kokoro publishing. Kept shared Sherpa/ONNX,
Moonshine, Whisper, E2B/E4B, Piper VITS/espeak, VAD, speaker checks and wake/stop.
Setup requires Piper; retired settings migrate without overwriting saved Piper
profiles. Exact retired installer paths are removed on voice preparation.
Historical calls/results remain readable.

P1/P2 now use Piper with versioned pack IDs and actual output sample-rate replay.
The full Piper comparison includes both 160/320-character openings. Release
verification still checks shared JNI callback ABI and now rejects retired Paul
assets/patch. See [supported stack and acceptance](supported-model-stack.md).

This retires Paul-specific Phase 6A/B work, not the remaining acoustic,
interruption, latency, context, lifecycle or E4B device acceptance gates.

Validation before CI: 239 local Kotlin/JUnit tests passed, including Piper profiles/configuration, retired preference migration and file cleanup, PCM sample-rate handling and existing capture/interruption regressions. Four native-dependency helper tests passed. Piper output/model setup and benchmark core compiled against Android 35 and the pinned Sherpa/Moonshine SDK classes. Signed Android CI and phone upgrade/duplex checks remain release and device gates respectively.


## Build 705 context and short-interruption repair — 17 September

Confirmed code defects: fresh calls imported six entries from a recent call within
15 minutes; runtime summaries/subjects outlived calls; natural interruption rejected
bare No as no_intent. Speaker preference ran downstream of playback stop and accepted
clips shorter than three seconds as uncertain, so it was not an owner gate for stopping.

Implemented clean new-call context with explicit Resume retained, stale-subject reset,
correction-priority voice instructions, exact submitted-prompt/history provenance retention,
any-word natural floor-taking with speaker confirmation before stop, earlier bounded probes,
and listening-only handling of isolated interruption words. Existing keyword controls and
final tool validation remain. Build 706's test compilation blocker was separately repaired:
its local download fixture now uses portable ServerSocket instead of com.sun HttpServer.

Validation: 77 focused Kotlin/JUnit checks pass, including short No, owner/background
speaker admission, preserved Hey Jarvis/stop, echo rejection, final correction integrity,
new-call isolation/explicit resume, prompt persistence/truncation/retention and download
resume/200-response fallback. Full signed Android CI is the remaining build gate.
Phone acceptance: owner says No/Yes/I once while generating and speaking; another speaker
and TV speech should not interrupt; owner keywords still work; new calls must not inherit
MVP/squirrel content while explicitly resumed calls can. Inspect speaker scores and
speechOnsetToStopRequestMs. No sub-300-ms stop latency or reliable short-clip biometric
matching is claimed without device evidence. Phase 3/latency acceptance remains open.
