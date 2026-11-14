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
