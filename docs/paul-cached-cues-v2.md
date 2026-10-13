# Fixed Paul cues: opening v2, prolonged recovery v3

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

Replaces the old edited hum. Generate with `scripts/generate_paul_cues.py`, using
sherpa-onnx 1.13.7 CPU, two threads, Pocket int8 2026-01-26, seed 42,
temperature 0.7, five steps and the pinned Kyutai Paul/VCTK p259 reference in
PocketVoiceSpec. Model archive SHA-256:
`2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb`.
Reference SHA-256:
`7aba504fe0b3b16478b69eb27ce6007e3cb42b0c1915b5f1c6a6024ae37d679b`.

Exact generation inputs: `. One moment please, sir.` and `. Just a moment, sir.`
The leading period is generation conditioning only. No words are cut out of a
longer recording. Preserve generated pacing, trim only edge silence, normalize
to RMS 0.10 with peak <=0.90, apply 10 ms edge fades and add 20/40 ms silent
lead/tail. Canonical mono 24 kHz PCM16 WAVs:

| File | Frames | Duration | SHA-256 |
| --- | ---: | ---: | --- |
| paul-one-moment-v2.wav | 41760 | 1.74 s | 5ac7821f968ae2a8d7c021dfc38ff4279709b0095d03c9d8d25e96cd46cffa35 |
| paul-just-a-moment-v3.wav | 32160 | 1.34 s | 0df7afc741c1db31a02a6928f2a51e0f5c77f45befb26bfd3f7fb5aa39e61fd2 |

Local Whisper base.en int8 independently checks the spoken wording. This and
numerical level/edge checks do not establish subjective naturalness; phone
preview and live listening remain the acceptance test. Earlier unclear takes
were discarded. These files are never regenerated during build or phone calls.

Voice reference attribution: CSTR VCTK Corpus, speaker p259; Yamagishi, Veaux
and MacDonald, University of Edinburgh, CC BY 4.0
(https://creativecommons.org/licenses/by/4.0/). Enhanced reference distributed by
Kyutai at the pinned URL in PocketVoiceSpec. Clips are generated and edited as
specified above. Pocket model licensing is documented in `pocket-tts-paul.md`.

Both static clips now use the selected answer playback speed with pitch 1.0.
At 0.85× the recovery lasts approximately 1.58 seconds. It is eligible only
after a completed sentence has remained drained for 2.5 seconds, once per answer.
Routine sentence shortages use silent rebuffering.
