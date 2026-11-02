package com.battlesbudz.jarvis.v2.memory

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamGroupingTest {
    private val now = 1_700_000_000_000L

    private fun record(
        id: String,
        updatedAtMs: Long = now,
        status: MemoryReviewStatus = MemoryReviewStatus.APPROVED,
        eventSource: String = "auto-memory/chat",
        sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
        expiresAtMs: Long? = null,
        category: MemoryCategory = MemoryCategory.FACT,
        provenance: List<MemoryProvenance> = emptyList(),
    ): MemoryRecord = MemoryRecord(
        id = id,
        content = "Record $id",
        category = category,
        tier = MemoryTier.LONG_TERM,
        type = MemoryType.SEMANTIC,
        confidence = 80,
        source = MemorySource("event-$id", eventSource, now - 10_000L, sensitivity, provenance),
        reviewStatus = status,
        createdAtMs = now - 10_000L,
        updatedAtMs = updatedAtMs,
        revision = 1L,
        expiresAtMs = expiresAtMs,
    )

    /** A 2-D unit vector whose cosine against the x-axis is exactly [cosine]. */
    private fun vectorAt(cosine: Double): FloatArray =
        floatArrayOf(cosine.toFloat(), sqrt(1.0 - cosine * cosine).toFloat())

    private val xAxis = floatArrayOf(1f, 0f)

    @Test fun eligibilityExcludesPendingSupersededExpiredAndRestrictedRecords() {
        val ok = record("ok")
        val records = listOf(
            ok,
            record("pending", status = MemoryReviewStatus.PENDING),
            record("superseded", status = MemoryReviewStatus.SUPERSEDED),
            record("expired", expiresAtMs = now),
            record("restricted", sensitivity = MemorySensitivity.RESTRICTED),
        )
        assertEquals(listOf(ok), MemoryDreamGrouping.eligible(records, now))
    }

    @Test fun groupingIsDeterministicRegardlessOfInputOrder() {
        val a = record("a"); val b = record("b"); val c = record("c")
        val vectors = mapOf("a" to xAxis, "b" to vectorAt(0.95), "c" to vectorAt(0.10))
        val first = MemoryDreamGrouping.formGroups(listOf(a, b, c), 0L, vectors, now)
        val second = MemoryDreamGrouping.formGroups(listOf(c, a, b), 0L, vectors, now)
        assertEquals(first.groups.map { it.key }, second.groups.map { it.key })
        assertEquals(1, first.groups.size)
        assertEquals(setOf("a", "b"), first.groups.single().records.map { it.id }.toSet())
        assertEquals(setOf("c"), first.singletonSeedIds)
    }

    @Test fun bandBoundariesFollowTheCalibratedThresholds() {
        fun bandFor(cosine: Double): DreamBand {
            val seed = record("seed"); val neighbour = record("neighbour", updatedAtMs = now - 5_000L)
            val result = MemoryDreamGrouping.formGroups(
                listOf(seed, neighbour), 0L,
                mapOf("seed" to xAxis, "neighbour" to vectorAt(cosine)), now,
            )
            return result.groups.single().band
        }
        assertEquals(DreamBand.MERGE_CANDIDATE, bandFor(0.95))
        assertEquals(DreamBand.JUDGE_ONLY, bandFor(0.90))
        assertEquals(DreamBand.JUDGE_ONLY, bandFor(0.85))
    }

    @Test fun belowTheLinkFloorNoGroupFormsAndTheSeedIsASingleton() {
        val seed = record("seed"); val other = record("other", updatedAtMs = now - 5_000L)
        val result = MemoryDreamGrouping.formGroups(
            listOf(seed, other), 0L,
            mapOf("seed" to xAxis, "other" to vectorAt(0.50)), now,
        )
        assertTrue(result.groups.isEmpty())
        assertEquals(setOf("seed", "other"), result.singletonSeedIds)
    }

    @Test fun dreamDerivedRecordsNeverSeedButMayNeighbourACanonicalSeed() {
        val canonical = record("canonical")
        val derived = record(
            "derived",
            eventSource = "dream_cycle/dream-run-1",
            provenance = listOf(MemoryProvenance("dream_cycle", "dream-run-1", "Nightly dream synthesis")),
        )
        val result = MemoryDreamGrouping.formGroups(
            listOf(canonical, derived), 0L,
            mapOf("canonical" to xAxis, "derived" to vectorAt(0.95)), now,
        )
        val group = result.groups.single()
        assertEquals(setOf("canonical"), group.seedIds)
        assertEquals(setOf("canonical", "derived"), group.records.map { it.id }.toSet())
        assertFalse(group.allDerived)
        assertTrue(group.hasCanonicalSource)
    }

    @Test fun DerivedNotSoleSourceTest() {
        // Both records are old (below the watermark change frontier is irrelevant
        // here): give one a canonical twin so a group forms, then check the flag
        // logic directly on an all-derived selection via a derived-only pair and
        // a synthetic canonical seed that is then removed is overkill — instead
        // assert the predicate on records the grouping itself classifies.
        val derivedA = record("da", eventSource = "dream_cycle/run-a")
        val derivedB = record("db", eventSource = "dream_cycle/run-b")
        assertTrue(MemoryDreamGrouping.isDreamDerived(derivedA))
        assertTrue(MemoryDreamGrouping.isDreamDerived(derivedB))
        // Derived records cannot seed, so no group forms from them alone.
        val result = MemoryDreamGrouping.formGroups(
            listOf(derivedA, derivedB), 0L,
            mapOf("da" to xAxis, "db" to vectorAt(0.99)), now,
        )
        assertTrue(result.groups.isEmpty())
    }

    @Test fun SettingsEnteredEvidenceOnlyTest() {
        val manual = record("manual", eventSource = MemoryDreamGrouping.SETTINGS_EVENT_SOURCE)
        val auto = record("auto")
        val vectors = mapOf("manual" to xAxis, "auto" to vectorAt(0.95))
        val group = MemoryDreamGrouping.formGroups(listOf(manual, auto), 0L, vectors, now).groups.single()
        assertTrue(group.containsSettingsEntered)
        assertTrue(MemoryDreamGrouping.isSettingsEntered(manual))
        assertFalse(MemoryDreamGrouping.isSettingsEntered(auto))
    }

    @Test fun groupsAreCappedAtTheProvenanceCeiling() {
        val records = (1..10).map { record("r$it") }
        val vectors = records.associate { it.id to xAxis }
        val group = MemoryDreamGrouping.formGroups(records, 0L, vectors, now).groups.single()
        assertEquals(MemoryDreamGrouping.MAX_GROUP_RECORDS, group.records.size)
        assertTrue(group.records.size <= MemoryPolicy.MAX_PROVENANCE - 1)
    }

    @Test fun unvectorizedSeedsAreReportedUnprocessed() {
        val seed = record("seed")
        val result = MemoryDreamGrouping.formGroups(listOf(seed), 0L, emptyMap(), now)
        assertEquals(setOf("seed"), result.unvectorizedSeedIds)
        assertTrue(result.singletonSeedIds.isEmpty())
    }
}
