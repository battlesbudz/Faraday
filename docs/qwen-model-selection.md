# Qwen model selection — audio PR2 / PR #6

## Implementation

Voice settings and setup now list 15 pinned Qwen bundles alongside the existing E2B/E4B entries. End the current call, select a model, then Download and Install (or import its catalog filename). Complete the model test before starting a call. Only the selected model downloads; installed models retain their own validation and cache. E2B remains the default. No new PR.

LiteRT-LM Android is upgraded from 0.12.0 to 0.16.0: Qwen3.5 requires >=0.15. The adapters compile against the actual 0.16.0 AAR. This SDK change also affects Gemma and requires device regression checks; API compilation does not prove native output correctness.

| Model | Download (decimal) | Decoder backend | Export context |
|---|---:|---|---:|
| Qwen2.5-Coder-1.5B-Instruct | 1.12 GB | CPU | 4096 |
| Qwen2.5-Coder-3B-Instruct | 3.43 GB | GPU | 4096 |
| Qwen3-0.6B | 0.34 GB | GPU | 2048 |
| Qwen3-1.7B | 0.98 GB | GPU | 2048 |
| Qwen3-4B | 2.66 GB | GPU | 2048 |
| Qwen3-4B-Instruct-2507 | 2.66 GB | GPU | 2048 |
| Qwen3-4B-Thinking-2507 | 2.47 GB | GPU | 4096 |
| Qwen3-8B | 4.89 GB | GPU | 2048 |
| Qwen3.5-0.8B | 0.96 GB | CPU | 4096 |
| Qwen3.5-2B | 2.12 GB | CPU | 4096 |
| Qwen3.5-4B | 2.75 GB | CPU | 4096 |
| Qwen2.5-1.5B-Instruct | 1.60 GB | GPU | 4096 |
| Qwen2-0.5B-Instruct | 0.65 GB | GPU | 4096 |
| Qwen2-1.5B-Instruct | 1.80 GB | GPU | 4096 |
| Qwen2-VL-2B | 1.78 GB | GPU | 4096 |

Artifact revisions, source filenames, byte sizes and SHA-256 are recorded in [the manifest](measurements/qwen-model-artifacts.json), retrieved from the publisher's Hugging Face API. Files are pinned, not latest/main URLs. The Thinking model's upstream `model.litertlm` must be renamed to `Qwen3-4B-Thinking-2507.litertlm` for explicit Downloads import; built-in download handles this automatically.

## Capabilities and limits

- These are runtime-compatible candidates, not Fold 6 performance certifications. Download size is not resident memory. Setup persists an attempted-test marker before native initialization so crashes do not create automatic restart loops; switch back to installed Gemma to recover.
- Qwen2-VL supports images; other selected artifacts are text-only, including the Qwen3.5 text-decoder conversions. Text-only image requests are rejected clearly.
- Speech uses Moonshine/Whisper -> Qwen -> Piper. Qwen receives no raw audio. Empty recognition asks for repetition without trying to load an audio encoder.
- Qwen uses Conversation and its embedded template, never Gemma's raw Session delimiters. Listening-time prefill is disabled for Qwen; plain finalized input is submitted once. Gemma keeps incremental input. This avoids false claims of prompt reuse or speedups for Qwen.
- Native thought channels are excluded from answer/TTS. General models request thinking disabled; the explicit Thinking model has a 256-token thinking budget and can take longer to start answering. Qwen output is capped at 512 tokens per decode.
- Qwen native tool calling is not enabled: these conversions do not establish the same structured tool contract as Gemma. Existing deterministic app-command routing remains; do not treat Qwen as a full replacement for Gemma phone-tool behavior yet. GitHub editing tools are not part of this change.
- Export context is 2K or 4K, not the much larger advertised upstream context. Engine limits and smaller app compaction budgets are set per model. Character budgeting is approximate; the runtime's token limit is authoritative. Oversized Qwen prompts are rejected rather than truncating the current request.
- Qwen3.5 uses CPU as the conservative Snapdragon default; the 4B publisher specifically does not certify its Adreno GPU path. Coder 1.5B uses the publisher's Android CPU recommendation; other selected models use GPU. Phone benchmarks must determine whether these defaults are optimal for Fold 6.

## Scope of the catalog audit

Sources: [Qwen collection](https://huggingface.co/collections/litert-community/qwen-family) plus all Qwen-named repositories under litert-community (2026-09-18).

Excluded: Qwen2.5-0.5B legacy `.task` files (not LiteRT-LM bundles); Qwen3-14B (smallest published file ~8.66 GB, insufficient dependable headroom for the Fold's full voice stack); hardware-specific MediaTek NPU bundles; redundant precision variants; Qwen ASR/TTS/embedding/reranker models (not conversational LLM replacements). Qwen3.5 VL variants are not enabled in this pass; selected text versions avoid adding unverified vision configurations. This catalog does not claim to enumerate every third-party conversion.

## Validation and phone acceptance

Local: catalog/default/selection checks, template-session buffering and abandonment checks, existing Gemma streaming/lifecycle tests; both runtime adapters compiled against 0.16.0. Signed Android build, full JVM tests, packaging and signing checks run in existing PR CI.

Pending on Fold 6:
1. Existing E2B conversation and voice still work after runtime upgrade.
2. Start with Coder 1.5B, then Qwen3 1.7B or Qwen3 4B Instruct. Verify download, model check, a short answer and a follow-up.
3. Check empty recognition, stop/goodbye, switching back to Gemma without re-download, and app restart restoring selection.
4. Use the existing diagnostics to compare inference `timeToFirstTokenMs`, `firstCallbackMs`, `totalGenerationTimeMs` and speech-end-to-playback. The user's ~30-second audible E4B delay is not a TTFT measurement.
5. Larger and Thinking models: monitor initialization, memory, heat, answer correctness and sustained latency. No speed claim until measured.
