# Keyword interruption and background speech

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

Build 602 demonstrated a 7.349-second recognition call for 4.6 seconds of audio,
followed by microphone-buffer overflow. The old listener then waited indefinitely.
The live reply path now uses bundled microWakeWord models for **Hey Jarvis** and
**stop**, without loading Moonshine or transcribing speaker echo before a match.
Unprompted arbitrary-speech interruption is deferred; the older ASR gate remains
isolated from the production reply path for regression/reference tests.

Keyword detection pauses/flushes output immediately and signals the existing reply
coordinator to cancel generation. It does not wait for native synthesis to return.
That native owner still releases safely after returning. The same microphone then
feeds the subsequent request to lazy Moonshine ASR. The matching 100 ms chunk and
all earlier reply/keyword audio are consumed rather than replayed as a new command.
Say the keyword, wait for speech to stop, then give the next request. This is a
triggered interruption mode, not full conversational duplex or speaker verification.
No trigger or unfinished correction executes a device action.

Both keyword models retain their published settings and native startup warmup
(approximately 3.1 seconds of captured audio). `barge_keyword_ready` reports actual
readiness, separately from microphone readiness. Only **Hey Jarvis** and **stop**
are supported; **Jarvis** alone is not a separately trained keyword. Any speaker can
trigger them. Hardware echo cancellation is requested; a displayed enabled flag is
not proof of effective echo removal on every route. False activation when Jarvis
itself says a keyword remains an acoustic test case.

Capture failures finish cleanup and reopen the listener with 0.5–5-second backoff.
Explicit cancellation ends retries. A failure after a confirmed trigger discards the
incomplete correction and returns to the normal call loop; it never replays an action.
A complete reply cancels and joins listener cleanup as before.

Normal call input now requests Android noise suppression where available, logs its
actual enabled state, and uses noise-aware gain capped at 3x. A rolling lower
percentile estimates the room floor; audio near that floor receives no boost.
All original speech samples remain available—there is no loudness gate deleting
words. This limits the former 8x room amplification but cannot exclude another
person speaking. Whisper sensitivity and changing-room behavior require phone tests.

## Speaker focus decision

Loudest/first voice is not reliable identification, and topic-based rejection can
silently remove legitimate topic changes. Proper focus needs speaker embeddings
matched to an enrolled or explicitly selected speaker; overlapping speech may also
need target-speaker extraction/beamforming before ASR. Diarization labels speakers
but does not by itself remove an overlapping voice. No unbenchmarked identity or
semantic discard model is added to this already overloaded phone pipeline.

Typical systems separate echo cancellation, noise processing, voice activity,
keyword detection and recognition. References:
- WebRTC audio processing: https://webrtc.googlesource.com/src/+/refs/heads/main/api/audio/audio_processing.h
- Home Assistant keyword interruption: https://www.home-assistant.io/blog/2025/06/25/voice-chapter-10/
- Speaker tagging: https://docs.nvidia.com/deeplearning/riva/user-guide/docs/asr/asr-overview.html

## Validation

Host tests cover no ASR before keyword, stopping before ASR load, trigger exclusion,
ordered following audio, cleanup on failure, recovery after cleanup, cancellation
without restart and steady-noise gain. CI initializes both models in the actual
native engine, processes five seconds of silence and checks model digests. This
verifies compatibility, not real-world keyword accuracy. Android debug/release tests
and packaging remain required.

Phone checks: long story without interruption; then separate attempts with Hey
Jarvis and stop; repeat with background voices and low-volume speech; deliberately
end a call during recovery and confirm it remains stopped. Copy keyword readiness,
match, recovery, noise-suppression and TTS underrun events if a failure remains.
