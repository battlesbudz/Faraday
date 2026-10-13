# Jarvis OS V2 — voice repair proposal

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

Prepared 12 September 2026. Status: proposal only; no application changes, commits, or dependency upgrades. ECG supplied the evidence-first workflow. Baseline inspected: `audio-pr2`, commit `93a144c`. Implementation must recheck the branch head and reconcile later changes.

## Recommendation

Continue V2 with Moonshine, Gemma, Paul, microWakeWord, and the existing local call architecture. Repair audio production, acoustic input, and interruption control in bounded stages. Treat Pocket replacement or a V3 model as a later decision supported by phone measurements.

Documented implementations provide useful patterns, but their server or desktop performance does not establish fully local performance on the Galaxy Z Fold 6. The proposal below adapts those patterns; it is not a claim that any reviewed framework already runs this exact stack successfully on this phone.

## Evidence and limits

Build 673 call `001f012e-7919-4ab5-9bc0-f8a0eea70414` recorded 20 text submissions, 271 native PCM callbacks, 102.64 seconds of source audio, 116.415 seconds of synthesis/session timing, 19 underruns, and 1.230 seconds of sampled playback starvation. The final segment took 14.752 seconds for 11.360 seconds of source audio. At 0.9 playback speed it lasts approximately 12.622 seconds, so production still falls behind. Aggregate timing may include scheduling and consumer effects; isolated benchmarks are necessary to assign computational cost.

The uploaded WAV contains the final 30 seconds of that source PCM. Its three retained text-submission joins match the logged deltas exactly. Waveform checks found no clipping and no large discontinuity at those three joins; this does not establish that every callback or audible artifact is clean. Justin reports the WAV is faster and has no noticeable playback pauses, but retains the accent changes. Thus voice inconsistency exists before playback; source PCM excludes Android time stretching and playback gaps. Crackling was not separately resolved by the listening report.

Barge-in processed microphone audio without recorded backlog, but neither keyword triggered. Natural recognition attempted 34 probes and repeatedly hit work/playback limits. No successful interruption is demonstrated. The source includes model acquisition inside its 1600 ms probe deadline. One logged rotation consumed roughly 967 ms. This leaves little recognition time, although it is not the sole established cause of all failures.

## What other implementations teach us

