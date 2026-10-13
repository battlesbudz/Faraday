> Qwen update: [15 selectable LiteRT-LM Qwen models](qwen-model-selection.md), using LiteRT-LM 0.16.0. See capabilities and pending Fold 6 acceptance there.

# Supported local model stack

Current as of 16 September 2026, on `audio-pr2` / existing PR #6.

| Role | Supported choices |
| --- | --- |
| Speech recognition | Moonshine Small Streaming; Whisper base.en |
| Conversation, tools, image/audio input | Gemma-4-E2B-it; Gemma-4-E4B-it |
| Speech output | Piper Northern English Male medium only |

Kokoro and Pocket/Paul are retired. Their downloaders, synthesis branches, bundled
Paul acknowledgements, Paul native patch/host experiments, dedicated comparison
controls and Kokoro model publishing workflow have been removed. No call selects or downloads either retired voice. Their historical results and
Voice Calls remain readable with their original identities.

## Upgrade and setup

- New installations download/install the selected Gemma, Piper and selected ASR.
  Kokoro is no longer a setup prerequisite. Piper is a pinned 67,210,490-byte
  archive; its file inventory and verification marker determine readiness.
- Saved retired/unknown voice selections migrate to Piper. Old experimental
  profiles are ignored. Calls use four synthesis threads, 320-character passages,
  a 640-character cap, natural sentence pauses and normal speed.
- On voice preparation (setup or a call/test), remove only exact installer-owned
  retired model directories, staging files, partial archives and their nested
  filler caches. Gemma/ASR/Piper files and saved calls/diagnostics are preserved.
- Acknowledgements are generated and cached with Piper; no Paul recording is
  bundled or loaded. The 700 ms acknowledgement policy and ready-answer priority
  remain. An uncached cue may be skipped when answer text supersedes preparation.
- E2B/E4B selection, independent files/caches and failed-initialization recovery
  remain as described in [AI model switching](ai-model-switching.md).

## Shared dependencies retained

Sherpa-ONNX 1.13.7 and its ONNX runtime are shared by Piper, Whisper, Silero VAD
and speaker checks. Moonshine retains its separately namespaced matching ONNX
runtime. Piper still requires VITS/espeak data and the archive extraction library.
The official shared Sherpa SDK contains configuration types for other engines;
these are SDK ABI, not selectable/downloadable Jarvis models. The build uses pinned upstream model implementations with an app-specific JNI
source list; the Paul-specific streaming modification is removed. See the
[APK size audit](apk-size-audit.md) for retained and excluded native interfaces.

Silero, speaker checking and microWakeWord/stop keywords remain supporting audio
components. Removing these would break the retained assistant stack.

## Tests and acceptance

Settings and retained development checks are described in [current diagnostics](current-diagnostics.md).
The profile matrix, P1/P2 packs and obsolete Gemma/ASR comparison routes have been
removed with their controllers. Historical saved evidence is not relabeled.

Automated checks cover retired-selection migration, bounded call evidence and
archived history, exact-path file cleanup, sample-rate handling, Piper passages
and native configuration. CI also checks release tests, native dependency closure,
Piper callback ABI, absence of Paul assets/native patch, and APK signing.

Phone acceptance is still required: upgrade with an old voice selected, install
Piper if absent, verify a short/long reply and cached acknowledgement, switch
E2B/E4B, exercise both ASR engines, and deliberately test stop/goodbye and a
correction during playback. This cleanup does not establish E4B RAM/latency or
resolve outstanding acoustic/interruption acceptance by itself.
