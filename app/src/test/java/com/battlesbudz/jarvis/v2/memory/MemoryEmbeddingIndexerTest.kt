package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MemoryEmbeddingIndexerTest {
    private var now = 1_700_000_000_000L
    private val spec = EmbeddingModelCatalog.default

    /** Deterministic stand-in for the real model: a stable, text-derived 768-wide vector. */
    private class FakeEngine(override val available: Boolean = true) : MemoryEmbeddingEngine {
        override val modelId: String = EmbeddingModelCatalog.default.id
        var embedCalls = 0
        var refuseOnEmbed = false
        override fun embed(text: String, role: EmbeddingRole): FloatArray {
            embedCalls++
            if (refuseOnEmbed) throw EmbeddingEngineUnavailable("refused")
            val raw = FloatArray(768)
            raw[0] = (text.hashCode() % 89 + 100).toFloat()
            raw[1] = text.length.toFloat()
            raw[2] = 1f
            return raw
        }
    }

    private class FakeVectorStore : MemoryVectorStore {
        val rows = linkedMapOf<String, MemoryVectorEntry>()
        override fun read(modelId: String) = MemoryVectorStore.Read(rows.values.filter { it.modelId == modelId })
        override fun put(entry: MemoryVectorEntry): Boolean { rows[entry.memoryId] = entry; return true }
        override fun remove(memoryIds: Collection<String>): Boolean { memoryIds.forEach { rows.remove(it) }; return true }
        override fun storedBytes(): Long = rows.values.sumOf { it.vector.size * 4L }
    }

    private fun tempLedger(): File = File(Files.createTempDirectory("memory-ledger").toFile(), "memory-os.json")
    private fun source(id: String) = MemorySource(id, "manual", now)

    private class Fixture(val engine: FakeEngine = FakeEngine()) {
        val vectors = FakeVectorStore()
        lateinit var os: MemoryOs
        lateinit var indexer: MemoryEmbeddingIndexer
    }

    private fun fixture(engine: FakeEngine = FakeEngine(), nowRef: () -> Long): Fixture {
        val f = Fixture(engine)
        f.os = MemoryOs(tempLedger()) { nowRef() }
        val service = MemoryEmbeddingService(engine, spec)
        f.indexer = MemoryEmbeddingIndexer(f.os, f.vectors, service) { nowRef() }
        return f
    }

    private fun approved(os: MemoryOs, content: String, event: String, expiresAtMs: Long? = null): MemoryRecord {
        val record = os.propose(MemoryProposal(content, source(event), expiresAtMs = expiresAtMs)).memory!!
        assertEquals(MemoryOutcome.APPROVED, os.approve(record.id).outcome)
        return os.read().snapshot!!.memories.first { it.id == record.id }
    }

    @Test fun backfillEmbedsOnlyApprovedUnexpiredRecords() {
        val f = fixture { now }
        val a = approved(f.os, "Lives in Watertown", "a")
        val b = approved(f.os, "Likes black coffee", "b")
        f.os.propose(MemoryProposal("Still pending", source("c")))
        val rejected = f.os.propose(MemoryProposal("Will be rejected", source("d"))).memory!!
        assertEquals(MemoryOutcome.REJECTED, f.os.reject(rejected.id).outcome)
        approved(f.os, "Expires before the sync", "e", expiresAtMs = now + 1_000)
        now += 2_000

        val report = f.indexer.sync()
        assertNull(report.error)
        assertTrue(report.engineAvailable)
        assertEquals(2, report.embedded)
        assertEquals(0, report.pending)
        assertEquals(2, f.vectors.rows.size)
        assertEquals(setOf(a.id, b.id), f.vectors.rows.keys)
        assertTrue(f.vectors.rows.values.all { it.modelId == spec.id && it.dimension == spec.storedDimension })
        assertTrue(f.vectors.rows.values.all { it.vector.size == spec.storedDimension })
    }

    @Test fun secondSyncIsANoOpWithNoFurtherEngineCalls() {
        val f = fixture { now }
        approved(f.os, "Lives in Watertown", "a")
        approved(f.os, "Likes black coffee", "b")
        val first = f.indexer.sync()
        assertEquals(2, first.embedded)
        val callsAfterFirst = f.engine.embedCalls

        val second = f.indexer.sync()
        assertEquals(0, second.embedded)
        assertEquals(2, second.upToDate)
        assertEquals(0, second.pending)
        assertEquals(callsAfterFirst, f.engine.embedCalls)
    }

    @Test fun contentChangeThroughCorrectionReembedsAndPurgesTheSupersededVector() {
        val f = fixture { now }
        val original = approved(f.os, "Lives in Austin", "a")
        f.indexer.sync()
        assertTrue(f.vectors.rows.containsKey(original.id))

        val correction = f.os.propose(
            MemoryProposal("Lives in Dallas", source("b"), correctsMemoryId = original.id, expectedTargetRevision = original.revision),
        ).memory!!
        assertEquals(MemoryOutcome.APPROVED, f.os.approve(correction.id).outcome)

        val report = f.indexer.sync()
        assertEquals(1, report.removed)
        assertEquals(1, report.embedded)
        assertFalse(f.vectors.rows.containsKey(original.id))
        assertTrue(f.vectors.rows.containsKey(correction.id))
        assertEquals(AutomaticMemory.contentFingerprint("Lives in Dallas"), f.vectors.rows[correction.id]!!.contentFingerprint)
    }

    @Test fun deletionAndExpiryRemoveVectors() {
        val f = fixture { now }
        val deleted = approved(f.os, "Delete me later", "a")
        val expiring = approved(f.os, "Expires soon", "b", expiresAtMs = now + 60_000)
        f.indexer.sync()
        assertEquals(2, f.vectors.rows.size)

        assertEquals(MemoryOutcome.DELETED, f.os.delete(deleted.id).outcome)
        now += 60_001
        val report = f.indexer.sync()
        assertEquals(2, report.removed)
        assertTrue(f.vectors.rows.isEmpty())
        assertFalse(f.os.read().snapshot!!.memories.any { it.id == deleted.id })
        assertTrue(f.os.read().snapshot!!.memories.any { it.id == expiring.id })
    }

    @Test fun unavailableEngineReportsHonestlyAndEmbedsNothing() {
        val f = fixture(FakeEngine(available = false)) { now }
        approved(f.os, "Lives in Watertown", "a")
        approved(f.os, "Likes black coffee", "b")

        val report = f.indexer.sync()
        assertNull(report.error)
        assertFalse(report.engineAvailable)
        assertEquals(0, report.embedded)
        assertEquals(2, report.pending)
        assertTrue(f.vectors.rows.isEmpty())
        assertEquals(
            "Memory embeddings: model=embeddinggemma-2-text-270m dim=256 vectors=0 pending=2 engine=unavailable",
            report.diagnosticsLine(spec.id, spec.storedDimension, f.vectors.read(spec.id).entries!!.size),
        )
    }

    @Test fun engineThatRefusesMidSyncStopsAndReportsUnavailable() {
        val engine = FakeEngine()
        val f = fixture(engine) { now }
        approved(f.os, "First fact", "a")
        approved(f.os, "Second fact", "b")
        engine.refuseOnEmbed = true

        val report = f.indexer.sync()
        assertFalse(report.engineAvailable)
        assertEquals(0, report.embedded)
        assertEquals(2, report.pending)
        assertTrue(f.vectors.rows.isEmpty())
    }

    @Test fun productionPlaceholderNeverFabricatesVectors() {
        assertFalse(UnavailableMemoryEmbeddingEngine.available)
        assertThrows(EmbeddingEngineUnavailable::class.java) {
            UnavailableMemoryEmbeddingEngine.embed("anything", EmbeddingRole.DOCUMENT)
        }
        val service = MemoryEmbeddingService(UnavailableMemoryEmbeddingEngine)
        assertFalse(service.isAvailable)
        assertThrows(EmbeddingEngineUnavailable::class.java) { service.embedDocument("anything") }
    }

    @Test fun serviceFormatsRolesValidatesWidthAndNormalizes() {
        val engine = FakeEngine()
        val service = MemoryEmbeddingService(engine, spec)
        var seenDocument: String? = null
        var seenQuery: String? = null
        val capturing = object : MemoryEmbeddingEngine {
            override val modelId: String = engine.modelId
            override val available: Boolean = true
            override fun embed(text: String, role: EmbeddingRole): FloatArray {
                if (role == EmbeddingRole.DOCUMENT) seenDocument = text else seenQuery = text
                return engine.embed(text, role)
            }
        }
        val svc = MemoryEmbeddingService(capturing, spec)
        val document = svc.embedDocument("Lives in Watertown")
        assertEquals("title: none | text: Lives in Watertown", seenDocument)
        assertEquals(spec.storedDimension, document.size)
        val norm = kotlin.math.sqrt(document.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-5)
        svc.embedQuery("where do I live")
        assertEquals("task: search result | query: where do I live", seenQuery)
        assertTrue(service.isAvailable)
    }
}
