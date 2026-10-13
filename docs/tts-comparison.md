# Voice comparison

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

The current voices are **Kokoro original** and **Pocket TTS — Paul**. Miro is removed
from selection, downloads and benchmarks. Its dedicated VITS configuration and pinned
archive manifest are gone. The next voice-model preparation removes its installed
model, filler cache, staging and partial archive files. A saved Miro preference falls
back to Kokoro; historical test records remain labelled Miro (retired).

Sherpa/ONNX Runtime and the shared archive installer remain because Kokoro and Paul
use them; there is no remaining Miro-only app dependency. Kokoro needs its phonemizer.

For the current profiles, immutable text-run copying and thermal recording policy see
[voice-profile-benchmark.md](voice-profile-benchmark.md). For Paul's native audio
streaming behavior, reference identity and verification see [pocket-tts-paul.md](pocket-tts-paul.md).
