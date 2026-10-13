# Pocket TTS — Paul

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation notes: the continuous-state, zero-startup-wait and generated initial filler descriptions below are superseded. Current call defaults use fresh decoder/sampling state per sentence group, adaptive headroom starting at 200 ms, and a bundled Ummm clip available for gaps. Gap cues are optional; ready answer audio preempts even an active initial cue. Saved diagnostic profiles can override call settings. See the [implementation plan](local-voice-implementation-plan.md) for current decisions and evidence.

## Current source-audio comparison

**Compare Paul source audio · 14 runs** in Voice and response speed diagnostics compares fixed one/grouped submissions and fresh/retained synthesis state using upfront text, at two threads, speed 1.0 and 200 ms headroom. It leaves saved call profiles intact. Export each run’s audio + diagnostics for source pause/level ranges, planned submissions, thermal status, build and artifact identity. First intelligible word timing remains unavailable until listening is assessed. See the Phase 6A evidence entry in the implementation plan for cases, method and outstanding validation.

Pocket is an optional voice in Voice Call → Voice settings → Voice. Select **Pocket TTS — Paul**, then start a Voice Call or run the voice comparison. Kokoro remains the default and retired Miro selections fall back to Kokoro. Custom voice creation is not part of this change.

## Download and identity

The first use downloads approximately 99 MB (98,336,520-byte INT8 archive plus 717,182-byte Paul reference) outside the APK. Allow approximately 310 MB free during installation for archive plus extracted models. After installation, synthesis and Paul's voice conditioning work offline. Each download has a pinned SHA-256, expected size, temporary staging, cancellation checks and atomic completion. Demo reference voices are not installed.

Model: `sherpa-onnx-pocket-tts-int8-2026-01-26`, seven ONNX/token files. Paul is the official Kyutai label for `vctk/p259_023_enhanced.wav`, speaker p259, pinned at revision `a0de156151266cf8eb27ac8f27312f7aff2ef7b8`. His 32 kHz recording is loaded once per TTS session and resampled by Sherpa. Native reference embedding caching is limited to one voice. See `PocketVoiceSpec.kt` for manifests and checksums.

## Live playback

Paul uses one native audio session per answer. Text arrives at natural sentence
boundaries, with no 40/60/90/240-character release rule. Already available sentences
are conditioned together, so a backlog is not regenerated as many tiny requests.
The voice-conditioned prompt is cached; Mimi decoder state and the sampling sequence
continue between text units. Each answer
has a unique session ID; stopping or ending a call discards that state. Fillers are
isolated and cannot reset or contaminate the answer. Detached prepared Paul openings
are disabled because splicing those clips would lose the continuing acoustic state.
Gemma text preparation is retained.

The pinned Sherpa 1.13.7 native callback path originally generated all of a sentence's
latents before decoding any PCM. `native/sherpa/pocket-streaming.patch` adds an opt-in
path that decodes during latent generation. The first callback carries 3 latent frames
(240 ms PCM), then up to 5 frames per callback; these are audio frames, not characters.
The CPU uses 2 threads, 5 flow steps, temperature 0.7 and seed 42 at the start of an
answer. Continuing the random sequence avoids reseeding every phrase. The text LM
restores the same cached voice prompt for each new text unit, following upstream's
`copy_state=True` default. Carrying a completed text/EOS state into the next sentence
was tested and rejected because it truncated later speech. No synthesized prefix is
replayed or regenerated. Android performance and perceived prosody still need phone
listening tests.

The callback uses a kept Java `invoke(float[]) -> Integer` method. PCM is copied once
into the bounded playback queue; the returned full utterance is checked and never
played again. Callback cancellation unblocks the native owner before release. Android
uses normal playback speed for live Paul calls and no artificial startup wait.

## Acknowledgements and diagnostics

Both acknowledgements and answers use Android's media speech attributes and the user's
media volume. Fresh short filler clips have silent padding trimmed and quiet speech
boosted within a bounded gain; silent, corrupt or overlong clips are rejected. The v2
cache and new policy ID invalidate earlier clips. “Um, one second.” remains the initial
cue; isolated “Um.” was unreliable with this model. Cold installation still requires
model loading and the initial filler synthesis.

`acknowledgement_speech_frames_rendered` means Android's playback head passed non-silent
PCM. It reports route, media usage and PCM level; it is not a microphone measurement of
what the user heard. Reply latency excludes fillers. Each reply's footer separates
Gemma TTFT, subsequent model passes, text readiness, TTS and playback. See
[per-reply latency](per-reply-latency.md).

## Benchmarks

The same fixed texts and input pacing are used for both remaining voices. Explicit
character-opening profiles are experiments; they never change Paul's live policy.
Full-text-before-playback remains a deliberately buffered baseline. The four **Paul
native audio streaming** profiles (2/4 threads × 1.0/0.9 playback) exercise the live
natural-sentence/continuous-state path. Each copied diagnostic belongs to one immutable
text run, with suite, profile, voice, pass and text hash. Heat is recorded and never
pauses a requested benchmark.

## Building and verification

Install Android NDK 27.2.12479018, then build normally with Gradle. `buildSherpa` downloads
checksum-pinned Sherpa source and matching ORT, applies the checked patch, and compiles
JNI. The official AAR supplies Kotlin classes only; its older buffered JNI is never
packaged. Kokoro, Silero and other Sherpa users retain their existing implementation.
CI caches this native build and checks the APK for both the callback ABI and patched
streaming markers, including after release shrinking. Sources/models are not swapped.

The host check uses the actual pinned model and Paul reference. It compares first PCM
with the original callback path; checks finite PCM, exact callback/return equality,
decoder/sampling continuation versus a fresh session, deterministic new answers, filler isolation,
early cancellation/recovery, and a multi-sentence story. To reproduce:

```sh
python3 scripts/build_sherpa.py --output /tmp/pocket-host
/tmp/pocket-host/cmake/bin/jarvis-pocket-stream-check MODEL_DIR PAUL_WAV
```

Host timing does not establish phone latency. The logs that motivated this change had
critical thermal status and large Gemma/retry delays in addition to TTS delay. A
0.25-second Gemma TTFT is not promised by changing speech streaming.

Sources and attribution: [Pocket TTS](https://github.com/kyutai-labs/pocket-tts),
[Sherpa 1.13.7](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7),
[ONNX conversion](https://huggingface.co/KevinAHM/pocket-tts-onnx),
[Paul reference](https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p259_023_enhanced.wav).
The native source retains its Apache-2.0 notices. Paul/model CC BY 4.0 notices are
bundled in `assets/licenses/pocket-tts-*`; downloaded license files are preserved.

Host verification for this patch passed with the pinned INT8 model and Paul reference.
The full 519-character story delivered its first 5,760 PCM frames in 189 ms, then
streamed 28.8 seconds of audio across 73 callbacks; generation took 12.01 seconds.
These are warm Linux host measurements, without Android playback or Gemma. The tests
also passed callback equality, non-truncation bounds, continuation/isolation and
cancellation recovery. Android CI separately verifies compilation and APK packaging.
