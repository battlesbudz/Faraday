# Audio overlap evidence and incremental LLM prefill — 12 October 2026

## What is established

Baseline inspected: `audio-pr2` `fd50f2389659479401b6598fc715aaae7c34561f`
(Build 1278 source). The reported roughly 2.5 seconds from stopping speech to
hearing a reply is user observation, not a new instrumented benchmark.

- `VoiceTurnPreparation` starts microphone capture before calling
  `prepareVoiceConversation`. Conversation creation synchronously prefills the
  fixed app-policy system instruction and waits for checked idle. This is real
  preparation, not just allocation. It excludes history, retrieved memories,
  current user audio and audio-embedding LLM prefill.
- The app displays its ordinary “listening” status **after** this preparation.
  Preparation therefore happens with the microphone open but may finish before
  the user starts speaking. Source order alone cannot prove physical speech overlap.
- Existing `GemmaStreamingAudioCapture`/`RetainedPcmEncoderWorker` incrementally
  encode retained PCM. The current app consumes a sealed projected-audio payload
  at final submission or a certified frozen-pause speculative submission. It does
  not incrementally prefill evolving live audio into the language model's cache.
- Smart Turn receives live capture frames. Endpoint-mode inference is admitted
  only after at least 350 ms of a qualified pause; shadow mode uses 200 ms.
  Resumed or uncertain speech invalidates the previous pause. Receiving a voiced
  frame, classifying a pause and accepting a final endpoint are different events.

## Added evidence, unchanged execution ownership

All added edges use Java `System.nanoTime` divided by 1,000,000 and align with the
existing turn origin. No waveform, words, model outputs, periodic timer or extra
model invocation is added.

The recognition stage reads existing capture state after the existing join:
`speech_started` is the first speech-confirming captured frame, `speech_ended`
remains the last detected speech, and `endpoint_decided` is the final accepted
proposal before final recognition/drain. No timestamp is reconstructed from the
silence setting.

The exported metrics distinguish:

- `early_gemma_preparation_capture_overlap_ms`: preparation intersected with the
  microphone-ready to recognition-finalized interval.
- `early_gemma_preparation_speech_span_overlap_ms`: preparation intersected with
  first-to-last detected speech. **Internal pauses remain inside this span.**
- `early_gemma_preparation_completed_before_speech`: identifies useful preparation
  completed before the detected utterance rather than claiming speech overlap.
- `early_gemma_preparation_after_speech_ms`: preparation remaining after detected
  speech ends.
- `speech_end_to_endpoint_decision_ms`,
  `endpoint_decision_to_recognition_finalized_ms` and
  `speech_end_to_answer_submit_ms`: separately expose endpoint wait, finalization
  and pre-submission work. The final metric is unavailable for ambiguous or
  earlier speculative submissions, following existing answer attribution.

Smart Turn records first-frame/first-possible-speech-frame observation timestamps,
bounded request-admission/completion edges and actual backend `infer` call/return
edges. The backend interval includes feature extraction and model invocation;
it excludes request queue and initialization, and is not CPU/GPU busy time.
Each of up to eight observations has backend duration, speech-span intersection
and post-speech remainder. A cancelled or stale completion is work evidence,
never authority to end a newer pause. Completion after turn finish remains in
its existing old-turn diagnostic receipt and cannot mutate the frozen benchmark.
The completion recorder now accepts the endpoint mode's full eight-request budget;
it previously accepted only the shadow mode's first three indices.

A measured non-overlap is zero. Missing, reversed, uncompleted or never-invoked
boundaries remain unavailable. New fields are additive; old records load without
inventing missing evidence. JSON/CSV/copy export keeps the stage offsets and
versioned definitions.

These are wall-time and detector observations. They do not prove isolated
continuous-voiced-frame overlap, acoustic onset, GPU parallelism, speedup, or that
the model knew the final end while the user was still speaking.

## Physical validation

Use the exact release candidate on the same phone/model/settings, with matched
cold/warm state and thermal condition. Retain exported per-turn data for:

1. An utterance begun immediately as capture opens, and one begun after the
   listening status. Confirm whether fixed preparation overlaps the detected
   speech span or has already finished before speech.
2. Continuous speech followed by one final pause. Confirm capture-frame
   observations during speech; expect Smart Turn inference after the pause.
3. An internal hesitation followed by resumed speech and a changed ending.
   Confirm the old verdict is invalidated and any internal-pause inference is
   distinguished from the final endpoint.
4. A short/quiet utterance, unavailable model, cancellation and busy/failure
   paths. Missing evidence must stay unavailable and fallback behavior must remain.

Compare last speech → accepted endpoint → recognition finalized → SDK send →
first raw text → policy release → first Piper PCM → first playback. Sum only
non-overlapping observed intervals; do not add overlapping encoder work or SDK
proxy durations. First playback is a playback-head proxy, not externally measured
acoustic onset. This evidence is required before attributing the reported delay
or claiming a reduction.

## Incremental language-model experiment

A supported native primitive is promising, but the current Android app has no
validated incremental projected-row path. Do not turn it on merely because
`RunPrefillAsync` exists. The smallest next experiment must establish equivalent
input ordering, attention/cache behavior and native ownership before device
performance is meaningful. The following source review and parity gate cover
that experiment; no new decoding architecture or production switch is included
in this change.

### Pinned source findings

The reviewed runtime is LiteRT-LM
`924e79c91542761242244e4f1651851f822e4cbb` plus the repository's source delta.

