package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract tests for the production LiteRT-LM binding. The native runtime cannot
 * run in JVM tests (it loads the LiteRT-LM native library), so the runtime is
 * faked at the [EmbeddingRuntimeFactory] seam; everything above that seam —
 * presence checks, the SHA-256 integrity gate, lazy single creation, width
 * validation, failure latching — is the real binding code. The final test runs
 * the indexer live through the real binding against a real ledger.
 */
class LiteRtLmMemoryEmbeddingEngineTest {
    private var now = 1_700_000_000_000L

    /** A spec small enough to "install" in a test: 128 bytes of fixed content. */
    private val modelBytes = ByteArray(128) { (it * 7 % 251).toByte() }
    private val testSpec = EmbeddingModelSpec(
        id = "embeddinggemma-2-text-270m",
        fileName = "test-model.litertlm",
        downloadUrl = "https://example.invalid/test-model.litertlm",
        expectedSha256 = sha256Hex(modelBytes),
        downloadBytes = modelBytes.size.toLong(),
        outputDimension = 768,
        storedDimension = 256,
    )

    private object NoTransfer : EmbeddingTransfer {
        override suspend fun download(
            url: String,
            temporary: File,
            expectedSha256: String?,
            onProgress: (Long, Long) -> Unit,
        ) = Unit
    }

    private class FakeRuntime(
        private val width: Int = 768,
        private val failOnEmbedCall: Int = -1,
    ) : EmbeddingRuntime {
        var embedCalls = 0
        var closed = false
        override fun embed(text: String): FloatArray {
            embedCalls++
            if (embedCalls == failOnEmbedCall) throw IllegalStateException("native boom")
            val raw = FloatArray(width)
            if (width > 2) {
                raw[0] = (text.hashCode() % 89 + 100).toFloat()
                raw[1] = text.length.toFloat()
                raw[2] = 1f
            }
            return raw
        }
        override fun close() { closed = true }
    }

    private class Harness(
        val runtime: FakeRuntime = FakeRuntime(),
    ) {
        val dir: File = Files.createTempDirectory("embedding-engine").toFile()
        val store = EmbeddingModelStore(dir, NoTransfer)
        var createCalls = 0
        lateinit var spec: EmbeddingModelSpec
        lateinit var engine: LiteRtLmMemoryEmbeddingEngine

        fun install(bytes: ByteArray) {
            store.fileFor(spec).writeBytes(bytes)
        }
    }

    private fun harness(runtime: FakeRuntime = FakeRuntime(), failCreate: Boolean = false): Harness {
        val h = Harness(runtime)
        h.spec = testSpec
        h.engine = LiteRtLmMemoryEmbeddingEngine(
            h.store,
            testSpec,
            EmbeddingRuntimeFactory { _, _ ->
                h.createCalls++
                if (failCreate) throw IllegalStateException("no native lib")
                runtime
            },
        )
        return h
    }

    @Test fun missingModelReportsUnavailableWithReasonAndEmbedThrows() {
        val h = harness()
        assertFalse(h.engine.available)
        assertEquals("Embedding model is not installed.", h.engine.unavailableReason)
        assertThrows(EmbeddingEngineUnavailable::class.java) {
            h.engine.embed("title: none | text: x", EmbeddingRole.DOCUMENT)
        }
        assertEquals(0, h.createCalls)
    }

    @Test fun wrongLengthFileIsNotInstalledAndRuntimeIsNeverCreated() {
        val h = harness()
        h.install(ByteArray(64) { 1 })
        assertFalse(h.engine.available)
        assertThrows(EmbeddingEngineUnavailable::class.java) {
            h.engine.embed("x", EmbeddingRole.DOCUMENT)
        }
        assertEquals(0, h.createCalls)
    }

    @Test fun corruptModelFailsIntegrityBeforeAnyRuntimeCreation() {
        val h = harness()
        h.install(ByteArray(modelBytes.size) { 9 })
        // Presence (length) passes, so the cheap availability check is optimistic;
        // the integrity gate must refuse before the native runtime is created.
        assertTrue(h.engine.available)
        val error = assertThrows(EmbeddingEngineUnavailable::class.java) {
            h.engine.embed("x", EmbeddingRole.DOCUMENT)
        }
        assertTrue(error.message!!.contains("integrity"))
        assertEquals(0, h.createCalls)
        // Not latched: replacing the file with the pinned bytes recovers the engine.
        h.install(modelBytes)
        assertEquals(768, h.engine.embed("x", EmbeddingRole.DOCUMENT).size)
    }

