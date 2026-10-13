# Speech recognition diagnostics

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

Moonshine Small Streaming is the sole recognizer. Its first use downloads 142.3 MB;
eight runtime files are pinned to the 2026-08-21 English quantization, with length
and SHA-256 verification before loading. Subsequent use is offline.

Moonshine receives the same continuous 16 kHz mono PCM, including opening audio,
through AudioTurnCapture. Silero still owns Jarvis's 3-second turn endpoint.
Moonshine also segments lines internally: its adapter combines lines by stable ID,
replaces provisional text, and forces a final update at Jarvis's endpoint. No
native line event independently submits a tool action or a Gemma response.

## Comparing on a phone

1. Make a call and say a test phrase.
2. Open Speech recognition diagnostics. Newer/Older selects measurements.
3. Enter **What I actually said**, including false starts and corrections, and
   save the reference to compute word-error rate. Copy diagnostics includes the
   last 20 measurements across calls and app restarts.

Old Zipformer measurements remain labeled as retired. Its saved selection is
ignored and its downloaded model directory is removed on recognition setup.

Useful phrases cover greetings/opening words, a corrected noun ("tell me a story
about pirates, no, astronauts on the moon"), names, and numbers. Do not use
consequential commands just to benchmark transcription. These are normal live
calls, not a sandboxed tool test. Conversation context, Gemma speculation, CPU
contention, cold starts and thermal conditions can affect response latency.

Measurements:
- `model_load_ms`: recognizer construction.
- `capture_ready_ms`: microphone startup plus detector/recognizer preparation;
  excludes downloads and earlier Gemma initialization.
- `speech_detected_to_first_partial_ms`: from Silero's first speech detection to
  the first published nonempty live hypothesis. Unavailable if text arrives only
  during finalization; this is not a precise acoustic word timestamp.
- `decode_ms`, `max_decode_chunk_ms`: synchronous ASR accept calls, excluding
  Gemma callbacks; elapsed wall time, not CPU profiler time.
- `finalization_ms`: final flush and final transcript publication.
- `decode_realtime_factor`: accept plus finalization time / all audio fed,
  including silence. Long idle periods affect this denominator.
- `final_to_first_text_ms`, `final_to_playback_start_ms`: after final ASR flush
  and before first answer text / observed playback-head movement. Exclude silence
  and finalization. Older builds measured audio submission instead; neither is
  an acoustic measurement from the speaker.
- `empty_candidates`: false/empty detections retried inside the same microphone session, without restarting call inactivity. Load/decode totals include retries.
- `prepared`: whether speculative Gemma preparation matched the final transcript.

Accuracy is unscored until a reference is entered. Word-error rate is word-level
Levenshtein distance / reference word count, lower is better and can exceed 100%.
Case/punctuation/apostrophe style are ignored; numbers and contractions are not
semantically normalized. The score does not measure successful understanding.
No raw recordings are persisted for this comparison feature.

## Native packaging

Moonshine Android 0.1.5 and Sherpa 1.13.7 both ship `libonnxruntime.so`, but their
native libraries require different ELF symbol versions (1.23.2 and 1.27.1).
`pickFirst` or substituting the newer runtime is not ABI-compatible. The Gradle
extraction task runs `scripts/prepare_moonshine_sdk.py` on the official Maven AAR.
It gives Moonshine's runtime a distinct SONAME and updates its consumers' NEEDED
and version references using an equal-length dynamic-string replacement. No
machine code, symbol versions, ELF offsets or segment alignment changes.
Sherpa/Kokoro keep their original runtime. The build fails if the expected ELF
layout/name changes. `scripts/check_asr_apk.py` verifies the actual APK's dependencies
and versioned runtimes in debug and release CI. Python 3 and readelf are required
for preparation and verification (available on GitHub's Ubuntu runner).

The adapter compiled against the shipped Maven classes; the real Moonshine 0.1.5
Linux native library transcribed the existing 0.wav fixture with streaming updates,
retaining "After early nightfall" and recognizing "brothels". This host smoke test
does not validate Android device latency or replace Fold 6 testing.

Sources: https://github.com/moonshine-ai/moonshine and
https://moonshine-voice.readthedocs.io/en/latest/models/available-models/.
