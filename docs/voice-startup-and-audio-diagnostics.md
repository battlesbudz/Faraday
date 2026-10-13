# Slow replies and speech diagnostics

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

Build 602 call `252373d8-89cf-4e91-928a-0a99ddab78eb` took 19,748 ms from endpoint to accepted answer text. Gemma's initial first token was 5,155 ms; the repetition guard then blocked four sentences and made a second generation. Opening synthesis took 3,806 ms and startup buffering another 1,202 ms. Speech-end to actual playback was 26,355 ms. The end of that reply had a 366 ms estimated audio supply gap. Those events explain silence and a playback interruption, but cannot establish whether the generated voice itself was garbled. An underrun reported during terminal drain alone is not proof of an audible internal gap.

This change builds on 603, which replaces full background ASR during replies with the bundled Hey Jarvis and stop keyword models. The supplied 602 logs do not test those changes.

## Acknowledgment (always-initial policy)

Every confirmed, accepted voice turn now starts with `Um.` in the selected voice, including fast answers. The previous skip-on-fast-answer behavior and rotating initial phrases were superseded by the user's always-initial preference. The cue begins as soon as its cached PCM is available; there is no intentional 700 ms grace period. It is requested immediately after confirmation, before answer routing/inference work.

While waiting for answer PCM, the controller waits 2,500 ms after cue playback finishes, then plays cached `One second.`. If that clip is not available yet, it reuses the initial clip. This cycle ends when the first answer PCM arrives. An already-playing cue finishes before answer playback; no overlap is allowed. The remaining cue time counts toward startup headroom. These are neutral waiting phrases, not claims of ongoing searches or successful actions. This is a startup-wait policy, not filler injection into gaps in a story already being played.

Both clips are cached in memory and on disk by model version, directory, speaker and text. They load before the native TTS model. Only the initial clip must be synthesized on a cold cache; follow-up preparation uses the existing native owner, below ready answer text in priority. Cache failures are logged and release the answer rather than deadlocking it. No extra native model or synthesis worker is created. The first-ever preparation, OS scheduling, muted/unavailable output, and intentional microphone handoff mean this is a 2.5-second scheduling target, not a hard three-second acoustic guarantee. Cancellation stops cues and there is no cue before turn confirmation.

Acknowledgment logs are separate from answer text, PCM, and playback latency. Existing answer metrics do not count a cue as an answer. Fillers are not conversation history and cannot execute tools. The WAV export below remains answer-only.

The repetition guard still checks sentences before speaking. It requests repair only when filtering leaves no answer. Entirely repeated answers still require repair; `repairMs` measures that cost. Fillers do not change Gemma decode speed or Kokoro's sustained synthesis throughput.

## Synthesis speed comparison

`Compare voice speed` tests the currently selected engine with 2 and 4 CPU threads and 40- and 90-character openings, twice with reversed order. All cases use the same streamed text, normal playback speed, and a loaded model. Results preserve thread count, synthesis time, first PCM/playback timing, and real-time factor. The test does not change live settings or automatically choose a voice. Measure on the actual Fold 6; published desktop throughput is not a phone result.

Candidates for a separate measured model comparison:

- Kokoro INT8 variants: https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html . Quantization is an option, not evidence of a speed or quality win on this device.
- Pocket TTS: https://github.com/kyutai-labs/pocket-tts . The authors report streaming and about 200 ms first-chunk latency, with about 6x real-time on an M4 MacBook Air CPU. These are not Fold 6 benchmarks.
- Kitten TTS: https://github.com/KittenML/KittenTTS . CPU-oriented ONNX models range from 15M to 80M parameters; voice preference and native-runtime compatibility still require evaluation.

No replacement model is installed or selected by this change. Target sustained synthesis comfortably below one second per second of speech, with acceptable first-audio delay and the user's voice-quality preference.

## Save latest reply audio

The Voice Call screen has **Save latest reply audio**, next to **Copy diagnostics**. After a reply finishes or is interrupted, the app retains up to 30 seconds of the latest reply's PCM in private cache. It records samples successfully accepted by AudioTrack, trims unplayed queued audio using the playback head, and replaces the previous file. Empty turns do not replace the previous recording. Export snapshots the file before opening Android's save chooser.

This WAV contains the generated answer PCM, not microphone audio. It excludes the acknowledgment, playback starvation gaps, Android playback time stretching, acoustic echo, speaker distortion, and Bluetooth processing. A clean WAV with distorted live playback points toward a later playback stage; it does not rule out the reported problem. The diagnostic event identifies the turn, frame range, sample rate and those limitations. Attach the WAV and copied diagnostics from the same reply for investigation.

PCM logs now include non-finite sample count, clipped sample count, and leading/trailing quiet durations alongside RMS and peak. These numbers are useful evidence of invalid synthesis or excessive quiet boundaries; they do not by themselves identify intelligibility or garbling.

## Validation

Host tests cover turn confirmation, skipping a cue for a fast answer, late cache availability, one-shot playback, orderly handoff to answer playback, cancellation, bounded WAV retention, playback-head trimming, replacing the previous WAV, and repair only for an empty filtered answer. Android CI additionally compiles and tests the application, verifies native keyword models, verifies packaged ASR libraries, and builds the signed release APK. Device playback quality and perceived latency still require testing the resulting APK.

## Build 604 follow-up diagnosis

Call `6d2c7b4b-29db-4db9-86f9-3d7014fb0e04` shows a story with Gemma first token at 4,167 ms and estimated decoding at 10.16 tokens/s. Kokoro repeatedly takes 1.58–1.91 seconds of synthesis per second of PCM, including after Gemma finishes. Increasing the startup buffer cannot indefinitely compensate for sustained slower-than-playback synthesis. A bigger voice model is not automatically faster; compare actual device first-PCM latency and sustained synthesis speed before choosing a replacement.

The last reply's 6,481 ms endpoint-to-text includes an unnecessary lookup for `the person you were talking about`; this exact dialogue reference now stays local when conversation history exists. Explicit Wikipedia requests still route to lookup. Opening synthesis adds 3,876 ms and buffering adds 1,201 ms. The attached WAV has exactly 99,690 frames at 24 kHz (4.15375 s), matching the final two-phrase lookup-failure reply rather than the story. It contains zero clipped samples; that is not a perceptual quality assessment.

After the `stop` keyword, Moonshine emits partials and a 54-character final transcript. ReplyVoiceCapture previously omitted the callback that forwards those partials to the waveform caption. The callback is now connected with an active-output/session guard. Recognition summaries distinguish normal versus post-keyword capture and whether missing Moonshine text requires the Gemma transcription fallback.

A separately retained, bounded summary section preserves acknowledgment, input, model, lookup and TTS timings so a long story's underrun events do not erase the evidence needed to diagnose its startup.
