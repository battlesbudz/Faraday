package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryHybridRetrievalTest {
    private val now = 1_700_000_000_000L

    private fun record(
        id: String,
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        status: MemoryReviewStatus = MemoryReviewStatus.APPROVED,
        confidence: Int = 80,
        updatedAtMs: Long = now,
        expiresAtMs: Long? = null,
    ) = MemoryRecord(
        id = id, content = content, category = category, tier = MemoryTier.LONG_TERM,
        type = MemoryType.SEMANTIC, confidence = confidence,
        source = MemorySource("evt-$id", "unit test", now), reviewStatus = status,
        createdAtMs = now, updatedAtMs = updatedAtMs, revision = 1, expiresAtMs = expiresAtMs,
    )

    private fun snapshot(vararg records: MemoryRecord) = MemorySnapshot(1, records.toList(), emptyList())

    private fun hybridWith(engine: MemoryEmbeddingEngine, vectors: MemoryVectorStore) =
        MemoryHybridRetrieval(MemoryEmbeddingService(engine), vectors)

    private fun indexAll(
        service: MemoryEmbeddingService,
        vectors: MemoryGoldenCorpus.FakeVectorStore,
        vararg records: MemoryRecord,
    ) {
        for (record in records) vectors.put(MemoryVectorEntry(
            record.id, AutomaticMemory.contentFingerprint(record.content),
            service.spec.id, service.spec.storedDimension, service.embedDocument(record.content), now,
        ))
    }

    @Test fun reciprocalRankFusionMath() {
        val fused = MemoryRankFusion.fuse(listOf(listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("b", "a", "c"), fused.map { it.first })
        assertEquals(1.0 / 62 + 1.0 / 61, fused[0].second, 1e-12)
        assertEquals(1.0 / 61, fused[1].second, 1e-12)
        assertEquals(1.0 / 62, fused[2].second, 1e-12)
    }

    @Test fun paraphraseIsFoundSemanticallyWithQueryEmbeddedOnce() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        val tea = record("tea", MemoryGoldenCorpus.Text.TEA, MemoryCategory.PREFERENCE)
        val car = record("car", MemoryGoldenCorpus.Text.CAR)
        indexAll(service, vectors, tea, car)
        val before = engine.queryEmbedCalls
        val result = hybridWith(engine, vectors).retrieve(snapshot(tea, car), MemoryGoldenCorpus.Queries.TEA_PARAPHRASE, 5, now)
        assertEquals(SemanticRetrievalState.AVAILABLE, result.semanticState)
        assertEquals(1, engine.queryEmbedCalls - before)
        assertEquals(1, result.semanticCandidates)
        // The tea record rides the profile slice (PREFERENCE), not the searched list.
        assertEquals(listOf("tea"), result.profile.map { it.id })
        assertTrue(result.searched.none { it.memory.id == "car" })
    }

    @Test fun unavailableEngineFallsBackToLexicalAndWikiAndSaysSo() {
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        val notebook = record("nb", MemoryGoldenCorpus.Text.NOTEBOOK)
        val result = hybridWith(UnavailableMemoryEmbeddingEngine, vectors)
            .retrieve(snapshot(notebook), MemoryGoldenCorpus.Queries.NOTEBOOK, 5, now)
        assertEquals(SemanticRetrievalState.UNAVAILABLE, result.semanticState)
        assertEquals(0, result.semanticCandidates)
        assertEquals(listOf("nb"), result.searched.map { it.memory.id })
        assertTrue(result.diagnosticsLine().contains("semantic=unavailable"))
    }

    @Test fun vectorStoreErrorDegradesWithoutBlockingTheTurn() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val vectors = MemoryGoldenCorpus.FakeVectorStore().apply { readError = "disk gone" }
        val notebook = record("nb", MemoryGoldenCorpus.Text.NOTEBOOK)
        val result = hybridWith(engine, vectors)
            .retrieve(snapshot(notebook), MemoryGoldenCorpus.Queries.NOTEBOOK, 5, now)
        assertEquals(SemanticRetrievalState.ERROR, result.semanticState)
        assertEquals(listOf("nb"), result.searched.map { it.memory.id })
        assertEquals(0, engine.queryEmbedCalls)
    }

    @Test fun availableEngineWithNoCurrentVectorsReportsEmptyAndNeverEmbeds() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        val stale = record("stale", MemoryGoldenCorpus.Text.CONTACT)
        val vector = FloatArray(service.spec.storedDimension).also { it[MemoryGoldenCorpus.AXIS_PHONE] = 1f }
        vectors.put(MemoryVectorEntry(stale.id, "wrong-stamp", service.spec.id, service.spec.storedDimension, vector, now))
        val result = hybridWith(engine, vectors)
            .retrieve(snapshot(stale), MemoryGoldenCorpus.Queries.BROTHER_PHONE_NEGATIVE, 5, now)
        assertEquals(SemanticRetrievalState.EMPTY, result.semanticState)
        assertEquals(0, engine.queryEmbedCalls)
        assertTrue(result.searched.isEmpty())
    }

    @Test fun weakSingleTokenLexicalHitIsFilteredOnlyWhenSemanticRan() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        // A FACT record (never profile-eligible) sharing exactly one token — "brother" —
        // with the phone-number question, and semantically unrelated to it.
        val weak = record("weak", "My brother keeps his old sedan parked behind the house.")
        val car = record("car", MemoryGoldenCorpus.Text.CAR)
        indexAll(service, vectors, weak, car)
        val query = MemoryGoldenCorpus.Queries.BROTHER_PHONE_NEGATIVE
        val withSemantic = hybridWith(engine, vectors).retrieve(snapshot(weak, car), query, 5, now)
        assertEquals(SemanticRetrievalState.AVAILABLE, withSemantic.semanticState)
        assertTrue(withSemantic.searched.isEmpty())

        val withoutSemantic = hybridWith(UnavailableMemoryEmbeddingEngine, vectors)
            .retrieve(snapshot(weak, car), query, 5, now)
        assertEquals(listOf("weak"), withoutSemantic.searched.map { it.memory.id })
    }

    @Test fun profileSliceSelectionCapsAndOrdersDeterministically() {
        val records = listOf(
            record("p-low", "Low person", MemoryCategory.PERSON, confidence = 50, updatedAtMs = now + 3),
            record("p-high", "High person", MemoryCategory.PERSON, confidence = 90, updatedAtMs = now + 1),
            record("pref-a", "Pref a", MemoryCategory.PREFERENCE, confidence = 99, updatedAtMs = now + 9),
            record("pref-b", "Pref b", MemoryCategory.PREFERENCE, confidence = 70, updatedAtMs = now + 2),
            record("pref-c", "Pref c", MemoryCategory.PREFERENCE, confidence = 60, updatedAtMs = now + 4),
            record("fact", "A fact", MemoryCategory.FACT, confidence = 100),
            record("goal", "A goal", MemoryCategory.GOAL, confidence = 100),
            record("pending-pref", "Pending pref", MemoryCategory.PREFERENCE, status = MemoryReviewStatus.PENDING, confidence = 100),
        )
        val result = hybridWith(UnavailableMemoryEmbeddingEngine, MemoryGoldenCorpus.FakeVectorStore())
            .retrieve(snapshot(*records.toTypedArray()), "unrelated question about zebra stripes", 5, now + 10)
        assertEquals(listOf("p-high", "p-low", "pref-a", "pref-b"), result.profile.map { it.id })
    }

    @Test fun diagnosticsLineIsContentFreeAndExact() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        val notebook = record("nb", MemoryGoldenCorpus.Text.NOTEBOOK)
        val car = record("car", MemoryGoldenCorpus.Text.CAR)
        indexAll(service, vectors, notebook, car)
        val result = hybridWith(engine, vectors).retrieve(snapshot(notebook, car), MemoryGoldenCorpus.Queries.NOTEBOOK, 5, now)
        assertEquals(
            "Memory retrieval: lexical=1 semantic=1 wiki=1 fused=1 profile=0 semantic=available top=[nb]",
            result.diagnosticsLine(),
        )
        assertFalse(result.diagnosticsLine().contains("teal"))
    }

    private fun MemoryOs.addApproved(content: String, category: MemoryCategory): MemoryRecord {
        val proposed = propose(MemoryProposal(content, MemorySource("evt-${content.hashCode()}", "unit test", now), category))
        assertEquals(MemoryOutcome.CREATED, proposed.outcome)
        assertEquals(MemoryOutcome.APPROVED, approve(proposed.recordId!!).outcome)
        return read().snapshot!!.memories.first { it.id == proposed.recordId }
    }

    @Test fun contextPacketWiresHybridDiagnosticsProfileAndZeroBudgetSkipsEmbedding() {
        val engine = MemoryGoldenCorpus.ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = MemoryGoldenCorpus.FakeVectorStore()
        val file: File = Files.createTempFile("memory-hybrid-os", ".json").toFile()
        val os = MemoryOs(MemoryStore(file), { now }, { MemoryHybridRetrieval(service, vectors) })
        val tea = os.addApproved(MemoryGoldenCorpus.Text.TEA, MemoryCategory.PREFERENCE)
        val notebook = os.addApproved(MemoryGoldenCorpus.Text.NOTEBOOK, MemoryCategory.FACT)
        indexAll(service, vectors, tea, notebook)

        val embedsBefore = engine.queryEmbedCalls
        val result = os.contextPacket(MemoryGoldenCorpus.Queries.TEA_PARAPHRASE, 4_000, limit = 5, nowMs = now)
        assertNull(result.outcome)
        assertEquals(1, engine.queryEmbedCalls - embedsBefore)
        assertTrue(result.diagnostics!!.startsWith("Memory retrieval: "))
        assertTrue(result.diagnostics!!.contains("semantic=available"))
        assertEquals(listOf(tea.id), result.packet!!.memories.map { it.id })

        // The delivery fence's zero-budget recheck must not embed or re-run sources.
        val recheck = os.contextPacket(MemoryGoldenCorpus.Queries.TEA_PARAPHRASE, 0, limit = 5, nowMs = now)
        assertNull(recheck.outcome)
        assertEquals("", recheck.packet!!.text)
        assertNull(recheck.diagnostics)
        assertEquals(1, engine.queryEmbedCalls - embedsBefore)
        assertEquals(result.stateToken, recheck.stateToken)
    }

    @Test fun contextPacketWithoutProviderIsLexicalAndDiagnosticFree() {
        val file: File = Files.createTempFile("memory-lexical-os", ".json").toFile()
        val os = MemoryOs(MemoryStore(file), { now })
        val notebook = os.addApproved(MemoryGoldenCorpus.Text.NOTEBOOK, MemoryCategory.FACT)
        val result = os.contextPacket(MemoryGoldenCorpus.Queries.NOTEBOOK, 4_000, limit = 5, nowMs = now)
        assertNull(result.outcome)
        assertNull(result.diagnostics)
        assertEquals(listOf(notebook.id), result.packet!!.memories.map { it.id })
    }
}
