# Where to change a feature

Use the package owner for policy and its Android adapter for platform work. Paths
in this table are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/`;
JVM tests mirror that package under `app/src/test/java/`. Release journeys live in
`app/src/androidTest/java/.../verification/ReleaseJourneyTest.kt` and are named in
`scripts/verification/scenarios.json`.

| Change | Start here | Behavioral coverage to inspect |
| --- | --- | --- |
| Conversation/voice overlay or navigation | `ui/ConversationScreen`, `JarvisApp`, `VoiceOrb`, `VoiceCallScreen`; activity for permissions/results | `VoiceNavigationPolicyTest`, `VoiceSessionUiTest`; release UI/call-navigation journeys |
| Add or correct an AI bundle | `ai/ModelCatalog`, `ModelCompatibility`, `ModelConversationConfig` | Catalog, compatibility and conversation-config tests; real-model device startup |
| Download, import, remove or recover a model | `ai/ModelStore`, `ai/storage/`, `presentation/ModelSetupOperations`, `ui/ModelSetupState`, `voice/JarvisModelSetupWorker` | `ModelStorageTest`, `ModelDownloadRecoveryTest`, `ModelRemovalTest`, `ModelOperationGateTest`; persisted-selection journey |
| Prompt/history/answer quality | `ai/ConversationPromptBuilder`, `DialogueContextPolicy`, `TurnOrchestrator`, `chat/ShortTermConversationContext`; `conversation/ConversationPrompt`, `ConversationGeneration`, `ConversationRecovery`, `ConversationFinalizer` | Prompt/context/orchestrator, stage prompt/session/reply/recovery/finalizer tests, `TurnContinuityTest`; real-model conversations |
| Image/audio attachment or Gemma direct audio | `conversation/ConversationInput`, `ai/AudioMessageInput`, `voice/GemmaAudioInputPolicy`, `ui/ChatAttachments` | `AudioMessageInputTest`, `AttachmentPolicyTest`, `GemmaAudioInputPolicyTest`; model capability/device evidence |
| Voice turn lifecycle, final input or cleanup | `runtime/turn/VoiceTurnRunner`, `VoiceTurnPreparation`, `VoiceTurnRecognition`, `VoiceTurnFinalizer`, `TypedVoiceInputStage`; call/turn/lease/memory ownership types | Turn lease/typed ownership/lifecycle tests plus capture, queue and memory fences; integrated release gate |
| ASR text stability, endpointing or noisy input | `voice/AudioTurnCapture`, `WhisperTranscriber`, `voice/whisper` backends, gate/turn-end policies | Capture, onset, recognition-budget and long-utterance tests; matched physical recordings |
| Wake word, stop, interruption or microphone handoff | `voice/PassiveWakeListener`, `MicroWakeWord`, `VoiceCallResources`, `ReplyVoiceCapture`, barge-in/gate policies | Native keyword checks, handoff/echo/barge-in tests; screen-off/route/device evidence |
| TTS passage generation, PCM or playback timing | `voice/PiperTextStream`, `PiperVoiceOutput`, `PiperSpeechSynthesizer`, `SynthesizedSpeechPcm`, `SpeechAudioTrackFactory`, queue/ledger/clock helpers | Passage, queue, callback, drain and playback tests; APK ABI checks and physical playback |
| Add/change a supported phone action | `actions/MobileAction`, catalog/definitions, `ActionTurnPlan`, `AndroidMobileActionExecutor` | Validator/plan/runner/native-tool tests and real Android journeys; update exact authority/receipt contracts |
| Accepted work, approval or restart recovery | `actions/AcceptedActionQueue`, `ToolTaskLedger`, `ActionApprovalStore`, `JournaledActionPipeline`; `runtime/AcceptedVoiceActionCoordinator` and `runtime/turn/AcceptedVoiceFollowupStage` | Queue, durable-task, approval and cancellation tests; restart/unknown-outcome journeys |
| Memory capture/review/retrieval | `memory/ConversationMemory`, `MemoryOs`, `MemoryPolicy`, `MemoryRetrieval`, runtime memory owner | Capture/recall/context/delivery tests; review/correction/erase UI journeys |
| Memory/source persistence or retention | `memory/SQLiteMemoryStore`, codecs, `MemorySourceArchive`, maintenance worker | Migration/reopen/capacity/lock/expiry Android journeys and archive-policy JVM tests |
| A metric, its definition or export | `diagnostics/PipelineBenchmarkDefinitions`, `PipelineBenchmarks`, `ReplyCaptureBenchmark`, capture/journals, `ReplyMetrics`, `ui/PipelineBenchmarkScreen` | Capture/archive/journal/accuracy tests; benchmark release journeys; explicit observability limits |
| Native runtime version, symbols or APK size | `app/build.gradle.kts`, `app/proguard-rules.pro`, `scripts/build_sherpa.py`, preparation/profile/packaging scripts | Native dependency/Piper callback/compact parity checks, shrunk release journeys and size report |
| CI evidence or publication | `.github/workflows/android*.yml`, `scripts/verification/` | Harness failure-injection/artifact/receipt tests and same-run normal+compact receipt |

## Trace a change

Run `python3 scripts/dev.py map memory` (or another source/package query), then
use `rg` for the relevant public entry point, callbacks and tests. Read the owner
before adding another helper. See [app modularization](../app-modularization.md)
for the current typed collaborators and justified cohesive owners.

Text and finalized voice share admission, tool authority, model execution and
conversation persistence. Make a shared behavior change there; keep audio-specific
capture and delivery in `voice/`. Diagnostics record behavior and must not become a
second owner of routing or permission policy.

For any new capability, update the [feature acceptance map](../verification/features.md)
and its named executable journeys. Plans for broad device control, embeddings,
external message adapters and proactive scheduling do not imply those features are
implemented. Check source and existing receipts before expanding a contract.
