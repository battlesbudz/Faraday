# Memory Unit 4 prototype report

## Changes

- `STUDY.md`: records what the current hybrid recall misses, grounded in the SQLite ledger, record model, correction lifecycle, expiry and tombstone semantics. Defines the prototype's example questions and scope.
- `app/src/main/java/com/battlesbudz/jarvis/v2/memory/MemoryEntityTemporalGraph.kt`: adds an ephemeral entity/correction projection, fixture-query surface and a disabled fence. The builder uses a supplied snapshot; it persists nothing and is not wired into recall. It ignores expired/restricted/secret records and excludes any row whose event is tombstoned.
- `app/src/test/java/com/battlesbudz/jarvis/v2/memory/MemoryEntityTemporalGraphTest.kt`: pins person linking/aggregation, current value plus historical correction, entity/temporal example questions, secret/deletion filtering and the disabled flag.
- `docs/verification/features.md`: adds the acceptance boundary and explicitly records coverage gaps.

## Deliberately not done

- No changes to production recall ranking, memory approval/capture thresholds, embeddings, dream synthesis, UI or ledger schema.
- No persistence for the graph; it is rebuilt from a snapshot.
- No attempt at broad natural-language entity resolution or general-purpose NER.
- No Gradle, Android SDK, build, APK, network or device work, per the task constraints.

## Tests

Added `MemoryEntityTemporalGraphTest` with fixture data. It pins the three study examples (“What do I know about Maya?”, “When did I move?”, “What was my old address?”), the new current answer and the old version timeline, and verifies deleted/tombstoned and secret content do not enter graph answers. Tests were not run because Gradle/build execution was explicitly prohibited and this VM has no Android SDK; `kotlinc` is also unavailable. `git diff --check` passed.

## Risks and owner review

- Name extraction is intentionally heuristic and English/proper-name biased; it can miss aliases and can treat sentence-initial capitalized words as names. Entity resolution needs a stricter identity policy before production use.
- Correction history relies on explicit `correctsMemoryId` edges. Independently captured contradictory facts are not inferred as a correction.
- Review Kotlin compilation/API compatibility in CI; this environment could only perform whitespace/diff checks.
- The disabled constant alone is not a runtime configuration mechanism; production remains isolated because no caller is wired. Enabling later requires deliberate caller wiring and privacy/approval review.

## On-phone proof

No current app screen exposes this disabled prototype, so a phone build should show unchanged memory capture and recall. After the owner deliberately wires/enables it in a future build, a controlled local-ledger fixture would demonstrate “Maya” aggregating two approved notes and a correction question showing the newer value with the older value and timestamps. A deleted fixture and a secret-bearing fixture should produce no graph output, and normal recall should remain unchanged until that separate enablement is approved.

---

# Reliability audit and hardening report

## Changes

- [`AUDIT.md`](AUDIT.md): records current M1–M7 verdicts with implementation/test evidence and identifies the chosen M5 model identity reuse gap.
- [`ConversationModelSession.kt`](app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationModelSession.kt): snapshots the selected model spec and closes/clears a resident engine when its `modelId` does not match, before it can be returned to chat, code, or voice work. This preserves native teardown and context accounting through the existing `close()` owner.
- [`ConversationModelSessionTest.kt`](app/src/test/java/com/battlesbudz/jarvis/v2/conversation/ConversationModelSessionTest.kt): pins the identity decision for no resident, same-model reuse, and changed-model replacement.
- [`REPORT.md`](REPORT.md): this handoff.

## Deliberately not done

- Did not alter M1–M4, M6, or M7 mechanisms; audit found them intact.
- Did not expand captured MCP fixture flows. The expected follow-on is still useful, but the discovered M5 defect takes priority. I did not manufacture server response bytes or claim provenance for uncaptured exchanges.
- Did not change UI, add features, touch video-call code, run Gradle, build an APK, access external services, or push commits.
- Could not create the requested local commit: the workspace exposes `.git` as read-only, and `git commit` failed creating `.git/index.lock`. The implementation and reports remain as uncommitted working-tree changes on `codex/reliability-next`.

## Tests

- Added `residentEngineReuseIsBoundToTheSelectedModelIdentity` in `ConversationModelSessionTest`. It pins the model-identity predicate that controls stale resident replacement.
- Existing tests inspected include `EngineBackendLadderTest`, `CheckedConversationLifecycleTest`, `TurnGuardTest`, `TurnGuardInterleavingTest`, and `McpCapturedFixtureTest`.
- Tests were not run because the task explicitly prohibits Gradle/Android execution in this VM. No build or runtime result is claimed.

## Risks and owner review

- The new regression pins the identity decision directly; it does not instantiate a LiteRT native engine to exercise the full close-and-recreate integration. Existing checked-lifecycle coverage separately pins close/drain behavior. CI compilation and JVM execution should confirm the combined path.
- Verify that callers continue to serialize model selection with runtime teardown as documented by `ModelStore.selectModel()`. The new guard is defensive if a stale resident survives that expected transition.
- The audit is based on current source and checked-in tests; it is not an Android release, physical-device, real-model, or audio validation.

## On-phone proof for Battles

With two installed models, start a chat or voice conversation using model A, switch the selected model through the app's normal serialized selection flow to model B, then submit another turn. Diagnostics should show the next resident backend using B, with a model load/recreation at the transition; subsequent chat/code/voice turns on B should reuse B. Confirm no turn after the switch is served by model A. This should be checked on the target phone with real installed weights; emulator coverage cannot establish model quality or device performance.

