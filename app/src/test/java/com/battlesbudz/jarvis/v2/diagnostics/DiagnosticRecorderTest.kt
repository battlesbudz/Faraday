package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.memory.EmbeddingEngineUnavailable
import com.battlesbudz.jarvis.v2.memory.EmbeddingModelCatalog
import com.battlesbudz.jarvis.v2.memory.EmbeddingRole
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingEngine
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingIndexer
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingService
import com.battlesbudz.jarvis.v2.memory.MemoryOs
import com.battlesbudz.jarvis.v2.memory.MemoryProposal
import com.battlesbudz.jarvis.v2.memory.MemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryVectorEntry
import com.battlesbudz.jarvis.v2.memory.MemoryVectorStore
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderTest {
    @Test fun exactPromptSurvivesEventChatterAndProcessRestoreAndClearsAtNewCall() {
        val prefs = preferences()
        val recorder = DiagnosticRecorder(prefs, "test")
        recorder.startSession("call-one")
        val prompt = "model=e4b historySource=current_call\n" + "full prompt ".repeat(500) + "FINAL CURRENT QUESTION"
        recorder.recordInferencePrompt(prompt)
        repeat(150) { recorder.record("audio $it") }
        assertTrue(recorder.snapshot().contains(prompt))
        val restored = DiagnosticRecorder(prefs, "test")
        restored.restore()
        assertTrue(restored.snapshot().contains(prompt))
        restored.startSession("call-two")
        assertFalse(restored.snapshot().contains(prompt))
    }
    @Test fun promptRetentionIsBoundedSeparatelyFromTimingEvents() {
        val recorder = DiagnosticRecorder(preferences())
        repeat(30) { recorder.recordInferencePrompt("prompt-number-$it-END") }
        assertFalse(recorder.snapshot().contains("prompt-number-5-END"))
        assertTrue(recorder.snapshot().contains("prompt-number-6-END"))
        assertTrue(recorder.snapshot().contains("prompt-number-29-END"))
    }
    @Test fun snapshotCarriesTheEmbeddingsLineFromFakedServices() {
        // The standard diagnostics export is the recorder snapshot (both Copy
        // Diagnostics assembly sites build on it). With the live section bound to
        // a real indexer over faked services — engine unavailable, model not
        // installed — the export must carry the honest line.
        val now = 1_700_000_000_000L
        val engine = object : MemoryEmbeddingEngine {
            override val modelId: String = EmbeddingModelCatalog.default.id
            override val available: Boolean = false
            override val unavailableReason: String = "Embedding model is not installed."
            override fun embed(text: String, role: EmbeddingRole): FloatArray =
                throw EmbeddingEngineUnavailable("Embedding model is not installed.")
        }
        val vectors = object : MemoryVectorStore {
            val rows = linkedMapOf<String, MemoryVectorEntry>()
            override fun read(modelId: String) = MemoryVectorStore.Read(rows.values.filter { it.modelId == modelId })
            override fun put(entry: MemoryVectorEntry): Boolean { rows[entry.memoryId] = entry; return true }
            override fun remove(memoryIds: Collection<String>): Boolean { memoryIds.forEach { rows.remove(it) }; return true }
            override fun storedBytes(): Long = 0
        }
        val ledger = File(Files.createTempDirectory("diagnostics-ledger").toFile(), "memory-os.json")
        val os = MemoryOs(ledger) { now }
        val record = os.propose(MemoryProposal("Lives in Watertown", MemorySource("evt-a", "manual", now))).memory!!
        os.approve(record.id)
        val indexer = MemoryEmbeddingIndexer(os, vectors, MemoryEmbeddingService(engine)) { now }

        val recorder = DiagnosticRecorder(preferences(), "test")
        recorder.startSession("call-one")
        assertFalse(recorder.snapshot().contains("Memory embeddings:"))
        recorder.liveSection = { indexer.diagnosticsLine() }
        assertTrue(recorder.snapshot().contains(
            "Memory embeddings: model=embeddinggemma-2-text-270m dim=256 vectors=0 pending=1 engine=unavailable (Embedding model is not installed.)"))
        // A failing probe degrades to the plain snapshot instead of breaking the export.
        recorder.liveSection = { throw IllegalStateException("probe failed") }
        assertFalse(recorder.snapshot().contains("Memory embeddings:"))
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; editor }
                "remove" -> { values.remove(args!![0] as String); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
