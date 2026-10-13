# Voice follow-up to build 622

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

- Retain background Whisper decoding and its 900 ms no-transcript endpoint target from 622, including the lazy recognizer after an interruption. Intentional unfinished-sentence pauses retain their longer target. Interruption captures now forward background Whisper timing logs too.
- Filter explicit known sound captions from ASR partials/finals. A sound-only candidate such as `(crying)` or `[music]` is discarded without an answer, lookup, tool, audio-model fallback or speaker-profile training. The microphone stays open for real words; repeated captions cannot extend the ordinary 20-second initial listening deadline. Actual words around captions are preserved. Plain spoken `crying`, requests about music, and unknown bracketed words are preserved. Caption-only audio-model fallback results also rearm listening without a reply.
- Show final ASR text before joining speculative answer work. This affects display timing, not recognition accuracy.
- Check cancellation and call identity after asynchronous capture startup and finalization. A late model load cannot change the state of an ended or replacement call. The controller checks identity atomically with the guarded state transition.
- Preserve Paul's 622 fixed opening, speed, voice reference, no-period default and short-sentence grouping. Do not retune speaker thresholds from an unverified background transcript or change Kokoro pacing.

These rules do not identify ordinary background speech from its text or prove that a transcript is accurate. Speaker preference is still the acoustic filter and remains permissive when it has not learned a reliable profile. Phone testing should compare the same spoken question and room conditions using Paul with each ASR, verifying the build number and selected engines in each report.

Checks cover caption-only discard with audio fallback enabled, resumed real speech on the same microphone, bounded repeated-noise listening, preservation of actual spoken requests, the lazy recognizer silence target, and a late state update after Stop or a replacement call.
