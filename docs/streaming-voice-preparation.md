# Streaming voice and speculative preparation

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

Voice calls now use streaming English ASR through Sherpa-ONNX. Gemma receives changing transcript snapshots plus corresponding captured audio while the user is still speaking. Gemma remains the reasoning and tool-selection model; Kokoro remains the speech output model.

## Commit boundary

`VoicePreparation` coalesces ASR changes for 600 ms without waiting for turn silence. Only the newest hypothesis is queued. Every revised draft cancels and joins the preceding native generation, resets its conversation, and seeds current voice-call history. It can generate text or propose structured tool calls, but has no executor, speaker, lookup client, or persistent memory interface. Tool automatic execution is disabled in LiteRT-LM.

Silero's trailing silence ends capture, then ASR flushes remaining encoder context. Only a prepared draft whose text matches the final transcript (ignoring case and whitespace) is eligible for reuse. Final validation is single-use. Changed or unavailable drafts are discarded and Gemma processes the final transcript with the full captured audio. Audio accompanying accepted drafts was captured alongside that hypothesis; trailing silence is not processed a second time.

The existing conversation runtime still routes the final request. Reference-requiring questions discard speculative text and fetch evidence before answering. Phone actions undergo final intent checking, final dictated-argument checks, and the existing native validator. Phone-action text is withheld until a verified result exists. A different noun, changed volume, an unspoken overriding package, or cancellation cannot authorize the old prepared action. Dictated number words such as "fifty actually twenty" are supported.

The matching draft can stream buffered and newly generated text after validation; it does not wait for the whole response to finish. Kokoro preloads while the microphone is listening but receives no text before final validation. This is speculative generation, not incremental editing of Gemma's KV cache. It can reduce latency when useful work fits into speech and trailing silence, but frequent revisions can discard work. It does not guarantee instant audio.

## Streaming ASR model

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, int8 encoder/decoder/joiner, greedy decoding, two CPU threads.
- Model card: https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
- Model license: Apache-2.0, as declared by that model card.
- Runtime source/API: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
- About 44 MB is downloaded once by existing setup or automatically before the first voice call after upgrading. Every runtime file is size- and SHA-256-verified; exact manifests are in `AsrModelStore`.
- A host test of the actual model produced 13 partial updates from the bundled 6.625-second sample. Host speed and this small fixture do not establish Fold 6 accuracy or latency.

Live hypotheses replace the provisional user line in the existing voice-call screen. Only final words enter call history. Raw audio remains bounded and in memory. There is no extra model-selection UI.

## Lifecycle and diagnostics

The voice job owns ASR, capture, preparation, final generation, and TTS. A model-operation lease prevents other model operations from overlapping. Ending a call cancels preparation and final work and stops playback. Native cancellation waits for its terminal callback before releasing the cancelled conversation; the underlying Engine can then create a fresh conversation. Image turns recreate a vision-capable engine when necessary.

Diagnostics distinguish `asr_partial`, `asr_final`, `preparation_started`, `preparation_validated`, `preparation_discarded`, and `consuming_validated_draft`. `endpoint_to_first_text_ms` measures the user's final-to-text delay; existing TTS timestamps show synthesis and playback delays separately.

## Phone acceptance

1. Ask for a story about pirates and watch the words appear before silence.
2. Repeat, changing the ending to "actually astronauts" without a long pause. Only the corrected request should be answered.
3. Ask to open Instagram, then correct it to YouTube before the turn ends. No app should open while speech is still provisional.
4. Say "set volume to fifty, actually twenty" and verify the final level.
5. End a call during preparation or speech, then start another call; no abandoned draft should execute or enter the new history.

The unit suite verifies transcript flushing, noise gating, revisions, silent preparation, exact final validation, single consumption, serialized cancellation, corrected tool arguments, and bounded capture. Actual concurrent ASR/Gemma/Kokoro performance still requires the phone test.


## Opening speech and call continuity

The 20M recognizer is primed with 12,800 zero-valued samples before live audio.
This supplies 0.8 seconds of left context without a wall-clock sleep. On the
model's official 0.wav fixture, the unprimed decoder omitted "AFTER EARLY
NIGHTFALL"; priming restored it. Another fixture still misrecognized its first
word: this is a targeted onset fix, not proof of general dictation accuracy.
`scripts/check_asr_onset.py` reproduces the comparison. Four-path modified beam
search did not consistently improve these two fixtures and increased host decode
time, so greedy search remains the default.

The microphone now starts before VAD/ASR model construction. A bounded Channel
retains audio before the consumer subscribes (SharedFlow previously dropped
frames with no subscriber). Queue overflow produces an explicit capture error.
Listening is reported after microphone readiness and recognizer initialization.

A newly started call can use the latest nonempty ended call from the past 15
minutes as background, limited to six completed entries and eight entries total
with the current dialogue. It never copies old dialogue into the new saved call,
imports task state, or restarts a tool. Explicitly resuming an older saved call
continues to use the existing resume path.

Speculative and final voice generation share correction guidance. Gemma may use
raw audio and dialogue to resolve clear conversational mishearings. Latest
corrections replace earlier details without discarding the original task.
Native tool arguments still must match the final ASR transcript; uncertain
numbers/targets cannot be guessed. No extra inference or transcript-rewrite
pass is introduced.
