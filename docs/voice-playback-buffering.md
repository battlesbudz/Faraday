# Kokoro playback queue

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

The previous implementation loaded a native engine for every phrase and ran two
four-thread synthesis jobs concurrently. A phone trace showed 19.2 seconds to
produce 11.1 seconds of audio and a 9.6-second wait at playback. That measurement
included engine loading; it did not establish Kokoro's steady-state speed.

The new path creates one engine per response, preloaded while listening, on a
dedicated native-owner thread. All generation calls and release run sequentially
on that thread. This removes repeated loading and overlapping native calls;
repeated-generation stability still needs verification on ARM64 Android.

Build 547 exposed a callback ABI incompatibility: Sherpa 1.13.7 looks up
`invoke([F)Ljava/lang/Integer;`, while Kotlin 2.3.0's default invokedynamic
lambda exposes `invoke(Object): Object`. A compiler probe reproduced the
missing typed method. The native crash on the first phrase is consistent with
this failure; Android process-exit diagnostics now provide additional evidence.

The output path uses `generateWithConfig` without a JNI callback. Each bounded
sentence/clause completes and enters a two-chunk PCM queue while playback
continues. The one native owner and engine reuse remain. Queue cancellation
unblocks the producer before release; native generation itself must return
before its engine can be freed. AudioTrack writes remain nonblocking and
cancellable, so already-playing audio stops without waiting for synthesis.

Text chunking targets a 40-character opening. Subsequent live chunks are capped
between 40 and 180 characters using remaining produced PCM and the previous
phrase's measured synthesis time per character, reserving 25% scheduling headroom.
This avoids jumping from a tiny opening to a long synthesis call. At forced word
boundaries the chunker waits for a small lookahead and includes a nearby sentence
ending, avoiding an isolated tail such as "price." that pays another native-call cost. Punctuation and
whitespace boundaries preserve words; the benchmark retains its controlled chunk sizes.
Startup headroom is bounded at 1200 ms, or 2000 ms for an opening under 1.2 seconds.
A ready second phrase or producer completion releases it early. This deliberately
trades some first-playback latency for continuity; cached openings use the same rule.
Buffering cannot guarantee continuity when production is persistently too slow.
The same native owner can synthesize one bounded opening while listening. Its PCM is
unavailable for playback until the final request passes routing, its draft is consumed,
and the actual spoken opening matches exactly. Corrections discard it.
The separate 120 ms artificial inter-phrase padding is removed. Voice speed is
unchanged.

Diagnostics separate `loadMs`, `synthesisMs`, `queueWaitMs`, callback latency,
and PCM occupancy. `audio_chunk_budget` records live text sizing, and
`audio_underrun` records increases in AudioTrack underruns. The session summary retains aggregate synthesis/audio times
even if per-phrase entries roll out of the diagnostics buffer. Queue waiting is
excluded from synthesis realtime factor. Values below 1 mean synthesis is ahead
of normal-speed playback; buffering cannot fix sustained values above 1.

The response-speed benchmark compares 28/40/70-character openings twice, reversing
the order on the second pass. It waits for model loading before feeding the same
text in four-character fragments every 32 ms. This simulates streamed text; it is
not a measurement of Gemma generation speed. First-text-to-PCM, first-text-to-playback,
prepared synthesis cost, reuse, gaps, and playback confirmation are recorded separately.

Validation: unit tests cover token-split decimals, first clauses, complete text
preservation, final flushing, chunk adaptation, queue ordering and cancellation
while full. Android CI runs the unit suite and builds the signed APK. On-device
acceptance: request a long pirate story, listen across every sentence boundary,
end playback midway, then start another call. Compare session RTF and underruns
against build 543. One-versus-two-worker throughput and thermal behavior cannot
be established by desktop tests; the new default is one worker to remove the
observed competing loads. No claim of gap-free device playback is made yet.

Microphone readiness now requires 300 ms of captured PCM, retained for ASR,
before the UI receives Listening. The UI no longer sets Listening on the button
press or automatic rearm. ASR receives every frame from capture startup; VAD
still gates visible hypotheses and turn submission, preserving opening speech
that precedes VAD confirmation. The microphone buffer is one second and the
bounded handoff holds 6.4 seconds to absorb temporary ASR decoding stalls.
