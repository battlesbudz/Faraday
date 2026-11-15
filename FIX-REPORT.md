# PR #28 CI failure investigation

## `CallVisualContextTest` failure

The snapshot budget calculation already counts raw tracked IDs: a listed merged line accounts for each ID it names, while tracks with no emitted line contribute to `tracksOmitted`. The budget omission sentence, however, only said `Additional tracked objects omitted: N.` The new test also requires the block itself to expose the explicit `tracksOmitted=N` count; that marker had only been present in diagnostics. This mismatch caused `snapshotBudgetReportsEveryUnlistedTrackAsOmitted` to fail.

The omission sentence now includes both the readable count and `tracksOmitted=N`, and the budget reserves space for that exact longer sentence before accepting another track line. The existing budget contract remains intact: for the 40 in-window tracks, `tracksIncluded + tracksOmitted` accounts for all 40, and the explicit block count matches the attachment count. Tracks outside the 15-second snapshot window remain separately reported as `windowExcluded` and are outside the budget's listed/unlisted count.

## `PrimedAudioInputTest` failure

This failure is independent of the scene changes. `sequenceBreakAfterRowsWereFedDiscardsTheSession` sends provisional rows to a `PrimedAudioInput` and expects the asynchronous feeder to have appended 144 rows before it sends a sequence restart. The feeder runs on `Dispatchers.Default`, while the test submits all three offers synchronously from `runBlocking`. The test does not wait for either chunk feed before submitting the restart. If the restart poisons the provisional stream before the feeder processes the first offers, its poison check can prevent the expected chunks from being fed. This is a timing assumption and a plausible flakiness signal in the audio test.

The PR diff changes only `CallVisualContext.kt`, `CallVisualContextTest.kt`, and `REPORT.md`; it does not change `PrimedAudioInput`, its feeder, coroutine scheduling, or audio/session state. Scene decision code has no shared mutable state with this unit test. I left all audio code and tests untouched.

## Files changed for this fix

- `app/src/main/java/com/battlesbudz/jarvis/v2/voice/CallVisualContext.kt`: expose the raw omission count in the snapshot block.
- `FIX-REPORT.md`: record both CI failure investigations.

No Gradle task or build was run, per the task constraints. `git diff --check` is recorded after these edits.
