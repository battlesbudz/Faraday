package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Unit 2 evaluation gate (in-repo plan §8): Recall@5 for the shipped lexical-only
 * production path vs the hybrid production path, measured on the fixed golden corpus
 * through [MemoryOs.contextPacket] itself — the packet order the model actually sees
 * (profile slice first, then searched results) is what gets ranked. Hybrid may
 * replace lexical in production wiring only if it beats lexical here.
 */
class MemoryGoldenCorpusTest {
    private val world = MemoryGoldenCorpus.buildWorld()
    private val now = world.now

    private data class PositiveCase(val query: String, val targetText: String)

    private val positives = listOf(
        PositiveCase(MemoryGoldenCorpus.Queries.NOTEBOOK, MemoryGoldenCorpus.Text.NOTEBOOK),
        PositiveCase(MemoryGoldenCorpus.Queries.TEA_PARAPHRASE, MemoryGoldenCorpus.Text.TEA),
        PositiveCase(MemoryGoldenCorpus.Queries.VEHICLE_PARAPHRASE, MemoryGoldenCorpus.Text.CAR),
        PositiveCase(MemoryGoldenCorpus.Queries.SPOUSE_COREFERENCE, MemoryGoldenCorpus.Text.WIFE),
        PositiveCase(MemoryGoldenCorpus.Queries.BROTHER_COREFERENCE, MemoryGoldenCorpus.Text.BROTHER_HOME),
        PositiveCase(MemoryGoldenCorpus.Queries.GOAL_PARAPHRASE, MemoryGoldenCorpus.Text.CABIN),
        PositiveCase(MemoryGoldenCorpus.Queries.SEAT_PARAPHRASE, MemoryGoldenCorpus.Text.SEAT),
        PositiveCase(MemoryGoldenCorpus.Queries.DENTIST_DIRECT, MemoryGoldenCorpus.Text.DENTIST),
        PositiveCase(MemoryGoldenCorpus.Queries.SKATE_PARAPHRASE, MemoryGoldenCorpus.Text.SKATE),
        PositiveCase(MemoryGoldenCorpus.Queries.HOME_PARAPHRASE, MemoryGoldenCorpus.Text.HOME),
    )

    private fun packetIds(os: MemoryOs, query: String, limit: Int = 5): List<String> {
        val result = os.contextPacket(query, PACKET_BUDGET, limit = limit, nowMs = now)
        assertEquals(null, result.outcome)
        return result.packet!!.memories.map { it.memory.id }
    }

    private fun recallAt5(cases: List<PositiveCase>, ranking: (String) -> List<String>): Double {
        if (cases.isEmpty()) return 1.0
        return cases.sumOf { case ->
            val target = world.byText.getValue(case.targetText).id
            if (target in ranking(case.query).take(5)) 1.0 else 0.0
        } / cases.size
    }

    @Test fun hybridBeatsLexicalOnGoldenCorpusRecallAt5() {
        val lexicalRecall = recallAt5(positives) { packetIds(world.lexicalOs, it) }
        val hybridRecall = recallAt5(positives) { packetIds(world.os, it) }
        println("GOLDEN-CORPUS Recall@5 lexical-only=$lexicalRecall hybrid=$hybridRecall positives=${positives.size}")
        for (case in positives) {
            println("GOLDEN-CORPUS case query='${case.query}' lexicalTop5=${packetIds(world.lexicalOs, case.query)} hybridTop5=${packetIds(world.os, case.query)} target=${world.byText.getValue(case.targetText).id}")
        }
        // The corpus is built so the lexical baseline can only reach the two
        // vocabulary-sharing controls; if this drifts up, the corpus no longer
        // discriminates and must be re-examined rather than silently re-baselined.
        assertTrue("lexical baseline drifted: $lexicalRecall", lexicalRecall <= 0.3)
        assertTrue("hybrid must beat lexical: hybrid=$hybridRecall lexical=$lexicalRecall", hybridRecall > lexicalRecall)
        assertTrue("hybrid Recall@5 below the release bar: $hybridRecall", hybridRecall >= 0.9)
    }

