package com.battlesbudz.jarvis.v2.code

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The code-mode counters behind the diagnostics block (scope Unit 6):
 * rounds, retries measured from generation attempts, fix rounds,
 * exports, and the last outcome / last error — persisted in the
 * workspace, tolerant of a torn file.
 */
class CodeStatsStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store(): CodeStatsStore = CodeStatsStore(temp.newFolder())

    @Test fun aFreshStoreReadsAsZeroes() {
        val stats = store().snapshot()
        assertEquals(CodeStatsStore.Stats(0, 0, 0, 0, null, null), stats)
    }

    @Test fun userRoundsCountRetriesAsAttemptsBeyondTheFirst() {
        val store = store()
        store.recordUserRound(attempts = 1, outcome = "round applied on Snake", error = null)
        store.recordUserRound(attempts = 3, outcome = "round applied on Snake", error = null)
        val stats = store.snapshot()
        assertEquals(2, stats.rounds)
        assertEquals(2, stats.retries)
        assertEquals("round applied on Snake", stats.lastOutcome)
        assertNull(stats.lastError)
    }

    @Test fun aRefusedRoundCountsAsARoundWithNoRetries() {
        val store = store()
        store.recordUserRound(attempts = 0, outcome = "round refused on Snake", error = "too small")
        val stats = store.snapshot()
        assertEquals(1, stats.rounds)
        assertEquals(0, stats.retries)
        assertEquals("too small", stats.lastError)
    }

    @Test fun fixRoundsAddTheirOwnRetriesAndKeepTheLastError() {
        val store = store()
        store.recordUserRound(attempts = 1, outcome = "round failed on Snake", error = "the gate said no")
        store.recordFixRounds(fixRounds = 2, attempts = 3)
        val stats = store.snapshot()
        assertEquals(2, stats.fixRounds)
        assertEquals(1, stats.retries)
        // A later success never erases the last error that happened.
        store.recordUserRound(attempts = 1, outcome = "round applied on Snake", error = null)
        assertEquals("the gate said no", store.snapshot().lastError)
        assertEquals("round applied on Snake", store.snapshot().lastOutcome)
    }

    @Test fun onlyFinishedExportsCount() {
        val store = store()
        store.recordExport(success = false, outcome = "export refused on Snake", error = "name too long")
        store.recordExport(success = true, outcome = "exported Snake as com.example.snake", error = null)
        val stats = store.snapshot()
        assertEquals(1, stats.exports)
        assertEquals("exported Snake as com.example.snake", stats.lastOutcome)
        assertEquals("name too long", stats.lastError)
    }

    @Test fun countersSurviveAStoreRecreatedOnTheSameWorkspace() {
        val dir = temp.newFolder()
        val first = CodeStatsStore(dir)
        first.recordUserRound(attempts = 2, outcome = "round applied on Snake", error = null)
        first.recordFixRounds(fixRounds = 1, attempts = 1)
        first.recordExport(success = true, outcome = "exported Snake", error = null)
        assertTrue(File(dir, CodeStatsStore.STATS_FILE_NAME).isFile)
        assertEquals(first.snapshot(), CodeStatsStore(dir).snapshot())
    }

    @Test fun outcomesAreFlattenedToOneBoundedLine() {
        val store = store()
        store.recordOutcome("first line\nsecond line " + "x".repeat(500))
        val outcome = store.snapshot().lastOutcome!!
        assertTrue(!outcome.contains('\n'))
        assertTrue(outcome.length <= 200)
    }

    @Test fun aTornOrForeignCountersFileReadsAsZeroes() {
        val dir = temp.newFolder()
        File(dir, CodeStatsStore.STATS_FILE_NAME).writeText("nonsense\nrounds=abc\n=orphan\n")
        assertEquals(CodeStatsStore.Stats(0, 0, 0, 0, null, null), CodeStatsStore(dir).snapshot())
    }
}
