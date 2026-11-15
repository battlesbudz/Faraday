package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamJournalTest {
    @Test fun virginDiagnosticsLineUsesExactFormat() {
        val journal = MemoryDreamJournal(InMemoryDreamJournalStore())
        assertEquals(
            "Memory dream: runs=0 last=none lastOutcome=none merged=0 superseded=0 synthesized=0 noop=0 vetoed=0 watermark=0",
            journal.diagnosticsLine(),
        )
    }

    @Test fun fileJournalPersistsRunsWatermarkAndNightKeysAcrossReopen() {
        val file = File.createTempFile("dream-journal", ".json").apply { delete(); deleteOnExit() }
        val entry = DreamRunEntry(
            runId = "dream-2026-11-02-100",
            nightKey = "2026-11-02",
            startedAtMs = 100L,
            finishedAtMs = 200L,
            outcome = DreamRunOutcome.COMPLETED,
            dryRun = true,
            watermarkBeforeMs = 10L,
            watermarkAfterMs = 90L,
            processedGroupKeys = listOf("group-a"),
            groupsExamined = 2,
            outputs = listOf(
                DreamOutputEntry(
                    verb = DreamVerb.SYNTHESIZE,
                    action = DreamOutputAction.WOULD_COMMIT,
                    newRecordId = null,
                    sourceRecordIds = listOf("a", "b", "c"),
                    content = "A synthesized pattern.",
                    judgeConfidence = 94,
                    reason = DreamReasons.DRY_RUN,
                ),
                DreamOutputEntry(
                    verb = DreamVerb.MERGE,
                    action = DreamOutputAction.JOURNALED,
                    newRecordId = null,
                    sourceRecordIds = listOf("a", "b"),
                    content = "A merged record.",
                    judgeConfidence = 99,
                    reason = DreamReasons.AUTONOMOUS_SUPERSEDE_DISABLED,
                ),
                DreamOutputEntry(
                    verb = DreamVerb.NO_OP,
                    action = DreamOutputAction.NONE,
                    newRecordId = null,
                    sourceRecordIds = listOf("x"),
                    content = null,
                    judgeConfidence = null,
                    reason = DreamReasons.JUDGE_OUTPUT_INVALID,
                ),
            ),
            judgeModelId = "Gemma-4-E4B-it",
            note = null,
        )
        MemoryDreamJournal(FileDreamJournalStore(file)).save(
            DreamJournalState(
                entries = listOf(entry),
                watermarkMs = 90L,
                completedNightKeys = setOf("2026-11-02"),
            )
        )

        val reopened = MemoryDreamJournal(FileDreamJournalStore(file))
        val state = reopened.state()
        assertEquals(listOf(entry), state.entries)
        assertEquals(90L, state.watermarkMs)
        assertEquals(setOf("2026-11-02"), state.completedNightKeys)
        assertEquals(
            "Memory dream: runs=1 last=dream-2026-11-02-100 lastOutcome=dry_run merged=0 superseded=0 synthesized=1 noop=1 vetoed=1 watermark=90",
            reopened.diagnosticsLine(),
        )
    }

    @Test fun saveNeverMovesWatermarkBackwardsOrUncountsANight() {
        val store = InMemoryDreamJournalStore(
            DreamJournalState(watermarkMs = 500L, completedNightKeys = setOf("2026-11-01"))
        )
        val journal = MemoryDreamJournal(store)
        journal.save(DreamJournalState(watermarkMs = 125L, completedNightKeys = setOf("2026-11-02")))

        val state = journal.state()
        assertEquals(500L, state.watermarkMs)
        assertEquals(setOf("2026-11-01", "2026-11-02"), state.completedNightKeys)
    }

    @Test fun saveRetainsOnlyTheNewestBoundedRunHistory() {
        val journal = MemoryDreamJournal(InMemoryDreamJournalStore())
        val entries = (1..MemoryDreamJournal.MAX_ENTRIES + 5).map { index ->
            DreamRunEntry(
                runId = "run-$index",
                nightKey = "2026-11-02",
                startedAtMs = index.toLong(),
                finishedAtMs = index.toLong(),
                outcome = DreamRunOutcome.SKIPPED_INSUFFICIENT,
                dryRun = false,
                watermarkBeforeMs = 0L,
                watermarkAfterMs = 0L,
                processedGroupKeys = emptyList(),
                groupsExamined = 0,
                outputs = emptyList(),
                judgeModelId = null,
                note = null,
            )
        }
        journal.save(DreamJournalState(entries = entries))
        assertEquals(MemoryDreamJournal.MAX_ENTRIES, journal.state().entries.size)
        assertTrue(journal.state().entries.first().runId == "run-6")
    }

    @Test fun corruptJournalFileLoadsClosedAsVirginState() {
        val file = File.createTempFile("dream-journal-corrupt", ".json").apply { deleteOnExit() }
        file.writeText("this is not json")
        assertEquals(DreamJournalState(), FileDreamJournalStore(file).load())
    }
}
