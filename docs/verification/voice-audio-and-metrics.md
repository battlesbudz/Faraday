# Voice audio and benchmark checkpoint

## System-level full duplex

Jarvis implements simultaneous microphone capture and assistant speech playback
at the application/audio-session level. During an active, unpaused call, one
call-owned recorder feeds sequential capture consumers while Piper can continue
playing a reply. Returning a consumer does not stop the hardware reader; retained
PCM and a consumption cursor preserve the onset across command/reply handoffs.
Pause, explicit End, external-microphone priority and capture failure retain their
existing ownership and cleanup rules.

With speech recognition enabled, Silero speech evidence and bounded, warm
Moonshine or Whisper probes check interruption candidates while playback continues.
A confirmed natural interruption requests `stopSpeaking()` immediately, closes
and joins the probe worker, and forwards the retained candidate PCM into final-turn
capture. Native recognition has released its lease before publishing that result. Final recognized words are checked again before becoming a request;
provisional probe text does not authorize tools. Playback-budget, echo, freshness
and model-availability guards remain in force. This is bounded interruption
recognition, not continuous unrestricted ASR decoding during every reply.

The architecture keeps capture, ASR, Gemma/LiteRT-LM generation and Piper TTS as
separate stages. **Full duplex describes overlapping audio input/output**, not a
single model jointly consuming and generating an unrestricted live audio stream.
Gemma audio-understanding mode still consumes bounded recordings. With its live
captions disabled, natural word-verification probes are disabled too. Keyword
controls with playback-text echo checks remain available during playback; VAD
governs the subsequent request capture, as described below.

Current source owners:

- [`VoiceAudioSession`](../../app/src/main/java/com/battlesbudz/jarvis/v2/voice/VoiceAudioSession.kt)
  owns the hardware reader, bounded PCM history and sequential consumer cursor.
- [`OrdinaryVoiceReplyStage`](../../app/src/main/java/com/battlesbudz/jarvis/v2/runtime/turn/OrdinaryVoiceReplyStage.kt)
  wires the reply listener to the retained microphone alongside answer playback.
- [`ReplyVoiceCapture`](../../app/src/main/java/com/battlesbudz/jarvis/v2/voice/ReplyVoiceCapture.kt)
  owns confirmation, immediate playback-stop request and finalized interruption capture.
- [`NaturalBargeInAudioInput`](../../app/src/main/java/com/battlesbudz/jarvis/v2/voice/NaturalBargeInAudioInput.kt)
  owns bounded candidate verification, playback-reference checks and retained PCM delivery.

`VoiceAudioSessionTest`, `NaturalBargeInAudioInputTest`, `BargeInGateTest` and
`ReplyListenerRecoveryTest` cover controlled ownership, retention, confirmation and
recovery contracts. Source wiring and these tests establish the implemented
system-level architecture; they do not establish universal acoustic echo rejection,
Bluetooth-route compatibility or measured interruption latency on every phone.
Physical speaker/microphone overlap, echo-only rejection, interruption-onset
retention and route/lifecycle changes require device evidence for the tested build.
Android AEC being requested or enabled is not proof of acoustic effectiveness.

## Intended behavior

The build 874 log contains a correct final vaping transcript followed by generated unfinished `nicotine-` loops and a 31.5-second speech-supply gap. It demonstrates a model-output loop passed to synthesis. Earlier thinking transcripts and reported self-echo need further physical evidence; the supplied latest-turn log does not establish the acoustic cause of those earlier errors.

The speech guard now detects adjacent single-word and short-phrase loops before an unfinished passage becomes queued speech, cancels native generation, and permits one bounded repair even after a valid opening. A second loop stops rather than continuing queued nonsense. Natural long responses remain permitted. The capture-profile identity now controls recorder reuse, and the next ordinary listener retains a bounded playback-reference tail for conservative echo rejection. Speech clarity remains selectable; it is not claimed to improve recognition on every device.

## Answer-only speech

Voice turns speak the actual answer as it becomes ready. There is no injected
opening acknowledgement or sentence-gap recovery phrase, including when an old
filler cache is present or answer generation takes longer than expected.
Nonverbal wake/ready tones remain available. Generated answer text is not edited
merely because it contains words previously used as a cue.

Normal output keeps eight queued PCM passages; accepted-action follow-up speech
keeps two. Existing sentence boundaries, whole-passage native synthesis,
`silenceScale=1`, 1× playback, startup-headroom policy and all natural PCM silence
remain intact. This change does not shorten a normal 278 ms sentence pause.
Answer playback still owns captions, echo-reference history, actual-head delivery
confirmation, interruption and drain/cancellation cleanup.

