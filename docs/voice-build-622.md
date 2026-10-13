# Voice follow-up to build 621

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

Whisper live capture uses one background native worker and overlapping, growing utterance windows. It begins after 1.5 seconds of speech-context audio and schedules the newest snapshot after at least 1.2 seconds of new audio. A busy decoder never queues intermediate snapshots or blocks microphone ingestion. Two successive hypotheses must agree before words become a partial. The final decode includes every captured byte; it reuses a result only when its sample end matches exactly. Windows retain at most 25 seconds. This is incremental use of an offline recognizer, not a native streaming Whisper model. Same-recording benchmarks deliberately remain offline for comparability.

The no-transcript silence target for Whisper is 900 ms. Existing incomplete-sentence hesitation handling, microphone backlog protection, speaker preference checks and action validation remain active. Diagnostics report partial decode duration and final queue wait separately. More concurrent CPU work and final decoding still need Fold 6 timing checks; no fixed latency improvement is promised.

Ordinary conversation now creates a Gemma conversation without registered phone tools. Current explicit action intent enables tools and rebuilds context when the policy changes. Invalid-tool fallback removes the tool configuration rather than relying solely on an instruction to avoid tools. Final action-intent validation remains unchanged.

Paul defaults to no artificial leading period. Tiny sentences (fewer than four words) wait for an adjacent sentence when one is still arriving; final short replies still flush. Audio remains callback-streamed within each text submission and decoder resets remain visible. Regular sentence pacing is unchanged. The period on/off comparison uses identical sampling and decoder settings, including the reported opening: “I understand. I can keep up with what you are saying and process your requests. I am ready when you are.” Existing user-applied profiles remain authoritative.

## Fixed opening and provenance

`app/src/main/assets/voice/paul-umm-v1.wav` is mono 24 kHz PCM16, 18,107 frames (754 ms), SHA-256 `9fcc714ad8ef2188bd706a0fe50156442efe7368a58567ba0f5c15ae4929c7fe`. Live Paul calls and the settings preview use this same pinned asset. It bypasses synthesis and the old filler cache. Follow-up “One second.” still uses the cache. Answer PCM diagnostics exclude fillers; acknowledgement diagnostics identify `source=bundled_paul_umm_v1`.

Source: Pocket TTS int8 2026-01-26, sherpa-onnx 1.13.7 CPU, two threads, seed 42, temperature 0.7, five steps, official Kyutai Paul/VCTK p259 reference pinned in PocketVoiceSpec. Generate “The sound is ummm.” with max_frames=100 and max_reference_audio_len=15. The 1.36-second result transcribed as “The sound is um.” using local Whisper base.en int8. Extract 0.90–1.32 seconds, apply FFmpeg atempo=0.65, gain=0.6, 20 ms onset fade, fade out from 0.50 for 0.10 seconds, 60 ms lead silence and 80 ms tail padding; rewrite a standard 44-byte WAV header. The resulting clip transcribed as “Um”. Numerical checks cover finite/clipping levels, duration and silent edges. These checks do not establish human-perceived naturalness: use the new preview button and real call playback to judge it.

Voice reference attribution: CSTR VCTK Corpus, speaker p259; Yamagishi, Veaux and MacDonald, University of Edinburgh, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/), enhanced reference distributed by Kyutai at the pinned URL in PocketVoiceSpec. The bundled clip is generated and edited as described above. Pocket model licensing is documented in pocket-tts-paul.md.

## Phone verification

- Preview the opening in Voice and response speed; compare it with the first acknowledgement in a call.
- In the radio setting, speak a complete question, then try a deliberate mid-sentence pause. Copy the partial/final Whisper timing logs and speech-end-to-playback metric.
- Run the Paul period on/off comparison and export both WAVs. Compare any timbre change with text-submission boundaries; callbacks alone do not establish a regeneration.
- Ask an ordinary question and then a valid phone action. Confirm tool policy is disabled/enabled respectively and the action still requires matching final intent.
