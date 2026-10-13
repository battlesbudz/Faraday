# AppFunctions and MCP: revised direction for Jarvis OS V2

Research date: 2026-09-24. Research and proposed architecture only; no runtime changes, device execution, model benchmark, or early-access application.

The completed interview now has an [implementation plan](../plans/tools-implementation-plan.md) and [decision record](../plans/tools-interview-decisions.md). Their agreed product choices supersede conflicting proposals here; this document remains the dated technical research reference.

## Decision

Make **AppFunctions a first-class integration in the initial tools architecture**, alongside MCP. Prioritize Jarvis as a **consumer** of other apps' functions. Separately expose selected Jarvis capabilities as a **provider**. This advances AppFunctions from the exploratory adapter mentioned in the earlier review to an early implementation and access-validation track.

Justin's ecosystem argument is persuasive: if app developers publish maintained semantic operations, Jarvis should consume their contracts instead of maintaining navigation scripts for every app. This reduces integration work as adoption grows. It does not guarantee universal app coverage, unrestricted caller access, or an offline implementation of every operation. There is no evidence in the reviewed resources that every app partner has committed to shipping functions or that Jarvis is already authorized to call them.

Keep Jarvis's local planner, validation, receipts, cancellation, and workflow engine. AppFunctions supplies a discovery and execution interface; it does not replace those responsibilities. The key production uncertainty is obtaining and proving cross-app caller access for an ordinary installed Jarvis app.

## Scope and pinned resources

Reviewed the four supplied resources, all six reference files in the AppFunctions skill, the sample provider and agent's core discovery/conversion/execution paths, manifests and dependency versions, and the linked platform/reference/release documentation. This is not a line-by-line audit of every UI, resource, or test in either repository. No sample was built or run.