    @Test fun negativesInjectNothingSearched() {
        // Stored-topic / unstored-fact questions: a relative, an appointment, a
        // birthday exist as topics in the ledger but the asked facts do not. The
        // hybrid path must inject no searched result (the always-on profile slice
        // is profile data, not a query answer, and is asserted separately).
        val profileIds = world.retriever.retrieve(world.snapshot(), MemoryGoldenCorpus.Queries.NOTEBOOK, 5, now)
            .profile.map { it.id }.toSet()
        for (query in listOf(
            MemoryGoldenCorpus.Queries.BROTHER_PHONE_NEGATIVE,
            MemoryGoldenCorpus.Queries.DENTIST_APPOINTMENT_NEGATIVE,
            MemoryGoldenCorpus.Queries.WIFE_BIRTHDAY_NEGATIVE,
        )) {
            val result = world.retriever.retrieve(world.snapshot(), query, 5, now)
            assertEquals("searched must be empty for '$query': ${result.searched.map { it.memory.content }}", emptyList<RetrievedMemory>(), result.searched)
            val injected = packetIds(world.os, query, limit = 8)
            assertTrue("packet carries only profile ids for '$query': $injected", injected.all { it in profileIds })
        }
    }

    @Test fun noRelevantMemoryTurnsStayEmpty() {
        for (query in listOf(MemoryGoldenCorpus.Queries.BLOOD_TYPE_NONE, MemoryGoldenCorpus.Queries.EIFFEL_NONE)) {
            val result = world.retriever.retrieve(world.snapshot(), query, 5, now)
            assertEquals("'$query'", emptyList<RetrievedMemory>(), result.searched)
        }
    }

    @Test fun publicInformationBoundarySkipsEverySource() {
        val result = world.retriever.retrieve(world.snapshot(), MemoryGoldenCorpus.Queries.WEATHER_BOUNDARY, 5, now)
        assertEquals(SemanticRetrievalState.SKIPPED, result.semanticState)
        assertTrue(result.profile.isEmpty())
        assertTrue(result.searched.isEmpty())
        // The lexical production path agrees: no packet entries for the same query.
        assertTrue(packetIds(world.lexicalOs, MemoryGoldenCorpus.Queries.WEATHER_BOUNDARY, limit = 8).isEmpty())
    }

    @Test fun ineligibleAndStaleRecordsNeverSurface() {
        val banned = setOf(
            world.byText.getValue(MemoryGoldenCorpus.Text.PENDING_TEA).id,
            world.byText.getValue(MemoryGoldenCorpus.Text.EXPIRED_ESPRESSO).id,
            world.byText.getValue(MemoryGoldenCorpus.Text.TRUCK_SUPERSEDED).id,
            world.byText.getValue(MemoryGoldenCorpus.Text.CONTACT).id,
        )
        for (case in positives) {
            val result = world.retriever.retrieve(world.snapshot(), case.query, 8, now)
            val surfaced = result.profile.map { it.id } + result.searched.map { it.memory.id }
            assertTrue("'${case.query}' surfaced a banned record: $surfaced", surfaced.none { it in banned })
        }
        // The changed-fact case in particular: the current car record answers, the
        // superseded truck record is nowhere in the production packet.
        val vehiclePacket = packetIds(world.os, MemoryGoldenCorpus.Queries.VEHICLE_PARAPHRASE, limit = 8)
        assertTrue(world.byText.getValue(MemoryGoldenCorpus.Text.CAR).id in vehiclePacket)
        assertTrue(world.byText.getValue(MemoryGoldenCorpus.Text.TRUCK_SUPERSEDED).id !in vehiclePacket)
    }

    private companion object { const val PACKET_BUDGET = 4_000 }
}
