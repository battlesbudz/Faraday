# Response latency follow-up

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

The build-607 story took 397 ms to endpoint and 7,475 ms from the detector's last speech frame to confirmed answer playback. Cached acknowledgement playback began earlier. These are software timestamps, not microphone measurements of speaker output.

Changes:

- Request cached acknowledgement as soon as a nonempty, non-goodbye transcript is confirmed and capture has stopped, before speculative cleanup or conversation reset. The later request is idempotent and still covers audio-only recognition fallback. Tools remain behind final validation.
- Give the TTS text consumer an explicit whitespace boundary after each repetition-checked sentence. Previously a complete sentence could sit in SpeechChunker until the next sentence arrived; this fix preserves the repetition check and Pocket's complete-sentence policy.
- Skip decimal points when seeking the next sentence boundary, instead of holding the remaining answer until inference finishes.
- Let answer PCM cancel a follow-up waiting clip and wait for its AudioTrack release. Preserve the user-required initial acknowledgement and avoid simultaneous playback.
- Retain a current valid speculative draft when only final punctuation/capitalization changes. Word/number changes and resumed speech still invalidate it.
- Ask Gemma for a short, substantive first sentence, then the requested detail. This is guidance, not guaranteed model behavior or an extra generation pass.
- Record first raw token availability separately from speech-ready text, and preparation-seal/reply-dispatch timings.

No change to turn-ending thresholds, Pocket voice/reference/seed/steps, tool authorization, keyword interruption, benchmark profiles, or persistent conversation limits. A smaller Pocket decoder chunk was tested locally and rejected: warm startup improved only slightly while PCM changed (maximum sample difference about 0.178), risking a voice-quality regression. The current 15-frame chunks remain.

Focused tests cover immediate sentence release, decimals, repetition suppression, mandatory initial cue, follow-up cancellation, draft retention and correction invalidation. Device latency measurements remain required; no fixed millisecond improvement or three-second cold-start guarantee is claimed.
