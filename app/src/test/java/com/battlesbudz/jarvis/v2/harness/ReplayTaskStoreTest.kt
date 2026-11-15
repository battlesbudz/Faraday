package com.battlesbudz.jarvis.v2.harness

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayTaskStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun record(id: String, atMs: Long) = ReplayTurnRecord(
        id = id, capturedAtMs = atMs, harnessVersion = 1, modelId = "gemma-4-e2b",
        kind = ReplayTurnRecord.Kind.VOICE_TEXT, replayable = true,
        userPrompt = "transcript for $id",
        historyOffered = listOf(ReplayTurnRecord.HistoryEntry("You", "earlier")),
        seedContextRequested = true, seeded = true,
        memorySection = null, continuitySection = "", captureSection = "",
        contextBlock = null, vision = null, referenceSection = null,
        activeSubject = null, resolvedQuestion = null,
        toolPlan = emptyList(), toolReceipts = emptyList(),
        finalPrompt = "assembled prompt for $id"
    )

    @Test fun recordsPersistAcrossStoreInstances() {
        val dir = File(temp.root, "replay-tasks")
        val store = ReplayTaskStore(dir)
        assertTrue(store.record(record("a", 1_000L)))
        assertTrue(store.record(record("b", 2_000L)))
        val reopened = ReplayTaskStore(dir)
        assertEquals(2, reopened.count())
        val listed = reopened.list()
        assertEquals(listOf("a", "b"), listed.map { it.id })
        assertEquals("transcript for a", listed[0].userPrompt)
        // Voice turns are transcript-level: the record type has no audio field at all.
        assertEquals(ReplayTurnRecord.Kind.VOICE_TEXT, listed[0].kind)
        assertTrue(listed[0].replayable)
    }

    @Test fun retentionKeepsOnlyTheNewestHundred() {
        val dir = File(temp.root, "replay-tasks")
        val store = ReplayTaskStore(dir)
        repeat(105) { index -> assertTrue(store.record(record("turn-$index", 1_000L + index))) }
        assertEquals(ReplayTaskStore.MAX_TURNS, store.count())
        val listed = store.list()
        assertEquals(100, listed.size)
        assertEquals("turn-5", listed.first().id)
        assertEquals("turn-104", listed.last().id)
    }

    @Test fun corruptFilesAreSkippedNotFatal() {
        val dir = File(temp.root, "replay-tasks").apply { mkdirs() }
        File(dir, "0000000000001-corrupt.json").writeText("{not a record")
        val store = ReplayTaskStore(dir)
        assertTrue(store.record(record("good", 2_000L)))
        val listed = store.list()
        assertEquals(listOf("good"), listed.map { it.id })
    }
}
