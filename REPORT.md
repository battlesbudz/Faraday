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
