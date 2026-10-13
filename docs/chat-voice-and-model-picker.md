> **Historical UI checkpoint.** Earlier “no image attachment,” “no live transcript,”
> and Chat/Back-ends-call instructions below are superseded. The current conversation
> surface supports compatible attachments and live transcript with a voice overlay;
> display/navigation changes retain the call, and explicit End terminates it.
> See [`ConversationScreen`](../app/src/main/java/com/battlesbudz/jarvis/v2/ui/ConversationScreen.kt),
> [`VoiceNavigationPolicy`](../app/src/main/java/com/battlesbudz/jarvis/v2/voice/VoiceNavigationPolicy.kt)
> and the [current change guide](architecture/change-guide.md). Historical acceptance
> steps below are retained as evidence, not the current phone test procedure.

# Shared chat, voice and model selection

Chat and Voice call are two modes of one conversation surface, with one app-private conversation history. The application runtime owns generation and persistence, so folding/rotating the phone or backgrounding the Activity does not make UI callbacks the sole copy of a message.

- The shared header has a Chat / Voice call segmented control and one Settings button. Settings contains the family-based model browser, phone guidance, voice input options and diagnostics in either mode.
- Type in Chat or start the existing hands-free session in Voice call. Starting Voice still waits for Hey Jarvis.
- Voice animates an opaque orb/call-control surface over the still-mounted chat. The draft and list state remain alive beneath it. The covered transcript is hidden visually and from accessibility semantics; switching into Voice clears keyboard focus.
- End call and a recognized goodbye reveal Chat while the conversation screen is open. Selecting Chat or pressing Back from Voice also stops the voice session. Wait for native audio/model cleanup if Send reports that work is still finishing.
- Spoken user messages are italicized and labeled “Spoken transcript.” Only final recognized input is retained; provisional ASR text is not a user message.
- Deleting a saved call also removes its linked transcript segment from chat.
- Calls remain available through Conversations → Saved voice calls. “Continue in chat” imports legacy calls explicitly; resuming voice adds a new segment to the selected shared thread without copying old messages twice.
- Generated assistant text is visible for review. For a voice interruption, model context uses the existing delivery ledger (actually spoken words and action receipts), not unsaid generated text. Unfinished typed replies remain visible but are excluded from subsequent context.
- New starts a separate thread. Conversations reopens saved threads. Selecting a model changes the engine but keeps the thread. Native context remains bounded; the full displayed history is not an unlimited model memory.
- Text chat does not require installed speech models. Voice setup still prepares its own speech dependencies.

## Catalog scope

The picker contains the existing 17 pinned models plus 73 additional entries: **90 total**, organized into clickable model families (Gemma, Qwen, Llama, Mistral and others), with publisher attribution inside each family. The first view lists families only. Open one to see models ordered by the actual pinned download size, smallest first. Search matches family, publisher, model name and use. Every model card shows purpose tags, a plain-language description, specialist limitations, download size, memory qualifier and workload. “Why this rating?” expands the reasoning and links to the model card. Browsing does not select or download anything; Choose retains the existing setup/import/test flow. Each additional entry selects one portable bundle; this is not a list of every quantization, NPU-specific artifact or third-party conversion on the internet.

`model-catalog-audit.json` records exact publisher repository revisions, filenames, SHA-256 values, sizes, backend choices and exclusions, audited 2026-09-19 against the Hugging Face `litert-community` repositories. The app already pins LiteRT-LM 0.16.0. No runtime upgrade is introduced here.

Not added: models with only legacy `.task` or raw `.tflite` files; embedding/reranking/ASR/TTS models; retired FunctionGemma task-specific bundles; document/OCR bundles needing a separate preprocessing/prompt path; the non-running 1-bit Bonsai proof of concept; Gemma 4 12B, which requires runtime 0.17. Hardware-specific NPU exports are omitted because this app uses the portable CPU/GPU backends. Existing downloads remain pinned unchanged.