Source-contract checks reject a live reconnection of the old cached/synthesized
cue paths. JVM regressions preserve an exact 278 ms PCM pause plus passage-edge
silence and verify existing text, queue, startup and delivery policies. They do
not simulate native Piper or Android AudioTrack. Release/Android checks and a
physical call with slow generation, interruption, answer completion and follow-up
speech are still required. Compare answer timing using the unchanged benchmark
definitions; do not assume the old cue wait predicts a fixed latency saving.

## Settings and audio authority

Open Voice Call settings → Voice input → **Use Gemma audio understanding**. Enable **Whisper live captions** for provisional display; disable it to remove ASR work from normal audio capture. Gemma receives the original recording with instructions and prior conversation. Provisional captions never substitute for the current spoken request or authorize phone actions. A final isolated Gemma transcription updates the paired saved user message after answer generation. This extra pass has its own benchmark purpose and timing. Piper remains the output voice.

The native model supports bounded recordings rather than unrestricted streaming audio. This implementation accepts complete recordings up to an explicit 28-second safety limit, rejects longer/incomplete input visibly, and never silently answers from only a rolling tail. It does not wait 28 or 30 seconds to answer a shorter completed request. The experimental mode answers questions; use speech-recognition mode for phone actions. Unsupported models reject with an explanation. Whisper's provisional live updates use overlapping rolling windows and share one microphone recorder. With captions disabled, recognizer preparation, caption decoding and natural word-verification probes are all skipped. Say Hey Jarvis to take the floor or stop to interrupt; these keyword controls use keyword detection and playback-text echo checks without a transcription engine; VAD governs subsequent request capture. Natural word-based interruption detection remains available with captions enabled.

## Access and documentation

Tap an assistant reply's existing metrics footer for its full measurements and conversation-scoped export. Saved call details also provide a call-scoped benchmark button. The Pipeline benchmarks screen supports JSON/CSV copy, save and share, with a Show all scope toggle. Reports include raw measurements, statuses, definitions, comparable groups, sample counts and percentiles. See [pipeline-benchmarks.md](pipeline-benchmarks.md) for clock definitions, missing-value policy, retention, scoring and the comparison procedure.

## Acceptance matrix

| Behavior | Evidence required | Failure case |
|---|---|---|
| Loop stops before broken suffix is spoken; one repair retains valid prefix | JVM guard/repair tests; runtime release compilation; actual model/phone follow-up | Token-split nicotine loop, repair loops again |
| Capture-profile switch recreates retained recorder | JVM routed-recorder test; phone confirmation | Same call still uses previous profile |
| Echo-only playback-tail transcript rejected, distinct user request retained | JVM echo policy tests; physical Fold 6 test | Own spoken clause becomes a new request |
| Whisper display captions cannot become current audio prompt/actions | Pure policy/prompt tests plus release code inspection; actual model follow-up | Wrong caption says thinking, audio says vaping |
| Captions-off capture submits complete audio with no ASR preparation | Release wiring; physical timing | Caption model loaded despite toggle off |
| Final caption updates saved paired message | Controller regression; actual Gemma follow-up | Duplicate user message or unrelated turn replacement |
| Reply-footer opens only its conversation's retained metrics | Named Android test46 on both shipping variants | Another conversation's rows exported |
| Audio mode and caption preference survive UI rebuild | Named Android test47 on both shipping variants | Toggle silently returns to on |
| Journal survives partial failure/reset, capacity refusal and migration | JVM journal tests | Reset data resurrects on restart |
| Native counts/rates never mixed with callback clocks | JVM telemetry tests; pinned SDK release compile | Callback chunks treated as tokens |

All prior release journeys remain required. Signed normal and compact APKs, the required emulator matrix and the consolidated exact-run receipt gate publication. Physical echo, real Gemma/Whisper accuracy, GPU/runtime behavior and speed comparisons remain unverified until phone tests; synthetic fixture values are not performance claims.

Build 878 passed 929 release JVM tests and built both signed variants. Both sandbox runs passed the conversation export journey but failed test47 before rendering settings: the separately shrunk test APK referenced a removed VoiceInputMode companion field (NoSuchFieldError). Its [retained evidence](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36926255369) is a failure, not a verified release. The repair preserves the precise VoiceInputMode and VoiceInputSettingsKt shared ABI owners, without removing or weakening any scenario. The repaired revision requires a new full release gate.

## Workflow provenance

ECG/PStack planning guidance was consulted. Native work agents implemented voice reliability, direct audio integration and benchmark persistence, with an independent review of the combined change. This is not a completed PStack coordinated receipt: the companion's requested Terra implementation route was unavailable, so no model-dispatch/usage receipt is fabricated. GitHub's signed-release and exact-build verification receipts remain the release evidence.
