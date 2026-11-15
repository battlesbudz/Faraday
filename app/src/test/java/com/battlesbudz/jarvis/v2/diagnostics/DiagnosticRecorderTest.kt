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

    @Test fun snapshotJsonParsesAndMirrorsTheTextExportSections() {
        val recorder = DiagnosticRecorder(preferences(), "build-json-test")
        recorder.startSession("Voice Call json-session")
        recorder.recordInferencePrompt("prompt-alpha")
        recorder.recordTurnEvidence("turn-1", "pipeline", "evidence-beta")
        recorder.recordSummary("summary-gamma")
        recorder.recordImportant("important-delta")
        recorder.record("audio-epsilon")
        recorder.liveSection = { "Memory embeddings: model=test dim=256 vectors=1 pending=0 engine=available" }

        val json = org.json.JSONObject(recorder.snapshotJson().toString())
        assertEquals("build-json-test", json.getString("build"))
        assertTrue(json.getLong("generatedAtMs") > 0)
        assertTrue(json.getString("session").contains("json-session"))
        assertEquals("prompt-alpha", json.getJSONArray("inferencePrompts").getString(0).substringAfter("\n"))
        assertTrue(json.getJSONArray("turnEvidence").getString(0).contains("evidence-beta"))
        assertTrue(json.getJSONArray("summaries").getString(0).contains("summary-gamma"))
        assertTrue(json.getJSONArray("callActionsAndTurns").getString(0).contains("important-delta"))
        assertTrue(json.getJSONArray("recentAudioEvents").getString(0).contains("audio-epsilon"))
        assertTrue(json.getString("liveSection").contains("Memory embeddings:"))

        // The JSON export carries the same sections as the text export: every
        // recorded entry string appears in snapshot() as well.
        val text = recorder.snapshot()
        listOf("prompt-alpha", "evidence-beta", "summary-gamma", "important-delta", "audio-epsilon", "Memory embeddings:")
            .forEach { assertTrue("text export missing $it", text.contains(it)) }
    }

    @Test fun snapshotJsonWithoutLiveSectionIsNullNotMissing() {
        val recorder = DiagnosticRecorder(preferences(), "build-json-test")
        recorder.startSession("Voice Call plain")
        val json = recorder.snapshotJson()
        assertTrue(json.has("liveSection"))
        assertTrue(json.isNull("liveSection"))
    }

    @Test fun twoConversationsEachKeepTheirOwnLatestCall() {
        val recorder = DiagnosticRecorder(preferences(), "test")
        recorder.startSession("Voice Call call-a", "conv-a")
        recorder.recordImportant("call-a-marker")
        recorder.startSession("Voice Call call-b", "conv-b")
        recorder.recordImportant("call-b-marker")

        val a = recorder.snapshotForConversation("conv-a")
        assertNotNull(a)
        assertTrue(a!!.contains("Voice Call call-a"))
        assertTrue(a.contains("call-a-marker"))
        assertFalse(a.contains("call-b-marker"))

        // B's call is the live session, and copying from B returns it live.
        val b = recorder.snapshotForConversation("conv-b")
        assertNotNull(b)
        assertTrue(b!!.contains("Voice Call call-b"))
        assertTrue(b.contains("call-b-marker"))
        assertTrue(recorder.isLiveCallFor("conv-b"))
        assertFalse(recorder.isLiveCallFor("conv-a"))

        // A third call archives B as well; both conversations stay separate.
        recorder.startSession("Voice Call call-c", "conv-c")
        val bArchived = recorder.snapshotForConversation("conv-b")
        assertNotNull(bArchived)
        assertTrue(bArchived!!.contains("Voice Call call-b"))
        assertTrue(bArchived.contains("call-b-marker"))
        assertFalse(bArchived.contains("call-a-marker"))
        assertTrue(recorder.snapshotForConversation("conv-a")!!.contains("call-a-marker"))
    }

    @Test fun copyingFromConversationAReturnsItsCallAfterALaterCallInB() {
        val recorder = DiagnosticRecorder(preferences(), "test")
        recorder.startSession("Voice Call call-a", "conv-a")
        recorder.recordInferencePrompt("prompt-from-a")
        recorder.startSession("Voice Call call-b", "conv-b")
        recorder.recordInferencePrompt("prompt-from-b")

        val exportA = recorder.exportForConversation("conv-a")
        assertTrue(exportA.contains("Voice Call call-a"))
        assertTrue(exportA.contains("prompt-from-a"))
        assertFalse(exportA.contains("prompt-from-b"))
        assertFalse(exportA.contains("Voice Call call-b"))
    }

    @Test fun conversationWithNoCallGetsThePlainNoCallResultNeverAnotherConversationsCall() {
        val recorder = DiagnosticRecorder(preferences(), "test")
        recorder.startSession("Voice Call call-a", "conv-a")
        recorder.recordImportant("call-a-marker")
        recorder.startSession("Voice Call call-b", "conv-b")
        recorder.recordImportant("call-b-marker")

        assertNull(recorder.snapshotForConversation("conv-never"))
        val export = recorder.exportForConversation("conv-never")
        assertEquals("No call recorded for this conversation.", export)
        assertEquals(DiagnosticRecorder.NO_CALL_RECORDED_MESSAGE, export)
        assertFalse(export.contains("call-a-marker"))
        assertFalse(export.contains("call-b-marker"))
    }

    @Test fun nonCallSessionNeverStandsInForAConversationsCall() {
        val recorder = DiagnosticRecorder(preferences(), "test")
        recorder.startSession("Voice Call call-a", "conv-a")
        recorder.recordImportant("call-a-marker")
        // A later non-call session attributed to the same conversation (the
        // boot/wake session shape) must not shadow the retained call.
        recorder.startSession("Jarvis session — awaiting wake word", "conv-a")
        assertFalse(recorder.isLiveCallFor("conv-a"))
        val a = recorder.snapshotForConversation("conv-a")
        assertNotNull(a)
        assertTrue(a!!.contains("Voice Call call-a"))
        assertTrue(a.contains("call-a-marker"))
    }

    @Test fun archivedCallsAndLiveOwnershipSurviveProcessRestore() {
        val prefs = preferences()
        val recorder = DiagnosticRecorder(prefs, "test")
        recorder.startSession("Voice Call call-a", "conv-a")
        recorder.recordImportant("call-a-marker")
        recorder.startSession("Voice Call call-b", "conv-b")

        val restored = DiagnosticRecorder(prefs, "test")
        restored.restore()
        val a = restored.snapshotForConversation("conv-a")
        assertNotNull(a)
        assertTrue(a!!.contains("call-a-marker"))
        assertFalse(a.contains("Voice Call call-b"))
        // The restored live session still belongs to conv-b.
        assertTrue(restored.isLiveCallFor("conv-b"))
        assertTrue(restored.snapshotForConversation("conv-b")!!.contains("Voice Call call-b"))
    }

    @Test fun archiveRetentionIsBoundedAndLatestPerConversationWins() {
        val recorder = DiagnosticRecorder(preferences(), "test")
        for (i in 1..10) {
            recorder.startSession("Voice Call call-$i", "conv-$i")
            recorder.recordImportant("marker-$i")
        }
        recorder.startSession("Voice Call call-11", "conv-11")
        // Only the most recent archived conversations survive the cap.
        assertNull(recorder.snapshotForConversation("conv-1"))
        assertNotNull(recorder.snapshotForConversation("conv-10"))

        // A second call in the same conversation replaces the first as its latest.
        recorder.startSession("Voice Call call-ten-second", "conv-10")
        recorder.recordImportant("ten-second-marker")
        recorder.startSession("Voice Call call-12", "conv-12")
        val ten = recorder.snapshotForConversation("conv-10")
        assertNotNull(ten)
        assertTrue(ten!!.contains("ten-second-marker"))
        assertFalse(ten.contains("marker-10"))
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