Publisher-gated models display an access notice and a publisher link; their automatic download button is disabled. Accept the publisher terms and download in a browser, then import the file. Jarvis does not collect a Hugging Face token.

New bundles are **experimental in Jarvis until tested on a phone**. Publisher format/backend evidence and a pinned hash do not prove every Android driver or conversation mode works. The built-in model test verifies file integrity, initialization and readable generation; it is not an accuracy benchmark. Llama base models and task specialists are labeled accordingly. Native tool registration remains enabled only for existing supported Gemma models; selecting a model trained for tools does not automatically integrate its tools.

Image-capable bundles are labeled as such, but the new text composer does not add image attachment or device GUI control. All conversational entries can be used through the existing ASR → text → Piper voice pipeline; this is not a claim of native audio support.

## Phone check

Manufacturer/model, chip, ARM64 support, total/free RAM and free internal storage are read locally. No device information is uploaded. **Installed status and passed reply tests never affect model suitability, family order or starting-option suggestions.** They are separate status labels. Download storage checks alone depend on whether the file is already installed.

The old artifact-size multiplier, calculated “working RAM,” free-RAM cutoff and passed-test preference have been removed. Memory screening now compares the pinned bundle size with Android-reported total RAM, and explicitly shows that calculation:

| Bundle / total RAM | Display qualifier | Meaning |
| --- | --- | --- |
| Below 40% | Likely memory headroom | Room on paper, with actual runtime overhead unknown |
| 40% to below 65% | Less memory headroom | May run; less room for Android, speech, caches and context |
| 65% to below 100% | Tight headroom · may not fit | Loading failure or substantial pressure is possible |
| 100% or more | Bundle exceeds total RAM | Full residency alone would exceed physical memory |

These bands are a transparent resource screen, **not empirically measured fit boundaries**. The internal risk separates 85%+ as especially tight. Bundles can include memory-mapped embeddings and optional encoders; quantization, cache allocation and architecture change actual working memory. No new numeric RAM-footprint or speed estimates are invented. On a 12 GiB test profile, at least 85 of the 90 current models stay below the tight-headroom bands, including E2B and E4B. Qwen3-8B also passes this size-only screen, but unverified phone compatibility and a reported Fold6 startup failure override its positive presentation. Gemma 26B/31B and Qwen3-14B remain visible and selectable with more demanding memory qualifiers.

Compute size is assessed separately: up to 2B effective parameters is light, over 2B–4B moderate, over 4B–8B heavy, and over 8B very heavy. Unknown sizes remain unknown. Gemma E2B/E4B use effective compute sizes; Gemma 26B-A4B still uses its full large bundle for memory screening. A compressed 8B Bonsai can therefore have plenty of memory headroom and heavy compute demand at the same time. Reasoning/hybrid models disclose extra thinking delays, and CPU bundles identify that backend. Longer sustained work can increase battery/heat demand; the app does not infer a temperature or guaranteed speed from RAM or chip name.

An “Everyday starting option” badge marks a general-chat candidate with reasonable memory headroom, 0.5–4B compute size and no dedicated thinking-only role. E2B and Qwen3-1.7B are preferred candidates in their families; other eligible families favor approximately 1.5B, then smaller downloads. This is a starting suggestion, not a benchmark winner or a change to size ordering. Base Llama, medical/repository/translation specialists and image-only-purpose cards are not promoted as general chat candidates. Reported Fold6 E2B/E4B experience appears separately in detail; it does not replace memory labels.

Purpose metadata distinguishes code generation/debugging, reasoning/math, Japanese/English, translation, base-model completion, image-model research and ordinary chat/writing/summaries. It describes intended uses, not a claim that every bundle excels at them. The current chat has no image attachment or video-call input. FastContext's repository tools and Jan's native tool integration are not connected here. Runtime capabilities and pinned downloads are unchanged.

