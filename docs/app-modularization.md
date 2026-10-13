# App modularization and handoff map

Current maintainability pass: `audio-pr2`, started 2026-10-02; finalization 2026-10-03. The app remains one Gradle
application module with responsibility-focused packages and explicit collaborators;
see [ADR 001](architecture/adr-001-package-boundaries.md). Start with the
[architecture](architecture/README.md), [change guide](architecture/change-guide.md),
and [development setup](architecture/development.md) for newcomer navigation.

## Current owners

Paths are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/`.

| Area | Owner | Boundary |
| --- | --- | --- |
| Android entry points | `MainActivity`, `voice/VoiceCallService`, `assistant/` services | Permissions/results, UI attachment, service eligibility and platform lifecycle |
| App composition | `ui/JarvisApp`, `JarvisAppComposition`, screen files | Routing and retained conversation/voice/memory surfaces; composition supplies narrow benchmark/evidence dependencies; public Compose/test boundary preserved |
| Shared admission | `work/ProcessConversationAdmission`, `conversation/ConversationWork` compatibility view | One shared atomic owner for model-file operations and conversation work; storage receives whether a conversation is active |
| Setup operations | `presentation/ModelSetupOperations`, `ModelSetupContract` | Activity-scoped select/delete/import/smoke work via a narrow session port; download lifetime in WorkManager |
| Setup presentation | `ui/ModelSetupState`, `ModelSelectionSection` | Observed readiness/work identity, explicit setup callbacks and shared browser/storage/access controls |
| Process composition | `JarvisRuntime` | Wires typed owners/ports and retains compatibility entry/session adapters; voice and conversation work delegate to their coordinators |
| Voice stage runner | `runtime/turn/VoiceTurnRunner`, `VoiceTurnStages` | Admits/sequences prepared → finalized → accepted or ordinary reply stages; typed request/resource/result values and rearm/error policy |
| Voice lifetime/ports | `runtime/turn/VoiceCallState`, `VoiceTurnLifetime`, `VoiceTurnPorts`, `VoiceConversationAccess` | Shared call state/events, exact per-turn child handles and narrow dispatch/current-child/reset/memory authority |
| Voice preparation/recognition | `runtime/turn/VoiceTurnPreparation`, `VoiceTurnRecognition` | Native/model/mic/output/prefill/capture acquisition; endpoint/echo/caption/control evidence becomes final typed input |
| Typed call input | `runtime/turn/TypedVoiceInputStage`, `VoiceTypedInputOwnership` | Final queued-message ownership and exact native-child join before release; no draft action authority |
| Voice reply stages | `runtime/turn/OrdinaryVoiceReplyStage`, `AcceptedVoiceFollowupStage` | Frozen ordinary answer/memory/TTS/direct-audio captions, or bounded accepted-follow-up capture/report state machine |
| Accepted pump resources | `AcceptedFollowupLifetime`, `AcceptedReportPlayback` in `runtime/turn/AcceptedFollowupLifetime` | Exact listener/selectors/report children join under NonCancellable; report stop → join → release → identity detach precedes outer mic/model finalization; process phone worker retained |
| Voice cleanup/observation | `runtime/turn/VoiceTurnFinalizer`, `VoiceTurnObservation`, `VoiceTurnModelLease`, `VoiceMemoryDeliveryOwner` | NonCancellable exact child/resource cleanup, benchmark evidence, lease transfer without double release and CAS-bound memory delivery |
| Phone task coordinator | `runtime/PhoneTaskCoordinator` | Durable journal admission, execution, decisions, unlock/restart recovery and conversation receipt projection |
| Memory coordinator | `runtime/RuntimeMemoryCoordinator` | Epoch/cutoff/token ordering, fresh context and one-use capture receipts; native reset remains runtime-owned |
| Accepted action coordinator | `runtime/AcceptedVoiceActionCoordinator` | Process-lifetime serial queue, generation-safe model lease and saved-ID terminal reporting |
| Runtime audio assembly | `runtime/RuntimeVoiceResources`, `VoiceTurnCaptureFactory`, `VoiceTurnOutputFactory` | Routing/capture/model assembly, frozen capture configuration, output/echo/evidence callbacks |
| Final voice interpretation | `runtime/FinalVoiceInputResolver` | Final ASR and bounded, tool-disabled Gemma audio fallback |
| Voice turn evidence | `runtime/VoiceTurnTelemetry` | Capture, synthesis, actual playback and live measurement projection |
| Conversation coordinator | `ConversationCoordinator` in `conversation/ConversationRuntime` | Shared admission/job lifetime, ordered typed stages, final assembly and native callback/cancellation cleanup |
| Conversation contracts | `conversation/ConversationContracts`, `ConversationBackend` | Typed invocation/callbacks, memory/reference ports, diagnostics callbacks and resident LiteRT backend adapter |
| Turn routing/context | `conversation/ConversationRouting`, `ConversationContextPreparation` in `ConversationContext` | Complete action/status/integrity routing before memory; approved snapshot, cutoff/recall/capture and reference context |
| Prompt/model ownership | `conversation/ConversationPrompt`, `ConversationModelSession`, `ConversationSessionState` | Prompt budgets/compaction and explicit shared native conversation reuse/capability/summary state |
| Generation/recovery/final text | `conversation/ConversationGeneration`, `ConversationRecovery`, `ConversationFinalizer`, `ConversationAnswer` | Bounded streaming/input selection and receipt-compatible tools, read-only factuality/reference/repetition recovery, pure final text policy |
| Inference observation | `conversation/ConversationInferenceTelemetry` | Per-invocation native progress, exact prompt submissions and metrics; no action authority |
| Turn input | `conversation/ConversationInput` | Typed multimodal generation selection, attachment reads and incremental fallback |
| Turn action bridge | `conversation/ConversationActions` | One turn's journal group and Android receipt callbacks through explicit executor/journal ports |
| Turn reply | `conversation/ConversationReply` | Memory-valid publication, latency and benchmark finalization |
| Model repository | `ai/ModelStore`, `ai/storage/` | Catalog selection/install/integrity; transport/provider lookup/hashing have independent owners |
| Call audio resources | `voice/VoiceCallResources`, `VoiceModelSession`, `CallModelSlot` | Serialized microphone handoff, replay boundaries and native borrowers/owner dispatchers |
| Incremental input | `voice/IncrementalVoiceInput`, `ai/LiteRtVoicePrefillSession`, `GemmaSessionText` | Stable text, prefill/decode lifetime and native channel filtering; no action authority |
| Piper producer/playback | `voice/PiperVoiceOutput`, `PiperSpeechSynthesizer`, `SynthesizedSpeechPcm`, `SpeechAudioTrackFactory` | Output coordinates the existing bounded queue/ledger; helpers own native synthesis, PCM validation and track construction/release |
| Transcript/formatting | `ChatEntry`, `chat/` | Persistent threads, delivered context and shared display/speech formatting |
| Pipeline evidence assembly | `diagnostics/PipelineBenchmarks`, `PipelineBenchmarkInput`, `PipelineBenchmarkResources`, `ReplyCaptureBenchmark` | Model/conversation snapshots and resource observations; independent capture observer with injected dependencies and no runtime lookup |
| Feature contracts/storage | `actions/`, `memory/`, `diagnostics/` | Typed authority, memory/source storage, benchmark evidence; no activity ownership |

## What this pass preserves

Single conversation admission, native lease/join ordering, microphone handoff,
accepted action lifetime, strict receipt-based action reporting, memory mutation
fences, persisted identities/schemas and the public UI/test ABI remain the contracts.
No alternate action executor, model backend, voice tuning, storage migration or
retired-voice revival is introduced by decomposition.

The entry point delegates setup operations through a small session interface.
UI diagnostics receive explicit store/evidence dependencies from composition;
benchmark assembly uses typed model/conversation snapshots and resource ports.
Shared work admission no longer makes model storage depend on conversation
implementation state.
Conversation work names its routing, context, prompt, model, input, actions and
reply phases. Runtime journal/memory/accepted-action state belongs to focused
collaborators. Piper retains native ownership and delivery ordering while extracting
synthesis/PCM/track responsibilities. README, contribution guidance, architecture,
source map and environment/helper commands supply the handoff path.

Verification and current acceptance are recorded in
[the refactor contract](verification/modular-refactor.md) and
[the feature map](verification/features.md). A code extraction or historical pass
is not evidence that the new combined APK passed its exact-revision gate.

## Retained cohesive owners

The process facade no longer owns the integrated voice workflow and conversation
phases no longer extend the process runtime. Named typed stage objects retain
ordering through narrow ports. `ConversationCoordinator` owns admission and its
returned job; `VoiceTurnRunner` owns turn sequencing, while `VoiceTurnFinalizer`
joins exact children before resource/model-lease release and rearm.

Some components remain substantial for a specific responsibility:
`AcceptedVoiceFollowupStage` coordinates the bounded follow-up/report pump,
`AudioTurnCapture` owns its acoustic state machine, `PiperVoiceOutput` owns bounded
PCM queue/drain ordering, `LiteRtLmEngine` owns a native session, and
`SQLiteMemoryStore` owns canonical transactions. Their explicit seams, tests and
justification are recorded in the [whole-repository audit](architecture/repository-audit.md).
A large cohesive owner is not an unfinished extraction solely because of its size.

Structural source review and the [A1–A8 completion contract](verification/modular-refactor.md)
cover the facade, stages, whole-repo audit and handoff. Exact combined-revision
release verification comes from its same-run receipt and numbered GitHub Release;
physical audio/model/performance acceptance remains separately disclosed.
Independent JVM/Android libraries require the separate benefit and ABI migration
criteria in [ADR 001](architecture/adr-001-package-boundaries.md).

## Earlier refactor checkpoint: 2026-09-16

The preceding pass replaced `JarvisScreens.kt` with focused screens, extracted
model transport/provider lookup/hashing, encapsulated microphone/model/replay
ownership in `VoiceCallResources`, moved shared transcript/admission policy out
of the activity, and consolidated display/speech filters in `AssistantText`.
That checkpoint's line counts and earlier test/build evidence described its
revision; the map above describes the current owners.
