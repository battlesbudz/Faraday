# Repository boundary audit

Audit baseline: initial decomposition checkpoint `ed30650`, 2026-10-03.
The complete source/boundary and newcomer-handoff audit covers the final typed
stage architecture. Findings below are resolved in source and retained cohesive
owners are justified. Static contract review preserves the existing ABI, keys,
selectors and assertions; compiled behavior and release acceptance still come
from the final candidate's exact-revision gates and same-run receipt. This record
audits unchanged components as well as changed ones; repo-wide maintainability
does not require rewriting every file.

## Reviewed subsystems

Source paths are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/` unless
otherwise stated. JVM tests mirror these feature packages under `app/src/test/`.

| Subsystem | Boundary and lifecycle owner | Coverage/navigation | Disposition |
| --- | --- | --- | --- |
| Android entry points | Activity handles permissions/results/exports; foreground/assistant services handle Android lifetimes and delegate runtime work | `MainActivity`, `voice/VoiceCallService`, `assistant/`; release lifecycle/navigation journeys | Retain explicit platform entries |
| Process and voice stages | `JarvisRuntime` composes typed stages; explicit call/turn/model/memory owners and accepted-pump/report exact-child cleanup | [Owner map](../app-modularization.md), runtime/voice regressions | Facade, stages, ports and exact-child lifetime source review complete |
| Conversation stages | `ConversationCoordinator` owns shared admission/job/stage cleanup; context/model/generation/input/actions/recovery/reply/finalizer use typed contracts and ports | `conversation/`, prompt/session/reply/recovery/finalizer plus existing context/action/memory regressions | Typed stage/source ownership review complete |
| Setup and Compose | `ModelSetupOperations` has a narrow session port; UI observes readiness/WorkManager identity through `ModelSetupState` | `presentation/`, `ui/ModelSelectionSection`; model and release UI journeys | Coherent extraction; diagnostic composition finding below |
| Chat/attachments/dictation | `ConversationHistory` owns thread state; `AttachmentPolicy` separates validation from Android `ChatMediaStore`; `ChatVoiceInput` uses `ChatDictation` and UI lifecycle cleanup | `chat/`, `ui/ChatAttachments`, `ui/ChatVoiceInput`; history/attachment/navigation tests | Retain typed UI/adapters and dictation lease release |
| AI policies and native adapter | Catalog/compatibility/prompt/reference policies separate from `LiteRtLmEngine` native ownership | `ai/`; catalog, prompt, continuity, native-session tests | Coherent; storage admission finding below |
| Model storage and transfer | Repository owns selection/install/integrity; transport/hash/Downloads lookup have separate collaborators; worker owns durable setup | `ai/ModelStore`, `ai/storage/`, `voice/JarvisModelSetupWorker`; storage/recovery/removal tests | Retain repository; remove conversation implementation coupling |
| Voice acoustic/recognizer/output components | Capture, recognizer leases, endpoint/echo policies and bounded PCM delivery have explicit owners/seams | `voice/`; capture, ASR, interruption, queue, playback, drain and callback tests | Retain coherent resource/state-machine owners |
| Action authority and recovery | Strict plan/validator/runner contracts, journal/approval interfaces and Android executor adapter; queue receives its owning scope | `actions/`; plan/authority/approval/ledger/queue JVM and Android journeys | No process/activity dependency found; retain receipt authority |
| Memory and source archive | `MemoryPersistence`/`MemorySourceArchive` ports; policy/codec/retrieval separate from transactional SQLite; process accessor and WorkManager own storage/expiry lifetimes | `memory/`; migration/reopen/lock/expiry/invalidation/capacity checks | Retain canonical transaction and mutation-fence boundaries |
| Benchmark/diagnostic evidence | Defined metrics, capture, journal/archives and exports separate from runtime decisions | `diagnostics/`, benchmark UI; journal/accuracy/export tests | Retain evidence owner; inject diagnostic UI dependency |
| Native/JNI/build packaging | Microfrontend/JNI, constrained Sherpa source profile and namespaced Moonshine preparation own pinned binary compatibility | `app/src/main/cpp/`, Gradle, R8 rules, native preparation scripts | Retain ABI owners and packaging checks |
| Tests and verification controllers | JVM policy suites, shipping release journeys, same-run artifact selection and receipt validation remain separate from app behavior | `app/src/test/`, `app/src/androidTest/`, `scripts/verification/`, scenarios manifest | Preserve every named assertion/selector and bounded repair rules |
| Scripts/workflows | Developer map/doctor/check commands, SDK/build helpers and hosted signed pipeline have documented entry points | [Scripts guide](../../scripts/README.md), [development setup](development.md), [verification](../verification/README.md) | Portable helper suites and architecture dependency guard; exact revision still required |
| Resources and repository hygiene | Bundled supporting audio assets/platform declarations and test fixtures have source purpose | `assets/`, `res/`, manifest, native assets | No committed APK, signing material or generated build products found |
| Documentation/handoff | Current owners/toolchain/change map separated from dated research/plans/build evidence | README, CONTRIBUTING, docs index and architecture | Source-grounded current maps/supersession notices and local link/heading review complete |

## Resolved boundary findings

| Finding at the earlier checkpoint | Resolved boundary | Source review result |
| --- | --- | --- |
| Integrated workflow still owned by broad process facade | `VoiceTurnRunner` sequences typed stages with call/conversation/memory/resource ports; `AcceptedFollowupLifetime`/`AcceptedReportPlayback` join exact pump children before outer cleanup | Resolved: composition facade, typed stages and exact-child lifetime owners reviewed |
| Conversation phase extensions access arbitrary runtime fields | Typed invocation/callbacks and phase objects; `ConversationCoordinator` owns job ordering/cleanup, with narrow session/memory/reference/backend/action ports | Resolved: phase/runtime coupling removed and ownership reviewed |
| UI diagnostic views recover the process singleton for recorder/store access | `JarvisAppComposition` supplies benchmark store and `CallEvidenceActions`; feature views accept explicit dependencies | Resolved: only narrow store/evidence dependencies reach feature views |
| `ModelStore` uses `ConversationWork.activeJobs` directly for setup admission | `ProcessConversationAdmission` owns the same atomic counter; `ModelStore` receives `conversationActive`, preserving `ModelStore(Context)` and busy semantics | Resolved: same atomic admission owner and constructor ABI retained |
| Earlier linked “current” feature notes contradict current audio/UI/navigation | Unambiguous source-grounded supersession notices; separate current index from historical links | Resolved: current source links/notices reviewed; historical bodies preserved |

## Justified cohesive exceptions

| Retained component | Why its responsibility stays together | Required seam/invariant |
| --- | --- | --- |
| `voice/AudioTurnCapture` | VAD/onset/endpoint/recognizer work shares a tightly coupled bounded acoustic state machine | Injected capture/recognizer policies; final input, PCM retention and cancellation regressions |
| `voice/PiperVoiceOutput` | Queue, producer/consumer ordering, interruption, ledger and drain must share one delivery owner | Extracted synthesizer/PCM/track helpers; preserve callback/native borrower and playback timing |
| `runtime/turn/AcceptedVoiceFollowupStage` | Bounded follow-up capture and serialized terminal report delivery form one coherent pump | Typed final inputs, explicit queue/report/resource ports and `AcceptedFollowupLifetime`/`AcceptedReportPlayback` exact-child cleanup; process task lifetime remains distinct |
| `voice/ContinuousActionSession` | Accepted follow-up intake and report delivery form one bounded state machine | Explicit queue/report/cancellation APIs; accepted work lifetime separate from speech |
| `ai/LiteRtLmEngine` | Native conversation initialization/reuse/generation/close share one native resource owner | Typed engine/session inputs; release on owning dispatcher after borrowers join |
| `memory/SQLiteMemoryStore` | Facts, sources, generation, expiry, migration and erase operations need canonical transactions | Persistence/archive interfaces; rollback, generation, lock/expiry and migration tests |
| `ai/CommunityModels`, `voice/NorthernPiperSpec` | Declarative catalogs/specifications are cohesive data rather than coordination workflows | Catalog/model-inventory validation; no runtime lifecycle authority |
| Android entry/service adapters | Platform lifetimes, notifications and component permissions must remain visible at entry points | Delegated work, Android lifecycle tests and unchanged manifest/component identities |
| Native preparation/JNI and release rules | Pinned binaries and independently shrunk release-test contracts impose real ABI constraints | Symbol/callback/APK/R8 checks; no `pickFirst` substitution or broad ABI rename |

Line count does not decide an exception. Each retained owner has a cohesive task,
visible test seams and a stated resource/transaction contract. No package-level
acyclic graph is claimed: action, AI, voice and chat packages exchange typed policy
and data contracts within one Android module. Independent libraries require the
separate dependency/ABI migration decision in [ADR 001](adr-001-package-boundaries.md).

The resolved structural findings do not replace behavior/release verification.
Use the final combined revision's [A1–A8 acceptance](../verification/modular-refactor.md),
complete regression results, same-run receipt and numbered release for handoff.
Original Build 885 and first-pass Build 886 evidence are historical baselines;
neither verifies this final architecture. Physical model/audio/performance gaps
remain separately disclosed.