### Experimental Qwen3-8B status

As requested on 2026-09-20, investigation of the Qwen3-8B startup failure is on hold. Its card and selected-model summary show “Experimental · not benchmarked in Jarvis” on all supported phones, before download, regardless of installed/tested status or free RAM. The visible notice says phone compatibility is unverified, records the reported Fold6 failure without asserting its cause, and cautions that the model may not load or run reliably. No error was captured. It stays selectable and in size order, but is excluded from starting-option eligibility. “Not benchmarked in Jarvis” refers to absent successful Jarvis performance validation, not the absence of publisher desktop benchmarks.

The notice is bound to the current Qwen3-8B GPU artifact (SHA-256 `cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42`). A replacement bundle/backend must be assessed separately. Tests cover notice visibility on Fold6 and other supported phones, resource independence, artifact/backend scoping, unsupported-architecture precedence, adjacent models and retained catalog availability.

### Source basis (reviewed 2026-09-19)

- [Google Gemma overview and memory planning](https://ai.google.dev/gemma/docs/core): effective parameters, mobile embeddings, full MoE weights, context overhead; published memory figures vary by export and runtime, so they are not substituted for measurements of these pinned files.
- [Qwen3](https://huggingface.co/Qwen/Qwen3-4B) and [Qwen2.5-Coder](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct): hybrid thinking/general assistance versus code specialization.
- [Llama base model](https://huggingface.co/meta-llama/Llama-3.2-1B), [Ministral Instruct](https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512), [Liquid Thinking](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking): model roles and tradeoffs.
- [FastContext conversion card](https://huggingface.co/litert-community/FastContext-1.0-4B-SFT), [FastVLM](https://huggingface.co/litert-community/FastVLM-0.5B), [MedGemma](https://huggingface.co/google/medgemma-1.5-4b-it): specialist intent. Remaining family/source metadata and pinned bundle sizes are recorded in `model-catalog-audit.json`; each card links to its actual bundle publisher.

## Acceptance on a phone

1. Open Settings from Chat and Voice; confirm there is just one entry point and the same model/voice controls. Open the family browser and inspect Gemma, Qwen, Llama and Mistral. Confirm size ordering, purpose labels, starting options and expandable rating explanations. E2B/E4B should show memory headroom on Fold6; the heavier models must remain selectable. Installing/testing a model must not move it or change its rating. Search a family/use, download one small model, run its test and chat. Switching to an installed model should not download it again. Delete a downloaded model and check freed storage.
2. Type “My dog's name is Luna,” leave an unsent draft, then switch to Voice call. Confirm the keyboard closes and the orb covers the transcript beneath the same header. Start voice, say Hey Jarvis, and ask “What is my dog's name?” Confirm no live transcript appears (including with TalkBack).
3. End call. Confirm the same chat and unsent draft reappear, the recognized user words are italicized, the reply appears once, and a typed follow-up refers to the same conversation. Repeat using the Chat selector and Android Back to end voice.
4. Interrupt a spoken reply, end the call, and inspect the generated/unfinished reply. Ask what was said; the model should use delivered speech, not the unspoken tail.
5. Rotate/fold and background the phone during a response, reopen it, then force-stop/relaunch after completion. Confirm saved thread order and no duplicate call entries.
6. Start New, confirm it has no prior thread context, then reopen the previous thread through Conversations. Try an older call's Continue in chat and Resume conversation.
7. Try starting voice while text generation is active and switching models during an active operation. Controls/runtime admission should prevent overlapping native work.

## Validation

15 focused model JVM tests passed locally for this correction. They cover catalog pinning, family ordering, suitability guidance, and the Qwen3-8B experimental notice's artifact/backend scope and visibility across phones. Android release compilation, the full JVM suite, native packaging, signing and APK assembly run in the existing PR workflow. Actual startup causes, model speed, UI rendering and newly listed model behavior still require device testing.
