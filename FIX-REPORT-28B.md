# PR #28B fix report: `PrimedAudioInputTest` feeder race

## Root cause

The failure diagnosis is confirmed by the code. `offerProvisionalRows` accepts and queues snapshots synchronously, but `startFeeder` consumes them on `Dispatchers.Default`. For the first two offers (96 rows each), the feeder can append two 72-row chunks, leaving 48 rows buffered. Before each append it checks `provisionalPoison`. The third offer in the failing test restarts the sequence at row 0 and sets that poison. If it arrives before the feeder reaches either append, both provisional chunks are correctly suppressed, so the test's expected `provisionalRowsFed == 144` is not guaranteed.

The old test submitted all offers from `runBlocking` without waiting for the fake session to observe either feed. It therefore did not reliably create the scenario named by the test: a sequence break *after* rows had been fed.

## Fix

The test fake now exposes a `CompletableDeferred` signal completed after its second `appendAudioChunk` call. `sequenceBreakAfterRowsWereFedDiscardsTheSession` awaits that signal, with a five-second timeout, before offering the same restarted sequence.

The test still sends the same two valid offers followed by the same row-0 restart. Its assertions are unchanged, including `provisionalRowsFed == 144`, the `provisional_rows_out_of_sequence` fallback reason, and zero decodes. This makes the intended ordering deterministic without weakening the behavior under test.

No production bug was found. Suppressing chunks when a sequence break is observed before they are appended is the intended protection against feeding stale encoder-candidate rows. The test now explicitly exercises the other ordering, where 144 rows have already entered the session before the restart is observed.

## Files changed

- `app/src/test/java/com/battlesbudz/jarvis/v2/voice/PrimedAudioInputTest.kt`
- `FIX-REPORT-28B.md`

`CallVisualContext.kt` and all scene code were left untouched. No Gradle task or build was run, as requested. `git diff --check` was run after the edits.