    @Test fun installedModelEmbedsAtModelWidthAndReusesOneRuntime() {
        val h = harness()
        h.install(modelBytes)
        assertTrue(h.engine.available)
        assertNull(h.engine.unavailableReason)
        val first = h.engine.embed("title: none | text: Lives in Watertown", EmbeddingRole.DOCUMENT)
        assertEquals(768, first.size)
        h.engine.embed("task: search result | query: where do I live", EmbeddingRole.QUERY)
        assertEquals(1, h.createCalls)
        assertEquals(2, h.runtime.embedCalls)
    }

    @Test fun runtimeStartFailureLatchesAndStopsRetrying() {
        val h = harness(failCreate = true)
        h.install(modelBytes)
        assertThrows(EmbeddingEngineUnavailable::class.java) { h.engine.embed("x", EmbeddingRole.DOCUMENT) }
        assertFalse(h.engine.available)
        assertNotNull(h.engine.unavailableReason)
        assertThrows(EmbeddingEngineUnavailable::class.java) { h.engine.embed("x", EmbeddingRole.DOCUMENT) }
        assertEquals(1, h.createCalls)
    }

    @Test fun wrongWidthOutputFailsTheEngineInsteadOfFlowingDownstream() {
        val h = harness(runtime = FakeRuntime(width = 256))
        h.install(modelBytes)
        val error = assertThrows(EmbeddingEngineUnavailable::class.java) {
            h.engine.embed("x", EmbeddingRole.DOCUMENT)
        }
        assertTrue(error.message!!.contains("256"))
        assertFalse(h.engine.available)
        assertTrue(h.runtime.closed)
    }

    @Test fun midStreamRuntimeFailureLatchesAfterEarlierSuccesses() {
        val h = harness(runtime = FakeRuntime(failOnEmbedCall = 2))
        h.install(modelBytes)
        assertEquals(768, h.engine.embed("x", EmbeddingRole.DOCUMENT).size)
        assertThrows(EmbeddingEngineUnavailable::class.java) { h.engine.embed("x", EmbeddingRole.DOCUMENT) }
        assertFalse(h.engine.available)
        assertTrue(h.runtime.closed)
    }

    @Test fun indexerGoesLiveThroughTheRealBindingWithAFakeRuntime() {
        val h = harness()
        h.install(modelBytes)
        val service = MemoryEmbeddingService(h.engine, testSpec)
        val ledger = File(Files.createTempDirectory("memory-ledger").toFile(), "memory-os.json")
        val os = MemoryOs(ledger) { now }
        val vectors = FakeVectorStore()
        val indexer = MemoryEmbeddingIndexer(os, vectors, service) { now }

        val record = os.propose(MemoryProposal("Lives in Watertown", MemorySource("a", "manual", now))).memory!!
        assertEquals(MemoryOutcome.APPROVED, os.approve(record.id).outcome)
        os.propose(MemoryProposal("Still pending", MemorySource("b", "manual", now)))

        val report = indexer.sync()
        assertNull(report.error)
        assertTrue(report.engineAvailable)
        assertEquals(1, report.embedded)
        assertEquals(0, report.pending)
        assertEquals(256, vectors.rows[record.id]!!.vector.size)
        assertEquals(
            "Memory embeddings: model=embeddinggemma-2-text-270m dim=256 vectors=1 pending=0 engine=available",
            report.diagnosticsLine(testSpec.id, testSpec.storedDimension, 1),
        )

        val second = indexer.sync()
        assertEquals(0, second.embedded)
        assertEquals(1, second.upToDate)
        assertEquals(1, h.createCalls)
    }

    private class FakeVectorStore : MemoryVectorStore {
        val rows = linkedMapOf<String, MemoryVectorEntry>()
        override fun read(modelId: String) = MemoryVectorStore.Read(rows.values.filter { it.modelId == modelId })
        override fun put(entry: MemoryVectorEntry): Boolean { rows[entry.memoryId] = entry; return true }
        override fun remove(memoryIds: Collection<String>): Boolean { memoryIds.forEach { rows.remove(it) }; return true }
        override fun storedBytes(): Long = rows.values.sumOf { it.vector.size * 4L }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