| Supplied resource | Finding |
|---|---|
| [AndroidX package reference](https://developer.android.com/reference/androidx/appfunctions/package-summary) | A substantial typed API for providers and callers, including discovery, metadata, runtime states, results, errors, URI grants, and runtime registration |
| [Interest form](https://forms.gle/GN5ybjQFhzHRCguM7) | Redirects to a closed form: early access is at capacity; a separate feedback form is linked |
| [android/appfunctions](https://github.com/android/appfunctions/tree/1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6) | Provider sample plus a working-design reference for a testing agent; audited commit `1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6` |
| [AppFunctions development skill](https://github.com/android/skills/tree/b1f707d90904129b5972b3cc6436b568583effe5/device-ai/appfunctions) | Guidance for discovering app features, implementing functions, refining documentation, testing, and migration; audited commit `b1f707d90904129b5972b3cc6436b568583effe5` |

The V2 comparison uses `feature-tools` at `bd7b50fe45632e7c03e755fc43d7c5256c410334`. The preceding review also checked the relevant action code on Audio PR2 `e3a68a05f59bc32c91ec8329c75c2a1dd078b27f`. This addendum does not merge those branches.

## 1. Landscape: ecosystem participation has two directions

| Jarvis role | Work required | Benefit |
|---|---|---|
| AppFunctions consumer | Discover visible functions, translate schemas for the selected model, validate arguments, invoke through Android, interpret results | Other app developers maintain their own implementations |
| AppFunctions provider | Publish selected Jarvis functions and their types, descriptions, and protected service entry point | Authorized external agents can use Jarvis features |
| MCP client | Discover and invoke connected MCP tools through supported transports | Reuse services and integrations outside the Android AppFunctions registry |
| Native executor | Keep direct Android APIs and appropriate intents | Efficient device actions and coverage where AppFunctions is unavailable |
| UI automation adapter | Add selected Android Control capabilities where permitted and needed | Covers remaining UI-only operations, with more fragile state and verification |

Publishing Jarvis functions does not grant it consumer access, and Jarvis cannot annotate another vendor's closed application into exposing private operations. The vendor must implement and distribute its functions, or expose another supported interface.

Google's [overview](https://developer.android.com/ai/appfunctions) explicitly encourages developers to prepare and test functions now, while describing the full integration as experimental and selectively available. Its [July architecture article](https://android-developers.googleblog.com/2026/07/build-intelligent-android-apps-appfunctions.html) positions Android as a registry for app-owned tools used by registered agents. Those are strong platform signals; broad availability and adoption remain future-dependent.

### Local and remote are deployment properties

AppFunctions invokes Android app functionality locally through the platform. The provider may still call an online backend. Local dispatch therefore does not prove that sending a message, fetching an account record, or purchasing something works offline.

MCP can also run locally. Its [transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) includes subprocess stdio and HTTP, including localhost deployments. Google's comparison with cloud MCP describes a common deployment, not a protocol requirement. The previously audited Android Control MCP itself hosts a server on the phone.

Google uses the term Android MCP for this app-tool ecosystem. Our integration should use the actual Jetpack/platform contracts. An AppFunctions provider is not automatically a conventional HTTP MCP endpoint that any existing MCP client can call.

## 2. Access and compatibility: distinguish five separate checks

1. **Platform support:** Can `AppFunctionManager.getInstance(context)` return a manager?
2. **Caller eligibility:** Does this Jarvis installation have permission and pass the runtime access checks?
3. **Visibility:** Is the target package queryable by Jarvis?
4. **Function availability:** Is the advertised function present and enabled now?
5. **Operation readiness:** Can the provider complete it with its current authentication, permissions, network, and user state?

These should produce distinct diagnostics. An empty discovery result must not automatically mean that no installed app supports AppFunctions.

The current [permission reference](https://developer.android.com/reference/android/Manifest.permission#EXECUTE_APP_FUNCTIONS) labels `EXECUTE_APP_FUNCTIONS` a **normal** permission, but explicitly documents runtime allowlist enforcement and possible additional requirements. Older articles and sample comments use privileged terminology. Neither declaring the permission nor selecting Jarvis as the default assistant proves access. Do not confuse it with the separate `EXECUTE_APP_ACTION` permission.

The [manager reference](https://developer.android.com/reference/androidx/appfunctions/AppFunctionManager) permits discovery of one's own package without cross-app permission and subjects other-package discovery to permissions and package visibility. It separates metadata search from runtime state and observation of changes. Newer activity-related discovery and runtime registration methods are API 37 features. Discovery eligibility must not be treated as authorization to execute a particular user action.

The introductory guide describes Android 16+. The latest manager reference is more specific: support requires a non-profile user and either API 36+, or API 34+ with the AppFunctions extension library. [ExtensionsAppFunctionService](https://developer.android.com/reference/androidx/appfunctions/ExtensionsAppFunctionService) documents a conditional extension route on Android 14–16. This is not universal Android 14 support. Probe the actual device; retain V2's older-device path. Compile/target SDK requirements do not by themselves require raising V2's minimum SDK.

### The form and the test-agent access route

The supplied signup URL resolves to [this closed form](https://docs.google.com/forms/d/e/1FAIpQLScEoIsgzE-LbgRrYcQMc-Lit_5VlKRA0iWw7Pvg1brIc8wXAw/closedform). It states that the early-access program is at capacity and links [developer feedback](https://forms.gle/uTjn571hKdN6vFw96). The visible first feedback page asks for an email, package name, optional Play listing, demonstration links, and whether the developer has API feedback. No information was entered or submitted; later form pages were not traversed.

The sample agent's [README](https://github.com/android/appfunctions/blob/1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6/agent/README.md) uses `run_privileged.sh`. Its [instrumentation](https://github.com/android/appfunctions/blob/1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6/agent/app/src/main/java/com/example/appfunctions/agent/ShellIdentityInstrumentation.kt) adopts shell permission identity and keeps the instrumentation alive. This is a development/testing mechanism. A successful ADB test is not evidence that Jarvis's ordinary release installation can execute the same functions.

Unresolved external questions: How can an independent local agent obtain production caller access? Is caller enrollment distinct from provider enrollment? Which device/system-module versions and distribution requirements apply? The reviewed pages do not establish Jarvis's eligibility or an approval timetable.

## 3. What the sample actually contributes

The provider's current [service](https://github.com/android/appfunctions/blob/1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6/ChatApp/shared/src/main/kotlin/com/example/chatapp/appfunctions/BaseChatAppFunctionService.kt) exposes five functions: `searchContacts`, `sendMessage`, `makeCall`, `updateChatWallpaper`, and `searchMessages`. Contact search returns identifiers needed by later calls. It includes typed serializable results, enum constraints, exceptions, attachment URIs, and a `PendingIntent` result. These illustrate dependent workflows and UI handoff, not just single fixed commands. The app is an educational sample, not a bridge to every messaging service.

Its README trails the source: it still describes `send`, a `filterType` argument, and three functions. Use the pinned implementation and generated metadata when designing tests.

The agent contains directly relevant patterns:

- `GetAppFunctionsUseCase`: observe changes, search metadata, group by package.
- `GetAppFunctionStatesUseCase`: retrieve current enabled states separately.
- `GeminiToolConverter`: translate types, nested objects, references, required arguments, and enums into model tools.
- `ConvertInputToAppFunctionDataUseCase`: encode supplied arguments for platform execution.
- `ExecuteAppFunctionUseCase`: invoke the manager and distinguish structured data, errors, and `PendingIntent` handoff; newer Android also carries interaction attribution.
- `AgentOrchestrator`: filter disconnected apps, send tools to the model, execute calls, return observations, and continue the inference loop.

Sources are under the pinned repository's [agent implementation](https://github.com/android/appfunctions/tree/1f9b43c725de50eb6300a5071c5bc98c1f1f3fa6/agent/app/src/main/java/com/example/appfunctions/agent).

The sample uses a Gemini API key. **Our design inference:** its schema-conversion boundary can feed Jarvis's E2B/E4B engine instead; AppFunctions execution is a platform operation, not a requirement to use Gemini inference. This must be implemented and measured, not inferred as already working in V2.

Do not copy the testing agent wholesale:

- Its interaction loop has no visible iteration budget in `runInteractionLoop`.
- Tool filtering deduplicates by function ID, and model naming truncates/sanitizes IDs. Jarvis needs collision-safe identity including package and function, plus schema revision.
- The input converter performs coercion, including numeric conversion and string-to-boolean conversion. Jarvis should reject ambiguous or invalid values before platform serialization and handle unsupported types explicitly.
- The agent handles URI grants and remote file references. Jarvis must scope those to the current task, including any network access.
- A pending intent does not inherently mean a mutation awaits confirmation: this provider calls `startCall` before returning the UI intent. Completion semantics must be learned from each contract.
- Persisted conversation data is not a durable, replay-safe workflow scheduler.

These are source findings and design implications, not reproduced failures. The sample also checks AppSearch module versions, reinforcing that the OS version alone is not sufficient deployment evidence.

## 4. The skill is developer tooling, and the API is moving

The linked skill helps coding agents expose features from an Android codebase. It is not a downloadable catalog of end-user Jarvis workflows. Its full reference set covers feature discovery, implementation/configuration, KDoc, ADB testing, terminology, and migration.

Useful practices to adopt from its [implementation reference](https://github.com/android/skills/blob/b1f707d90904129b5972b3cc6436b568583effe5/device-ai/appfunctions/references/implementation-configuration.md): use the Jetpack runtime and KSP compiler; expose existing application use cases through a protected service; generate schemas; document serializable properties inline; use typed errors; move blocking work off the main thread. It supports dependency injection alternatives, so Jarvis need not adopt Hilt solely for this integration.

Its [documentation reference](https://github.com/android/skills/blob/b1f707d90904129b5972b3cc6436b568583effe5/device-ai/appfunctions/references/kdoc-refinement-optimization.md) is particularly relevant: explain prerequisites, parameters, recovery, and app-wide operation patterns. Jarvis should retain such guidance when selecting tools. Treat provider descriptions as tool documentation, not permission to override user intent or execute unrelated actions.

The [release notes](https://developer.android.com/jetpack/androidx/releases/appfunctions) list `1.0.0-alpha12`, released September 23, 2026, with no stable or beta release. Alpha10 introduced the service-entry-point architecture; alpha11 separated metadata and runtime state; alpha12 removes legacy context/configuration APIs and adds further registration and metadata changes. The checked samples use alpha10 for ChatApp and alpha11 for the agent. The skill asks for target SDK 36+ and compile SDK 37+. Choose and test one coherent dependency/API version before implementation; do not combine snippets blindly.

V2 currently compiles and targets SDK 35 with minimum SDK 29. Plan the build/target upgrade and compatibility checks explicitly; do not raise the minimum SDK merely to match the testing agent's minimum of 36.

## 5. Revised architecture and implementation sequence

Use one registry and executor with adapters for AppFunctions, MCP, direct Android calls, and selected UI actions. For other apps, prefer a suitable, authorized semantic function when available. Retain direct native implementations for Jarvis-owned/device operations; avoid unnecessary cross-process calls.

Each discovered capability should record its source, provider identity, input/output schema, schema revision, availability, authorization scope, and completion semantics. Preserve Android-specific values such as URI grants and pending intents as typed handles. Expose only task-relevant tools to the local model. Refresh metadata after app updates and recheck state before execution.

| Stage | Concrete outcome and evidence |
|---|---|
| 1. Shared contract and AppFunctions probe | Typed results; unified schema/validation; existing three tools preserved; report support, access, visibility and available functions on the target device. Fix the earlier schema/limit/branch-CI inconsistencies. |
| 2. Consumer vertical slice | Use Google's sample provider or a small controlled provider. Discover a query, invoke it, bind a returned identifier to a dependent operation. Test the converter deterministically, then with the selected real local model. Label ADB and ordinary-app evidence separately. |
| 3. Workflow ownership | Add durable attempts/receipts, typed conditions, waits, scoped cancellation, and bounded adaptive inference. Preserve fixed plans for known sequences. Test restart and uncertain outcomes before relying on external writes. |
| 4. Provider participation | Expose narrow Jarvis-owned features when their implementations exist: for example task status, task creation, and explicit saved-workflow invocation. Reuse the same domain logic and authorization. Avoid an unrestricted remote `executeAnything` function. |
| 5. MCP and coverage expansion | Add connected MCP services and selectively port Android Control capabilities for uncovered operations. Reuse provider contracts where available rather than maintaining per-app UI scripts by default. |
| 6. Skills and performance | Package reusable, reviewed workflows over the registry. Benchmark latency, correctness, and energy. Evaluate FunctionGemma only for eligible stable tool sets. |

The access investigation in stage 1 should proceed immediately; it need not block typed results or workflow foundations. If production AppFunctions access is unavailable, retain the adapter and controlled tests while shipping capabilities supported by the other routes.

FunctionGemma remains a separate experiment. A changing catalog of third-party schemas makes a narrow specialist less obviously suitable than it is for a fixed command vocabulary. This is an architectural concern, not a measured model comparison. Local IPC savings also do not predict total latency: model passes, provider work, and network dependencies still matter.

### Required runtime properties

- Separate discovery from user-enabled capabilities and per-task authority.
- Bind dependent arguments to typed results and validated targets; descriptions alone cannot authorize writes.
- Evaluate simple conditions in code; use model passes for interpretation and adaptation.
- Allow repeated observations when they establish progress; prevent accidental mutation replay.
- Treat denial as denial. Do not automatically switch to UI automation to evade an access restriction.
- Do not retry an uncertain mutation through another adapter until its outcome is resolved.
- Represent pending user interaction, failed execution, and unknown outcome separately from success.
- Test package/function removal, disabled state, schema changes, name collisions, invalid arguments, URI scope, and process death.

## 6. Adoption and attribution

No upstream code was copied in this research. If we reuse the Android samples, retain their Apache-2.0 license and applicable notices and document the pinned source and modifications. The Android skill repository also carries Apache-2.0 terms. If we port Android Remote Control MCP, preserve its MIT notice and credit Daniele Salvatore Albano as established in the [original audit](feature-tools-audit-2026-09-24.md).

The strategic choice is to participate early through a reusable AppFunctions consumer and a deliberate Jarvis provider surface. The production release claim must remain narrower until ordinary-app caller access and actual installed-app coverage are demonstrated on the target device.
