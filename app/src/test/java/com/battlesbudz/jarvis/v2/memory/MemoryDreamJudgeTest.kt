package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamJudgeTest {
    private val now = 1_700_000_000_000L

    private fun record(id: String): MemoryRecord = MemoryRecord(
        id = id,
        content = "Record $id",
        category = MemoryCategory.FACT,
        tier = MemoryTier.LONG_TERM,
        type = MemoryType.SEMANTIC,
        confidence = 80,
        source = MemorySource("event-$id", "auto-memory/chat", now - 1_000L),
        reviewStatus = MemoryReviewStatus.APPROVED,
        createdAtMs = now - 1_000L,
        updatedAtMs = now,
        revision = 1L,
    )

    private fun group(band: DreamBand, vararg ids: String): DreamGroup = DreamGroup(
        key = "group-test",
        records = ids.map { record(it) },
        seedIds = ids.toSet(),
        band = band,
        maxSimilarity = 0.95,
    )

    @Test fun AmbiguityNoopTest() {
        val group = group(DreamBand.MERGE_CANDIDATE, "a", "b")
        assertNull(DreamJudgeOutputParser.parseGroup("not json", group))
        assertNull(DreamJudgeOutputParser.parseGroup("{\"verb\":\"delete\",\"confidence\":99}", group))
        assertNull(DreamJudgeOutputParser.parseGroup("{\"verb\":\"merge\",\"content\":\"x\"}", group)) // no confidence/ids
        assertNull(DreamJudgeOutputParser.parseGroup("{\"verb\":\"merge\",\"content\":\"x\",\"confidence\":101,\"source_record_ids\":[\"a\",\"b\"]}", group))
        val noop = DreamJudgeOutputParser.parseGroup("{\"verb\":\"no-op\"}", group)
        assertEquals(DreamVerb.NO_OP, noop?.verb)
    }

    @Test fun HallucinatedIdRejectedTest() {
        val group = group(DreamBand.MERGE_CANDIDATE, "a", "b", "c")
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"merge\",\"content\":\"all\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\",\"invented\"]}", group))
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"synthesize\",\"content\":\"pattern\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\",\"invented\"]}", group))
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"supersede\",\"content\":\"new\",\"confidence\":95,\"surviving_record_id\":\"a\",\"source_record_ids\":[\"a\",\"invented\"]}", group))
    }

    @Test fun mergeRequiresTheTopBandAndTheWholeGroup() {
        val judgeOnly = group(DreamBand.JUDGE_ONLY, "a", "b")
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"merge\",\"content\":\"all\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\"]}", judgeOnly))
        val top = group(DreamBand.MERGE_CANDIDATE, "a", "b", "c")
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"merge\",\"content\":\"partial\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\"]}", top))
        val merged = DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"merge\",\"content\":\"all\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\",\"c\"]}", top)
        assertEquals(DreamVerb.MERGE, merged?.verb)
    }

    @Test fun synthesizeRequiresThreeSourcesAndSupersedeExactlyTwo() {
        val group = group(DreamBand.JUDGE_ONLY, "a", "b", "c")
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"synthesize\",\"content\":\"pattern\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\"]}", group))
        assertEquals(DreamVerb.SYNTHESIZE, DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"synthesize\",\"content\":\"pattern\",\"confidence\":95,\"source_record_ids\":[\"a\",\"b\",\"c\"]}", group)?.verb)
        assertNull(DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"supersede\",\"content\":\"new\",\"confidence\":95,\"surviving_record_id\":\"a\",\"source_record_ids\":[\"a\",\"b\",\"c\"]}", group))
        assertEquals(DreamVerb.SUPERSEDE, DreamJudgeOutputParser.parseGroup(
            "{\"verb\":\"supersede\",\"content\":\"new\",\"confidence\":95,\"surviving_record_id\":\"a\",\"source_record_ids\":[\"a\",\"b\"]}", group)?.verb)
    }

    @Test fun extractionFactsAreAllowlistedAndCapped() {
        val cluster = DreamExtractionCluster("cluster", MemoryCategory.FACT, listOf(record("a"), record("b"), record("c"), record("d")))
        val text = "{\"facts\":[" +
            "{\"content\":\"fact one\",\"memory_type\":\"semantic\",\"source_record_ids\":[\"a\",\"b\",\"c\"],\"confidence\":95}," +
            "{\"content\":\"fact two\",\"memory_type\":\"procedural\",\"source_record_ids\":[\"a\",\"b\",\"invented\"],\"confidence\":95}," +
            "{\"content\":\"fact three\",\"memory_type\":\"semantic\",\"source_record_ids\":[\"a\",\"b\"],\"confidence\":95}" +
            "]}"
        val facts = DreamJudgeOutputParser.parseExtraction(text, cluster)
        assertEquals(1, facts.size)
        assertEquals("fact one", facts.single().content)
        assertTrue(DreamJudgeOutputParser.parseExtraction("garbage", cluster).isEmpty())
    }
}