| Primary source | Verified pattern | Application to V2 |
| --- | --- | --- |
| [LiveKit turn handling](https://docs.livekit.io/agents/logic/turns/) | VAD-based interruptions can resume after a false trigger; speech has explicit interruption controls. | Separate a reversible pause from permanent cancellation. |
| [LiveKit adaptive interruption](https://docs.livekit.io/agents/logic/turns/adaptive-interruption-handling/) | Acoustic classification distinguishes interruption attempts from acknowledgments; its documented service is hosted in LiveKit Cloud. | Borrow the distinction between speech onset and intent. Do not assume its classifier is an available offline Android component. |
| [Pipecat pipeline](https://docs.pipecat.ai/pipecat/learn/pipeline) and [interruptions](https://docs.pipecat.ai/pipecat/fundamentals/interruptions) | Priority interruption signals cancel in-flight work and flush unplayed output; conversation context follows delivered speech. | Keep control delivery independent of synthesis queues and preserve the existing delivery ledger. |
| [Pipecat speech input](https://docs.pipecat.ai/pipecat/learn/speech-input) | Speech start and turn completion are separate decisions using VAD, transcripts, and optional turn models. | Keep adaptive end-of-turn handling separate from barge-in. Smart Turn is not an echo or playback fix. |
| [Kyutai Pocket source](https://github.com/kyutai-labs/pocket-tts/blob/8d4be1987ef1531fbbf3300d972fdbdc68c1799b/pocket_tts/models/tts_model.py) and [text grouping](https://github.com/kyutai-labs/pocket-tts/blob/8d4be1987ef1531fbbf3300d972fdbdc68c1799b/pocket_tts/models/text_chunking.py) | Uses sentence/token grouping, copies voice-conditioned state by default, initializes decoding per short-text operation, and separates latent generation from decoding with queues. | Audit model-version parity and measure scheduling options. Continuous decoder state is an experiment, not an established upstream requirement. |
| [Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler) | Echo cancellation attaches to the actual AudioRecord session and availability/default activation vary by device and source. | Verify actual route and effectiveness; a requested/enabled flag is insufficient acoustic evidence. |
| [WebRTC audio processing](https://webrtc.googlesource.com/src//%2B/558c2dc5397cbb85db138ef17e36c816c4dad50e/api/audio/audio_processing.h) | Processes capture and render-reference streams with delay information. | If platform AEC fails measured tests, evaluate local software AEC with an aligned playback reference. |

Pocket source was inspected at the pinned revision above. It is newer than Jarvis's January 2026 ONNX model export. Porting current preprocessing or code requires identifying the matching model/configuration first. Current upstream also explicitly leaves prior-chunk audio conditioning as future work; it is not a ready-made continuity feature to enable.

## Stage A — build one reproducible in-app comparison

Extend the existing diagnostic harness rather than add another permanent settings panel. Run a fixed passage (ordinary narration plus quoted dialogue), short commands, and a two-minute story. Attach text hash, voice-reference hash, model/runtime versions, profile, and build to each result.

Run these conditions in sequence: prerecorded PCM playback alone; Paul synthesis/playback alone; Paul plus keyword/VAD; Paul plus candidate recognition; full live pipeline. Replay the same user test input into recognition separately to distinguish signal problems from compute pressure. Start with a cool phone, then repeat the winning configuration after ten minutes of conversation.

Measure model acquisition, native decode, finalization, model release, queue wait, callback arrival interval, playback head, empty-queue intervals, and actual first playback separately. Preserve rolling source PCM and compact diagnostics. Microphone test audio stays transient in memory by default; exporting it would be an explicit diagnostic action, not routine call recording. All user tests run in-app without ADB or a desktop.

Exit: failures are assigned to source synthesis, playback/throughput, acoustic input, or control handling. Reuse existing tests and recordings rather than expanding into an unrelated benchmark suite.

## Stage B — stabilize Paul's generated voice

Primary files: `PocketSpeechPolicy.kt`, `PocketTextStream.kt`, `TtsBenchmarkProfile.kt`, `SherpaKokoroVoiceOutput.kt`, `native/sherpa/pocket-streaming.patch`.

Compare the same reference and text with current defaults, matching-upstream preprocessing, and a full-text diagnostic baseline. Full-text submission is not automatically one continuous model operation; inspect any internal splitting. Compare natural sentence groups within the model's token limits. Avoid guessed character splitting and unbounded paragraphs.

Audit reference loading/resampling, voice conditioning, generation steps, temperature, seed lifecycle, end-of-sequence tails, and the added leading period. Change one variable at a time. Test decoder continuation separately from language-model prompt state; do not carry a completed text/EOS state into the next sentence. Existing native comments report truncation from that approach. Keep reference identity constant and compare both narration and dialogue: intentional expressive variation must not be mistaken for speaker replacement.

If necessary, generate a matching-checkpoint upstream reference outside the phone implementation for quality comparison; phone runtime remains local and unchanged during this research. Do not claim byte equality between PyTorch and quantized ONNX. Test that callback concatenation equals the same native run's collected output to detect transport corruption.

Exit: blinded A/B listening across three passages finds a stable, acceptable Paul voice without skipped/repeated words. If drift persists in the matching upstream baseline, classify it as a model/reference limitation. Discuss a better authorized Paul reference or another voice backend rather than continually altering buffers. No silent voice substitution.

## Stage C — give playback and recognition sustainable resources

Primary files: `BoundedInterruptionRecognizer.kt`, `InterruptionTiming.kt`, `NaturalBargeInAudioInput.kt`, `MoonshineStreamingTranscriber.kt`, `VoiceModelSession.kt`, `DuplexPlaybackBudget.kt`, `PaulPlaybackBuffer.kt`, `VoiceWorkScheduler.kt`, and native TTS patch.

Separate model readiness, recognition compute, result freshness, and cancellation cleanup. Keep a total bounded operation deadline; removing model-load time from the decode budget must not make stale input actionable. Move bounded model renewal to safe opportunities where feasible, preserving the existing SDK stream ceiling and exclusive native ownership. Measure whether a candidate-lived stream reduces repeated startup; do not blindly restore the earlier always-on ASR experiment that exceeded the phone's budget.

Keep microphone capture and keyword/VAD work serviced continuously while Jarvis owns the microphone. Admit recognition with reserved capacity, and consume a finished fresh result before abandoning it for a transient playback-watermark change. Emergency overload must remain bounded and visible. A candidate that cannot finish must recover through explicit control state, not an endless retry loop.

Measure Paul with 2, 3, and 4 compute threads under simultaneous recognition. More threads may hurt total responsiveness. Measure latent generation and decoder costs independently. Consider a bounded producer/decoder queue, inspired by Pocket's architecture, only if it improves combined phone performance and preserves native ownership, callback order, cancellation, and memory bounds. Queue concurrency is not a guaranteed speedup.

Retain a small adaptive startup cushion based on actual queued audio. Measure 1.0x and the existing 0.9x separately; choose cadence after throughput is sustainable. Keep the chosen rate stable within an answer. Avoid masking deficits with progressively slower speech or inserting fillers into a story. A sustained producer slower than playback requires faster computation, reduced competing work, or a deliberate backend tradeoff.

Exit: three two-minute story runs with no measured mid-answer playback starvation and no audible stutter, followed by the integrated warm-phone run. Target effective generation cost at most 80% of playback duration under intended concurrent load, leaving headroom. This is a proposed engineering target, not a current capability.

## Stage D — make the microphone distinguish Justin from Jarvis

Primary files: `AndroidAudioInput.kt`, `VoiceAudioSession.kt`, `ReplyVoiceCapture.kt`, `MicrophoneInterruptionMonitor.kt`, `MicroInterruptionKeywords.kt`, and native microWakeWord frontend.

V2 already requests platform AEC. Verify its availability, actual enable/control state, input/output route, and recognition under simultaneous speaker output. Compare quiet speech, speaker playback, and headphones at reproducible distances/volumes. In memory, compare captured user audio, playback reference, and keyword score curves; include silence and assistant-only playback controls.

If the current recognition route performs poorly, test a communication route with explicit ownership. Current contention checks treat MODE_IN_COMMUNICATION as external contention; fix ownership and restore the prior mode on exit before enabling that route. Preserve immediate release to real calls/dictation and automatic background rearm.

If platform AEC cannot preserve overlapping speech, evaluate WebRTC software AEC as a bounded experiment. Supply the actual rendered reference, rate conversion, gain and delay alignment. Android time stretching complicates reference matching; use controlled 1.0x tests initially or an owned stretch stage whose output is available to the AEC. Avoid stacking two unmeasured echo cancellers. Recheck CPU and battery cost before promotion.

Only after signal quality is verified should keyword frontend parity or thresholds change. Current near-zero scores with a ready detector do not prove a threshold problem. Test for processing that suppresses Justin along with echo.

Exit: reliable keyword and natural-speech evidence during speaker output, without assistant-only triggers, and no mic-handoff regressions.

## Stage E — implement recoverable interruption control

Extend existing coordinator/delivery components rather than create a second competing state machine. Proposed behavior:

| State/event | Action |
| --- | --- |
| Speaking; credible external speech begins | Enter a bounded candidate state; retain first words, briefly duck or pause output, and prioritize recognition. Tune onset from measured speech/noise examples. |
| Candidate confirms a request | Flush unplayed audio immediately; cancel generation cooperatively; retain actual delivered history; continue recognizing the correction. |
| Confident Hey Jarvis | Stop output through the priority path without waiting behind natural ASR. Retain following request audio. |
| Possible stop keyword | Briefly pause and run the reserved non-echo verification path; a false stop can resume. A reliable later acoustic classifier may shorten this path, but is not assumed available. |
| Confirmed stop | Stop speaking and remain in the active conversation. |
| Confirmed goodbye / goodbye Jarvis | End the call and restore passive wake listening. |
| Noise/echo false candidate | Resume retained PCM from the saved playback position, without regenerating the previous sentence. |
| Unresolved recognition or external mic ownership | Follow an explicit bounded recovery/handoff policy; never resume over ongoing credible speech. External microphone interruption keeps its existing resume contract. |

A provisional pause does not authorize a tool or commit a user request. Pause and cancel must have different semantics. Save bounded unplayed PCM and decoder state for a short verification interval; when storage/work limits are reached, backpressure generation at a safe callback boundary. Confirmed cancellation must not wait for synchronous JNI cleanup before silencing the speaker, and native state must not be freed while in use. Playback-head uncertainty must be documented; do not claim word-exact resume from segment-only timestamps.

## Acceptance and delivery

These are proposed initial acceptance targets, to be measured rather than promised:

| Test | Target |
| --- | --- |
| Explicit commands during narration | At least 19/20 successes each for Hey Jarvis, stop, and goodbye at ordinary speaking volume; test early and late positions. |
| Natural corrections | At least 18/20 successes, preserving initial words. |
| Audible response to natural interruption | 95th-percentile provisional yield within 500 ms of speech onset. Record confirmation latency separately. |
| Explicit command response | 95th-percentile audible stop within 500 ms of keyword completion; separately measure detector and output-flush latency. |
| False triggers | No confirmed cancellation in a 10-minute assistant-only/noise test; count provisional ducks/pauses separately and target at most one. |
| Source voice | Three accepted passage comparisons with no objectionable accent drift or repeated/skipped words. |
| Playback | Three two-minute story runs without observed starvation or audible stutter; repeat combined tests after warmup. |
| Responsiveness | Report actual final-word-to-first-audio, ASR-final-to-audio, first-text-to-PCM, and PCM-to-playback; require no material regression from the baseline. Fillers are measured separately. |
| Lifecycle | Screen-off wake, background use, dictation release/rearm, repeated calls, stopped/goodbye calls, and interrupted-history correctness all pass. |

Twenty trials are an initial acceptance sample, not proof of universal reliability. Retest specifically after routing, runtime, or model changes. Unit tests cover cancellation races, late callbacks, stale results, buffer order, false-pause resume, SDK rotation and own-versus-external microphone ownership. Native packaging, debug/release compilation, and signing remain publication gates. Phone listening and interruption acceptance cannot be replaced by unit tests.

Deliver in reviewable increments on audio-pr2: A baseline harness; B Paul quality correction selected from evidence; C scheduling; D acoustic route; E recoverable control and combined acceptance. Stage D measurement can occur during A; its results determine whether software AEC is needed. Each build records its exact scope and rollback profile. Reconcile these tasks with the existing Phase 3 interruption, Phase 6 quality/load, and Phase 7 acceptance work. Keep the completed Moonshine external speech gate, long-turn safeguards and Phase 5 speculation limits. Defer additional Smart Turn/model migration work until these failures are resolved.

Expected difficulty: baseline harness and timing separation are bounded changes; throughput and voice parity are medium, evidence-dependent work; acoustic duplex and resumable cancellation have the highest integration risk. A full phone-independent completion date is not supportable yet. The first milestone should be a diagnostic build that selects the next change from evidence, not a claimed all-fixed build.