---

# Browser Unit 1 report

## Changes

- `actions/MobileToolCatalog.kt`: declared `web_fetch` and `web_search` in the shared native-tool schema catalog.
- `actions/MobileAction.kt`: added typed read-only actions and validated HTTPS URLs / bounded queries.
- `actions/NativeActionDecoder.kt`: decoded both structured model calls into the shared action request envelope.
- `actions/AndroidMobileActionExecutor.kt`: dispatched through the existing executor and emitted short diagnostics without logging page content or the search query.
- `actions/WebReadTools.kt`: added an injectable GET seam, a timeout and body cap, HTTPS URL checks, HTML text extraction, bounded responses and untrusted-content framing.
- `actions/WebReadToolsTest.kt`: covers URL policy, HTML cleaning, truncation, timeout/HTTP failure honesty, wrapping and search result shape.

The registry-to-execution audit followed `MobileToolCatalog` → `NativeActionDecoder` → `MobileActionValidator` → `MobileActionPipeline` → `AndroidMobileActionExecutor`. `open_website` is a local dispatch tool through the executor, while `run_in_sandbox` demonstrates an injected executor bridge and receipt convention. `McpTransport` is the network example: injectable transport, bounded timeouts, status/body response object and centralized diagnostics. Historical memory uses explicit untrusted framing and JSON quoting; web output uses a parallel explicit warning and escapes markup delimiters.

## Deliberately not done

No forms, authentication, clicking, file downloads, external browser handoff, UI, changes to existing tool behavior, sandbox changes, network access, Gradle execution, APK creation or publishing.

## Tests

Added `WebReadToolsTest`; tests pin the stated policy and injected HTTP behaviors without network. They were not run because the task explicitly prohibits Gradle execution and this environment lacks Android SDK support.

## Risks / owner review

The current search parser uses DuckDuckGo's HTML response shape and may need fixture updates if that changes. Redirects are not followed intentionally. The built-in Java URLConnection response reads are capped while reading. Review whether web failure strings should be represented as failed `ExecutionResult` values: the shared result currently wraps the human-readable message as a successful tool dispatch even when that message reports an HTTP/network failure. The search snippet extraction is intentionally simple and should receive additional captured HTML fixtures before wider release.

## On-phone proof

On an instrumented phone build, ask Faraday to read a known public HTTPS article and summarize it, then ask a factual lookup requiring a web search. Confirm it returns titles and URLs, explicitly treats injected page instructions as untrusted, and reports an offline/timeout failure without inventing page text. Confirm no browser UI or form interaction occurs.

---

# Reliability audit and hardening report

## Changes

- [`AUDIT.md`](AUDIT.md): records current M1–M7 verdicts with implementation/test evidence and identifies the chosen M5 model identity reuse gap.
- [`ConversationModelSession.kt`](app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationModelSession.kt): snapshots the selected model spec and closes/clears a resident engine when its `modelId` does not match, before it can be returned to chat, code, or voice work. This preserves native teardown and context accounting through the existing `close()` owner.
- [`ConversationModelSessionTest.kt`](app/src/test/java/com/battlesbudz/jarvis/v2/conversation/ConversationModelSessionTest.kt): pins the identity decision for no resident, same-model reuse, and changed-model replacement.
- [`REPORT.md`](REPORT.md): this handoff.

## Deliberately not done

- Did not alter M1–M4, M6, or M7 mechanisms; audit found them intact.
- Did not expand captured MCP fixture flows. The expected follow-on is still useful, but the discovered M5 defect takes priority. I did not manufacture server response bytes or claim provenance for uncaptured exchanges.
- Did not change UI, add features, touch video-call code, run Gradle, build an APK, access external services, or push commits.
- Could not create the requested local commit: the workspace exposes `.git` as read-only, and `git commit` failed creating `.git/index.lock`. The implementation and reports remain as uncommitted working-tree changes on `codex/reliability-next`.

## Tests

- Added `residentEngineReuseIsBoundToTheSelectedModelIdentity` in `ConversationModelSessionTest`. It pins the model-identity predicate that controls stale resident replacement.
- Existing tests inspected include `EngineBackendLadderTest`, `CheckedConversationLifecycleTest`, `TurnGuardTest`, `TurnGuardInterleavingTest`, and `McpCapturedFixtureTest`.
- Tests were not run because the task explicitly prohibits Gradle/Android execution in this VM. No build or runtime result is claimed.

## Risks and owner review

- The new regression pins the identity decision directly; it does not instantiate a LiteRT native engine to exercise the full close-and-recreate integration. Existing checked-lifecycle coverage separately pins close/drain behavior. CI compilation and JVM execution should confirm the combined path.
- Verify that callers continue to serialize model selection with runtime teardown as documented by `ModelStore.selectModel()`. The new guard is defensive if a stale resident survives that expected transition.
- The audit is based on current source and checked-in tests; it is not an Android release, physical-device, real-model, or audio validation.

## On-phone proof for Battles

With two installed models, start a chat or voice conversation using model A, switch the selected model through the app's normal serialized selection flow to model B, then submit another turn. Diagnostics should show the next resident backend using B, with a model load/recreation at the transition; subsequent chat/code/voice turns on B should reuse B. Confirm no turn after the switch is served by model A. This should be checked on the target phone with real installed weights; emulator coverage cannot establish model quality or device performance.
