# Voice turn endpointing

Installed Smart Turn now participates in eligible native-audio endpoints by default.
See [the current model authority and fallback contract](verification/smart-turn-endpoint-2026-10-10.md).
ASR-text mode keeps its existing transcript policy.

> Current policy: [voice-pipeline-current.md](voice-pipeline-current.md). Ordinary calls use no added software gain; the shared acoustic gate retains a call-scoped room floor across listening turns. Complete/stable text targets 350 ms, settling 1100 ms, uncertain 1500 ms, unfinished 1800 ms, explicit hesitation 3500 ms; no-text is 900 ms for Whisper and 3000 ms for Moonshine. These are targets before final decoding/speaker checks. Long speech is segmented, not ended at the 25-second raw-audio bound. The older tuning history below is retained for evidence.

Audio PR2 uses bundled Silero VAD through Sherpa-ONNX 1.13.7. RMS and peak are diagnostics only: the previous fixed energy threshold could classify continuous room noise as speech and prevent a turn from ending.

- Input: 16 kHz mono signed PCM16, framed into 512 samples (32 ms).
- Strong speech: three consecutive frames with probability at least 0.5 (96 ms). Quiet speech now uses bounded input gain and stable ASR words to corroborate weaker VAD; see [voice-duplex-and-quiet-speech.md](voice-duplex-and-quiet-speech.md).
- Turn end: adaptive acoustic silence plus stable transcript cues. A complete-looking question, sentence, or short reply can finish after 350 ms of silence once its text has been stable for 300 ms. Uncertain text waits 1500 ms; unfinished phrases, hesitation, and missing text wait 3000 ms. These are conservative local text rules, not a trained semantic turn detector. Model classification latency is additional. A turn ending does not end the call.
- Pending microphone audio blocks automatic endpointing. Read timestamps keep an ASR decoding stall from appearing to be acoustic silence. A new speech candidate above the existing VAD threshold restarts the silence window, including before three-frame confirmation finishes. Resumed speech immediately invalidates a prepared answer/audio opening, even before ASR changes its text.
- Call inactivity: 20 seconds waiting for a recognized turn, on initial and follow-up capture. A completed Jarvis response starts a new listening window; generating/speaking time is excluded.
- Idle audio: bounded 1200 ms pre-roll, preserving speech onset without accumulating an entire idle call.
- Active audio: bounded 25 seconds; a continuously positive detector is segmented at that limit. Raw microphone audio stays in memory.
- Empty ASR after confirmed speech: live voice sends the bounded audio to Gemma for recovery. Captures explicitly requiring a transcript retain the existing recognizer recovery path. Inactivity is measured from the last detected speech. At inactivity expiry, save/end the call and stop automatic re-arm.
- Spoken ending: whole utterances `goodbye`, `goodbye Jarvis`, `stop listening`, or `stop listening Jarvis` (including leading Jarvis) save/end the call before model execution. Mentioning goodbye within another request does not hang up. Manual End remains immediate.
- The model runs on one CPU thread off the UI thread. The microphone reader owns recorder release; capture waits for it before allowing a subsequent turn to acquire the mic.

See [voice-latency.md](voice-latency.md) for speculative opening audio, timing definitions, and phone benchmarks. An explicitly supplied `trailingSilenceMs` still selects fixed endpointing for controlled tests.

## Bundled model

`app/src/main/assets/voice/silero_vad.onnx` is the k2-fsa 16 kHz Silero VAD v4 export:

- Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
- Size: 643854 bytes
- SHA-256: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- Model license: MIT; included next to the model as `SILERO_LICENSE.txt`.
- Integration API: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/kotlin-api/Vad.kt

The APK contains the model, so calls require no extra setup or network download.

## Validation

The focused Kotlin suite covers PCM normalization and odd chunk boundaries, speech confirmation, rejection of isolated positive frames, natural pauses and resumed speech, loud nonspeech decisions, initial silence, long idle pre-roll, the 25-second cap, detector failure, and cancellation/release.

A host ONNX Runtime check of the bundled model used 15 seconds each of seeded white noise (PCM RMS approximately 3300) and fan-like noise, then the first 10 seconds of the public Silero speech test recording followed by five seconds of loud white noise. Both standalone noise cases produced zero confirmed speech frames. The mixed clip produced 187 confirmed speech frames; its final confirmed frame ended at 10.368 seconds, giving a 1.2-second endpoint at 11.568 seconds. Audio chunk scheduling can add up to a chunk interval on-device.

Speech fixture source (not bundled): https://github.com/snakers4/silero-vad/blob/master/tests/data/test.wav

Host fixtures do not reproduce the user's room. Phone acceptance: ask a question, stop speaking while normal background sound remains, hear the reply, then ask a second question after automatic rearming. Copy diagnostics if the turn remains stuck; `capture_level` now includes `vad=silero` and its speech probability.


## Build 555 diagnostic follow-up

A false speech detection immediately after playback produced an empty transcript
and a 1.2-second endpoint. MainActivity returned "didn't hear", and the UI treated
that as a terminal failure, leaving the call without an armed microphone. Empty
candidates now stay inside the capture session until recognized speech or the
20-second inactivity deadline. `empty_candidates` appears in retained ASR metrics;
model-load and decode costs include internal retries.

Regression coverage includes an empty post-playback candidate followed by real
speech without restarting the microphone, repeated empty candidates preserving
the 20-second deadline, and a 2.5-second thinking pause followed by resumed speech.
The older VAD timing tests explicitly use a 1.2-second test configuration; the
production policy is tested separately at 3 seconds.

Opening-word loss in the supplied phone transcripts is not claimed fixed here.
Zipformer decoded at about 0.06–0.07 realtime factor in those measurements. A host
fixture with 0, 2, 5, 10 and 20 seconds of leading silence retained its opening
words after current priming. The phone recordings were not provided; comparison
with Moonshine remains necessary to separate recognizer accuracy from capture.