1. [SessionAdvanced successive prefill](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/core/session_advanced.cc#L89-L159)
   keeps the same session and chains dependencies through `last_task_ids_`.
   Middle chunks do not inherently recreate the KV cache; decode supplies the
   final prompt suffix when session templating is enabled.
2. The compiled-model executor explicitly retains a pending audio token's
   embedding across [prefill boundaries](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/executor/llm_litert_compiled_model_executor.cc#L488-L668)
   and manages [per-call multimodal lookup](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/executor/llm_litert_compiled_model_executor.cc#L1663-L1749).
   This supports the feasibility of a native experiment, not demonstrated parity
   for this model/backend.
3. [Attention settings](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/executor/llm_executor_settings.h#L166-L194)
   default to causal. The [mask implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/executor/litert_compiled_model_executor_utils.cc#L479-L565)
   applies its vision-bidirectional exception only to vision token -1, not audio
   token -2. All-bidirectional masking would break naive chunk equivalence. Actual
   model settings, exported graph and backend masks still need inspection.
4. [Gemma input configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/conversation/model_data_processor/gemma4_data_processor.cc#L331-L365)
   supplies an audio-start marker, with
   [native audio-row/end markers](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/executor/llm_executor_io_types.h#L264-L287)
   -2/-4. The reviewed repository patch lets `InputAudio(tensor, true)` skip the
   encoder; `InputAudioEnd(true)` emits the end marker without flushing that
   independent encoder. A row stream needs exactly one start and one final end,
   not a complete audio object per chunk.
5. [Kotlin Session](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Session.kt)
   exposes text/encoded-audio/image input. Jarvis's `LiteRtNativeVoiceSession`
   further restricts its adapter to strings. The reviewed projected-input path is
   a completed `Content.SealedAudioEmbeddings` Conversation message, requiring
   whole-PCM identity, `complete=true` and whole-payload row accounting. Its
   processor appends audio-end, and the native owner publishes rows at `Seal`.
   Incremental stable-row delivery and a corresponding JNI/session API are missing.
6. [ThreadedExecutionManager](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/framework/resource_management/threaded_execution_manager.cc#L71-L84)
   has one execution worker and a separate callback worker. Its
   [prefill task](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/framework/resource_management/threaded_execution_manager.cc#L806-L923)
   holds executor/context ownership through encode/combine/prefill. Async tasks
   allow microphone-time overlap; the API alone does not establish parallel
   encoder/LLM execution.

**Conclusion:** there is a credible native incremental-prefill experiment, no
proven model impossibility, and no validated Android implementation yet. Existing
sealed objects must not be repeatedly submitted as a substitute for that API.

### Minimal validation gate before integration

1. Use a native harness with the exact pinned SDK/patch, E2B artifact and backend.
   Capture the baseline formatted input order, including immutable system,
   history/user context, audio start, rows, one audio end and model-turn suffix.
2. Compare the same baseline against successive prefills replacing only the
   projected tensor with ordered row slices. Include one-row, encoder-sized,
   irregular and native-signature-boundary partitions, plus tiny and partial
   final chunks. Do not decode between input chunks.
3. Check exact row/token ordering/counts, positions, masks, single start/end and
   pending-token identity. Compare valid cache regions and next-token logits
   after the identical final suffix, first measuring backend repeatability.
   A GPU backend is not assumed bitwise repeatable. Require a justified numerical
   criterion, deterministic token comparison and representative understanding
   cases before any speed claim; do not weaken an existing acceptance oracle.
4. Only after parity, replay timestamped arrivals with stable committed encoder
   rows, immutable preceding prompt and one serialized model owner. Late history
   or memory inserted before those rows would invalidate their cache; determine
   that ordering before touching production. Cancellation/revised input drains
   and discards the experimental session. No partial audio authorizes tools or
   speech output before final turn acceptance.
5. Then compare phone latency, actually completed pre-endpoint LLM work, capture
   backlog, total compute and thermals. Small prefill calls may cost more or
   contend enough to erase expected savings. Preserve the sealed fallback and
   existing cancellation/quarantine safety until the full route is qualified.

Stop if the actual graph requires future-audio attention, previous rows are not
stable, exact prompt ordering cannot be preserved, or chunked logits differ beyond
established backend variation. No native/model parity test or performance run was
performed in this source review.

## Verification

Focused host tests compile actual production diagnostics, Smart Turn worker,
observer, lifecycle and endpoint-policy dependencies with Kotlin 2.3.21,
coroutines 1.9.0, JUnit 4.13.2 and Android-all 14. No replacement production/SDK
classes are used. The initial host compilation omitted two source dependencies;
its failure is retained and the complete source closure rerun. The focused suite
passes 65 tests, including missing/reversed edges, before-speech preparation,
post-speech work, pause-only admission, exact backend boundaries on success and
failure, eight completion records, stale/late ownership, and JSON/CSV round trips.
Architecture dependency checks and both Python helper suites (124 + 101 tests) pass.
The [host receipt](evidence/audio-overlap-2026-10-12/host-receipt.json) binds source
and dependency hashes; initial compilation failure and final outputs are retained.

This host result does not compile the complete Android orchestration closure,
load Smart Turn/Gemma weights, exercise native JNI or measure a Fold6. Full
exact-revision signed release JVM/native/packaging and Android sandbox gates,
followed by real-device/model evidence, remain required.
