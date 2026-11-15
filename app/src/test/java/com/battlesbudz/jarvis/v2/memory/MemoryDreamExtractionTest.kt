package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamExtractionTest {
    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun record(
        id: String,
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        category: MemoryCategory = MemoryCategory.FACT,
        type: MemoryType = MemoryType.EPISODIC,
    ): MemoryRecord = MemoryRecord(
        id = id,
        content = "Episode $id",
        category = category,
        tier = MemoryTier.LONG_TERM,
        type = type,
        confidence = 80,
        source = MemorySource("event-$id", "auto-memory/chat", createdAtMs),
        reviewStatus = MemoryReviewStatus.APPROVED,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        revision = 1L,
    )

    @Test fun clustersNeedThreeEpisodesInOneCategoryInsideTheWindow() {
        val records = listOf(
            record("a", now - dayMs), record("b", now - 2 * dayMs), record("c", now - 3 * dayMs),
            record("old", now - 8 * dayMs),
            record("goal-a", now - dayMs, category = MemoryCategory.GOAL),
            record("goal-b", now - dayMs, category = MemoryCategory.GOAL),
            record("semantic", now - dayMs, type = MemoryType.SEMANTIC),
        )
        val clusters = MemoryDreamExtraction.clusters(records, watermarkMs = 0L, nowMs = now)
        assertEquals(1, clusters.size)
        assertEquals(MemoryCategory.FACT, clusters.single().category)
        assertEquals(setOf("a", "b", "c"), clusters.single().records.map { it.id }.toSet())
    }

    @Test fun aClusterNeedsAChangedSeed() {
        val records = listOf(
            record("a", now - dayMs), record("b", now - 2 * dayMs), record("c", now - 3 * dayMs),
        )
        // Watermark already past every record: nothing changed, no cluster.
        assertTrue(MemoryDreamExtraction.clusters(records, watermarkMs = now, nowMs = now).isEmpty())
        // One record updated after the watermark seeds the cluster, and the
        // older episodes still join as corroboration.
        val seeded = records.mapIndexed { index, record ->
            if (index == 0) record.copy(updatedAtMs = now) else record
        }
        val clusters = MemoryDreamExtraction.clusters(seeded, watermarkMs = now - dayMs, nowMs = now)
        assertEquals(1, clusters.size)
        assertEquals(setOf("a"), MemoryDreamExtraction.seedIds(clusters.single(), now - dayMs))
    }

    @Test fun clustersAreCappedAtTwentyShownRecords() {
        val records = (1..25).map { record("e$it", now - it * 60_000L) }
        val cluster = MemoryDreamExtraction.clusters(records, watermarkMs = 0L, nowMs = now).single()
        assertEquals(MemoryDreamExtraction.MAX_CLUSTER_RECORDS, cluster.records.size)
        assertEquals("e1", cluster.records.first().id) // newest first
    }

    @Test fun extractionCommitsThroughTheSameGateWithFullProvenance() {
        val fixture = MemoryDreamPassTest.Fixture()
        val a = fixture.add("Went kayaking on Saturday", type = MemoryType.EPISODIC)
        val b = fixture.add("Went kayaking again on Sunday", type = MemoryType.EPISODIC)
        val c = fixture.add("Kayaked the river on Tuesday", type = MemoryType.EPISODIC)
        fixture.judge = MemoryDreamPassTest.FakeJudge { null }.apply {
            extractHandler = { cluster ->
                listOf(DreamExtractedFact(
                    "The user kayaks on the river regularly.",
                    MemoryType.SEMANTIC,
                    cluster.records.map { it.id },
                    95,
                ))
            }
        }
        val entry = fixture.run()
        val created = entry.outputs.single { it.verb == DreamVerb.CREATE }
        assertEquals(DreamOutputAction.COMMITTED, created.action)
        val record = fixture.dreamRecords().single()
        assertEquals("The user kayaks on the river regularly.", record.content)
        assertEquals(MemoryType.SEMANTIC, record.type)
        assertEquals(setOf(a.id, b.id, c.id), record.source.provenance.drop(1).map { it.id }.toSet())
        // Episodes stay active and untouched beside the distilled fact.
        assertTrue(fixture.snapshot().memories.filter { it.id in setOf(a.id, b.id, c.id) }
            .all { it.reviewStatus == MemoryReviewStatus.APPROVED })
    }

    @Test fun singleObservationFactsAreRefusedAtTheGate() {
        val fixture = MemoryDreamPassTest.Fixture()
        fixture.add("Went kayaking on Saturday", type = MemoryType.EPISODIC)
        fixture.add("Went kayaking again on Sunday", type = MemoryType.EPISODIC)
        fixture.add("Kayaked the river on Tuesday", type = MemoryType.EPISODIC)
        fixture.judge = MemoryDreamPassTest.FakeJudge { null }.apply {
            extractHandler = { cluster ->
                listOf(DreamExtractedFact(
                    "The user kayaks on the river regularly.",
                    MemoryType.SEMANTIC,
                    listOf(cluster.records.first().id), // one observation only
                    95,
                ))
            }
        }
        val entry = fixture.run()
        val refused = entry.outputs.single { it.verb == DreamVerb.CREATE }
        assertEquals(DreamOutputAction.JOURNALED, refused.action)
        assertEquals(DreamReasons.EVIDENCE_FLOOR, refused.reason)
        assertTrue(fixture.dreamRecords().isEmpty())
    }

    @Test fun belowClusterSizeProducesNothing() {
        val fixture = MemoryDreamPassTest.Fixture()
        fixture.add("Went kayaking on Saturday", type = MemoryType.EPISODIC)
        fixture.add("Went kayaking again on Sunday", type = MemoryType.EPISODIC)
        fixture.judge = MemoryDreamPassTest.FakeJudge { null }.apply {
            extractHandler = { error("judge must not be called for a two-episode category") }
        }
        val entry = fixture.run()
        assertEquals(DreamRunOutcome.COMPLETED, entry.outcome)
        assertEquals(0, fixture.judge.extractCalls)
        assertTrue(fixture.dreamRecords().isEmpty())
    }
}
