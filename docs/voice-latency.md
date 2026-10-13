# Voice latency on the existing stack

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

The stack remains Moonshine Small Streaming, Gemma 4 E2B, and the selected
Kokoro/Pocket Paul voice. No model weights or runtime dependency versions change.

## Adaptive turn ending

`AdaptiveTurnEnd` combines text stability with acoustic silence. Its initial tuning:

| Transcript evidence | Silence target |
| --- | ---: |
| Complete-looking question/sentence or short answer, stable for 300 ms | 350 ms |
| Otherwise complete-looking but still changing | 1100 ms |
| Uncertain text | 1500 ms |
| Hesitation, unfinished phrase, or no transcript | 3000 ms |

These are deterministic English text cues, not universal semantic understanding.
Incomplete endings take priority over punctuation; for example, “Can you tell me?”
keeps the longer pause. Resumed acoustic speech restarts the window. A queued
microphone backlog blocks automatic acceptance until capture catches up. Explicit
stop, bounded maximum turn duration, and call inactivity retain their own policies.

Moonshine updates every 250 ms instead of 500 ms. Draft updates coalesce for 300 ms
instead of 600 ms. The existing ASR decode-time metrics remain available to detect
extra CPU load on the phone; these intervals are tuning choices, not speed claims.

## Silent opening preparation

`VoicePreparation` retains only the newest hypothesis. A changed/shortened hypothesis
or resumed speech invalidates prior text and opening audio immediately. Native Gemma
cancellation is joined before replacement generation begins. The audio handle also
rejects a late synthesis completion after cancellation.
Final ASR text is validated separately from partial updates; a final question mark
can settle without throwing away matching work. Changed words or numbers still fail
validation.

`SherpaKokoroVoiceOutput` preloads its single native engine while listening, then
selects between confirmed text and a conflated opening-preparation queue. Preparation
does not create an AudioTrack, send PCM to playback, update captions, save conversation
text, or execute a tool. At most one opening (up to 12 seconds of PCM) is retained.

Final transcript matching alone does not permit playback: final request routing must
accept and consume the draft. Only then can an exact match against the actual first
spoken phrase consume its PCM once. Rejected factual/action routes and corrected text
generate their own speech. Tool calls remain data until the existing final transcript
and tool guards approve execution. Speculative tool output discards its opening.

The live opening target is 40 characters, down from 70. Later chunks retain natural
boundaries and lengths capped by remaining produced audio and measured synthesis cost.
Following reports of gaps, startup headroom is now bounded at 1200 ms, or 2000 ms
for an opening under 1.2 seconds, including reused openings. A ready second phrase
or producer completion releases it early. This prioritizes continuity over minimum
first-playback latency. Sustained synthesis slower than playback can still cause gaps
and needs phone measurements; see [playback buffering](voice-playback-buffering.md).

### Why the JNI callback stays disabled

In [Sherpa 1.13.7's Kokoro implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/offline-tts-kokoro-impl.h),
the callback runs **after** `Process` has synthesized a sentence batch. It does not
deliver progressively earlier samples from inside that batch. The bounded phrase
queue already provides incremental delivery between synthesis calls. Keeping the
established `generateWithConfig` path avoids reintroducing the earlier Kotlin/JNI
callback ABI crash for no demonstrated first-phrase benefit. No claim of native
sample-by-sample Kokoro generation is made.

## Timing definitions

Call records add `endpoint_detection_ms`, `target_silence_ms`, `endpoint_cue`,
`speech_end_to_first_text_ms`, and `speech_end_to_playback_ms`. Existing
`final_to_*` timers remain, so silence detection and post-endpoint work can be
distinguished. The speech origin is the last detected-speech chunk's monotonic
microphone-read timestamp. This approximates the acoustic boundary; AudioRecord
and VAD have their own delay.

Playback confirmation waits for the AudioTrack head to pass the first sample above
a small amplitude floor, rather than counting leading silence as speech. This is
device playback progress, not an external microphone measurement of speaker or
Bluetooth latency. Cold start, Bluetooth routes, corrections, tool calls, and thermal
throttling should be evaluated separately from ordinary warmed-up conversation.

## Phone benchmark steps

End the call, open Voice settings, then **Voice and response speed**:

1. **Compare opening lengths** uses the selected voice. It compares 28, 40, and 70
   characters with identical streamed text and a warm TTS engine. The second pass
   reverses the order. Listen for prosody, pronunciation, and gaps; copy results.
2. **Compare Gemma acceleration** uses the same installed model on the GPU with
   MTP explicitly off, on, then off again. Each block loads once, excludes a warm-up,
   and runs two passes of the same short answer, spoken explanation, and simulated
   battery-tool request. It records initialization, first token, first opening text,
   total generation, estimated decoding rate, memory, thermal status, and output.
   Token counts remain estimates because the adapter does not receive token IDs.
3. An incompatible model export reports an error for MTP; it is not counted as a
   successful accelerated run. The benchmark neither downloads replacement weights
   nor changes call settings. Tools receive a fixed simulated result, never a real
   device action. The process-global SDK flag is scoped around engine initialization
   and restored on failure/cancellation; the idle call engine is released first.
4. Start an ordinary call and test a short question, a hesitation mid-question,
   a corrected target, an interruption while Jarvis speaks, and a long response.
   Copy the call diagnostics, including the speech-end-to-playback event.

[LiteRT-LM 0.12's flag source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.12.0/kotlin/java/com/google/ai/edge/litertlm/ExperimentalFlags.kt)
documents that forcing MTP requires a compatible model. In that version
[Engine.initialize](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.12.0/kotlin/java/com/google/ai/edge/litertlm/Engine.kt)
reads the flag. Google's published [up-to-2.2x decoding result](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
uses an S26 Ultra, not the Fold 6 and not the entire voice pipeline.

Host tests verify endpoint policies, cancellation, exact-once authorized PCM reuse,
text preservation, and existing tool guards. Real Fold 6 latency, synthesis quality,
and acceleration gains require the phone benchmark; no sub-500-ms result is asserted.

For subsequent quiet-speech, continuous interruption detection, and cancellation recovery changes, see [voice-duplex-and-quiet-speech.md](voice-duplex-and-quiet-speech.md).
