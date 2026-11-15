package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEntityTemporalGraphTest {
    private val now = 1_700_000_000_000L

    private fun record(
        id: String,
        text: String,
        status: MemoryReviewStatus = MemoryReviewStatus.APPROVED,
        created: Long = now,
        corrects: String? = null,
        expiry: Long? = null,
    ) = MemoryRecord(
        id, text, MemoryCategory.FACT, MemoryTier.LONG_TERM, MemoryType.SEMANTIC, 90,
        MemorySource("event-$id", "fixture", created), status, created, created, 1,
        expiry, corrects,
    )

    private fun graph(vararg records: MemoryRecord, tombstones: List<MemoryTombstone> = emptyList()) =
        MemoryEntityTemporalGraph.build(MemorySnapshot(1, records.toList(), tombstones), now)

    @Test fun extractsAndLinksAReferencedPersonAcrossMemories() {
        val graph = graph(
            record("m1", "My friend Maya works as a doctor."),
            record("m2", "Maya lives in Boston."),
        )
        assertEquals(setOf("m1", "m2"), graph.entity("maya")!!.memoryIds)
        val answer = graph.answer("What do I know about Maya?")
        assertEquals(2, answer.memories.size)
        assertTrue(answer.text.contains("works as a doctor"))
        assertTrue(answer.text.contains("lives in Boston"))
    }

    @Test fun correctionAnswersCurrentValueAndExplainsPriorValueAndChangeTime() {
        val old = record("old", "I moved to Austin.", status = MemoryReviewStatus.SUPERSEDED, created = now - 20_000)
        val current = record("new", "I moved to Dallas.", created = now - 10_000, corrects = "old")
        val graph = graph(old, current)
        assertEquals("I moved to Dallas.", graph.answer("Where did I move?").text)
        val history = graph.answer("When did I move?")
        assertEquals(2, history.memories.size)
        assertTrue(history.text.contains("I moved to Austin."))
        assertTrue(history.text.contains("I moved to Dallas."))
        assertEquals(now - 10_000, graph.history("new").last().validFromMs)
        assertEquals(now - 10_000, graph.history("new").first().validToMs)
        assertEquals("new", graph.history("new").first().supersededBy)
    }

    @Test fun erasedContentCannotReturnFromTombstonesAndRestrictedContentIsSkipped() {
        val deleted = record("deleted", "My friend Maya knew a secret place.")
        val tombstone = MemoryTombstone(deleted.source.eventId, "a".repeat(64), now)
        val graph = graph(
            record("remaining", "My friend Maya likes hiking."),
            record("restricted", "My friend Maya's password is password123"),
            deleted,
            tombstones = listOf(tombstone),
        )
        assertFalse(graph.entity("Maya")!!.memoryIds.contains("restricted"))
        assertFalse(graph.entity("Maya")!!.memoryIds.contains("deleted"))
        assertEquals("My friend Maya likes hiking.", graph.answer("What do I know about Maya?").text)
        assertFalse(graph.answer("What do I know about Maya?").text.contains("password"))
    }

    @Test fun temporalQueriesUseFixtureQuestionsAndProjectionStaysFenced() {
        assertFalse(MemoryEntityTemporalPrototype.ENABLED)
        val old = record("address-1", "My address is Watertown.", status = MemoryReviewStatus.SUPERSEDED, created = now - 5_000)
        val fresh = record("address-2", "My address is Austin.", created = now - 2_000, corrects = old.id)
        val graph = graph(old, fresh)
        assertEquals("My address is Austin.", graph.answer("What is my current address?").text)
        assertTrue(graph.answer("What was my old address?").text.contains("My address is Watertown."))
    }
}
