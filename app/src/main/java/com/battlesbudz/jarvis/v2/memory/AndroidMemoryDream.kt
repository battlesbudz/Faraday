package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import androidx.work.ListenableWorker
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Process-wide access to the Unit 3 dream journal, plus the scheduled-pass
 * orchestration the WorkManager worker delegates to. The journal is
 * deliberately separate from both the canonical ledger and the vector
 * projection: dry-run nights may write here and nowhere else, and Copy
 * Diagnostics/settings read the same state the worker writes.
 */
object AndroidMemoryDream {
    private class Services(context: Context) {
        val app: Context = context.applicationContext
        val journal: MemoryDreamJournal = MemoryDreamJournal(
            FileDreamJournalStore(File(app.noBackupFilesDir, "memory-dream-journal.json"))
        )
    }

    @Volatile private var instance: Services? = null

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: Services(context).also { instance = it }
    }

    fun journal(context: Context): MemoryDreamJournal = services(context).journal

    fun journalState(context: Context): DreamJournalState = journal(context).state()

    /**
     * Test seam for the shared engine slot; production always uses
     * [RuntimeDreamEngineSlot]. Tests install a fake and restore null.
     */
    @Volatile internal var engineSlotOverride: DreamEngineSlot? = null

    private fun engineSlot(app: Context): DreamEngineSlot =
        engineSlotOverride ?: RuntimeDreamEngineSlot(app)

    /** Live, read-only line for the standard Copy Diagnostics export. */
    fun diagnosticsLine(context: Context): String = journal(context).diagnosticsLine()

    /**
     * One scheduled dream pass, end to end (called by [MemoryDreamWorker]):
     *
     * 1. Pick the judge model (OD-1: E4B when its file is installed, else the
     *    selected model). No model file at all → journal `skipped_no_model`,
     *    success: there is nothing to retry until a model exists.
     * 2. Shared-slot rule (Battles 2026-11-10): an engine resident in the
     *    app's one engine slot, or a voice session live / wake-armed →
     *    journal `skipped_contention`, retry. A resident engine is never
     *    swapped or shut down for the dream, whatever it is doing.
     * 3. Yield discipline (AutomaticMemory's D47 rule): a live conversation
     *    or a held model gate → journal `skipped_contention`, retry.
     * 4. Otherwise hold the model gate for the whole pass, load the judge
     *    model into the shared slot, run [MemoryDreamPass] with a
     *    [GemmaDreamJudge] over it, then unload the slot (leaving nothing
     *    resident) and release the gate, in all paths.
     *
     * Every exit path journals (Vessel's health discipline): a skipped night
     * reports itself as skipped-with-reason, never as silence.
     */
    suspend fun runScheduledPass(context: Context): ListenableWorker.Result {
        val app = context.applicationContext
        val journal = journal(app)
        val nightKey = LocalDate.now(ZoneId.systemDefault()).toString()
        val store = ModelStore(app)
        val e4b = ModelCatalog.gemma4E4b
        val spec = if (store.fileFor(e4b).isFile) e4b else store.selectedModel()
        if (!store.fileFor(spec).isFile) {
            recordSkippedRun(journal, nightKey, DreamRunOutcome.SKIPPED_NO_MODEL, "no_model_installed")
            return ListenableWorker.Result.success()
        }
        val slot = engineSlot(app)
        if (slot.engineLoaded()) {
            recordSkippedRun(journal, nightKey, DreamRunOutcome.SKIPPED_CONTENTION, "engine_resident")
            return ListenableWorker.Result.retry()
        }
        if (slot.sessionActive()) {
            recordSkippedRun(journal, nightKey, DreamRunOutcome.SKIPPED_CONTENTION, "voice_session_active")
            return ListenableWorker.Result.retry()
        }
        if (ConversationWork.activeJobs.get() != 0 || !store.tryBeginModelSelection(spec)) {
            recordSkippedRun(journal, nightKey, DreamRunOutcome.SKIPPED_CONTENTION, "model_busy")
            return ListenableWorker.Result.retry()
        }
        try {
            val engine = try {
                slot.loadJudgeEngine(spec, store)
            } catch (failure: Exception) {
                recordSkippedRun(
                    journal, nightKey, DreamRunOutcome.FAILED,
                    "model_load_failed:${failure.javaClass.simpleName}",
                )
                return ListenableWorker.Result.failure()
            }
            try {
                val memoryOs = AndroidMemoryOs.get(app)
                val fence = AutomaticMemoryFence(
                    FileFenceStore(File(app.noBackupFilesDir, "auto-memory-fence.tsv"))
                )
                val pass = MemoryDreamPass(
                    memoryOs = memoryOs,
                    fence = fence,
                    journal = journal,
                    vectors = dreamVectorProvider(app, memoryOs),
                    judge = GemmaDreamJudge(engine),
                )
                val entry = pass.run(nightKey, spec.id)
                return if (entry.outcome == DreamRunOutcome.FAILED) {
                    ListenableWorker.Result.failure()
                } else {
                    ListenableWorker.Result.success()
                }
            } finally {
                slot.unloadJudgeEngine()
            }
        } finally {
            store.endModelOperation()
        }
    }

    /**
     * Current-stamp vectors only: a projection row counts when its model,
     * dimension, and content fingerprint still match the canonical record —
     * the indexer's currency rule, applied at read time.
     */
    private fun dreamVectorProvider(context: Context, memoryOs: MemoryOs): () -> Map<String, FloatArray>? {
        val vectorStore = AndroidMemoryEmbeddings.vectorStore(context)
        val spec = EmbeddingModelCatalog.default
        return {
            val read = vectorStore.read(spec.id)
            val entries = read.entries
            if (entries == null) {
                null
            } else {
                val snapshot = memoryOs.read().snapshot
                if (snapshot == null) {
                    emptyMap()
                } else {
                    val byId = snapshot.memories.associateBy { it.id }
                    entries.filter { entry ->
                        val record = byId[entry.memoryId]
                        record != null && entry.dimension == spec.storedDimension &&
                            entry.contentFingerprint == AutomaticMemory.contentFingerprint(record.content)
                    }.associate { it.memoryId to it.vector }
                }
            }
        }
    }

    /** Journals a complete, zero-work run entry for a skip/failure exit path. */
    private fun recordSkippedRun(
        journal: MemoryDreamJournal,
        nightKey: String,
        outcome: DreamRunOutcome,
        note: String,
    ) {
        val state = journal.state()
        val now = System.currentTimeMillis()
        var runId = "dream-$nightKey-$now"
        var suffix = 2
        while (state.entries.any { it.runId == runId }) runId = "dream-$nightKey-$now-${suffix++}"
        val entry = DreamRunEntry(
            runId = runId,
            nightKey = nightKey,
            startedAtMs = now,
            finishedAtMs = now,
            outcome = outcome,
            dryRun = nightKey !in state.completedNightKeys &&
                state.completedNightKeys.size < MemoryDreamJournal.DRY_RUN_NIGHTS,
            watermarkBeforeMs = state.watermarkMs,
            watermarkAfterMs = state.watermarkMs,
            processedGroupKeys = emptyList(),
            groupsExamined = 0,
            outputs = emptyList(),
            judgeModelId = null,
            note = note,
        )
        journal.save(state.copy(entries = state.entries + entry))
    }
}
