# PR #29 prototype test fix

## Failing tests and fixes

- `correctionAnswersCurrentValueAndExplainsPriorValueAndChangeTime`: the
  projection calculated `MemoryFactVersion` entries for superseded records, but
  retained only approved records in its `records` map. `history()` traverses
  that map, so it could not follow the current correction's
  `correctsMemoryId` edge to the prior value. The current-value query could
  still select the approved correction, but the history query returned only
  that row and omitted the prior value and its end time. The builder now keeps
  all eligible approved and superseded rows for history traversal. Current
  query selection remains approved-only, and the version timestamps continue
  to come from the correction's source timestamp.

- `temporalQueriesUseFixtureQuestionsAndProjectionStaysFenced`: the
  “What was my old address?” fixture matched the current approved address, but
  its correction history could not reach the superseded Watertown row for the
  same reason. Retaining eligible superseded rows makes the existing history
  path answer both this fixture and “When did I move?” in chronological order.
  The disabled-flag assertion was already consistent with the implementation;
  it was not changed.

## Files changed

- `app/src/main/java/com/battlesbudz/jarvis/v2/memory/MemoryEntityTemporalGraph.kt`
  now retains eligible superseded rows for history traversal. Entity links and
  current-value query selection still use approved rows only.
- `FIX-REPORT.md` records the CI failure causes and fix.

The tests were not edited. Projection construction remains in-memory from the
supplied snapshot and adds no persistence or production caller. The disabled
`MemoryEntityTemporalPrototype.ENABLED` fence remains `false`. Existing build
filtering still excludes expired, restricted, secret-bearing, and tombstoned
records; tombstones provide no content and cannot recreate nodes. Entity links
and current answers are still limited to approved rows.

## Verification

`git diff --check` was run. Gradle, builds, and other test execution were not
run, as required by the task.
