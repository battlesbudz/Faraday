package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import com.battlesbudz.jarvis.v2.ai.storage.ModelDownloader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide access to the memory embedding projection: its vector store (a sibling
 * of the canonical ledger in noBackupFilesDir), its model acquisition (sharing the
 * chat models directory and the existing downloader), and the indexer that reconciles
 * the two. The engine is the LiteRT-LM binding: it reports unavailable until the
 * pinned embedding model is installed, and nothing here fabricates embeddings.
 * Construction stays lazy and cheap: no download, no native load, no embedding.
 */
object AndroidMemoryEmbeddings {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The host's conversation-idleness rule, installed by the runtime at start. */
    @Volatile private var syncIdleProbe: () -> Boolean = { true }

    private class Services(context: Context) {
        val app: Context = context.applicationContext
        val transfer: EmbeddingTransfer = EmbeddingTransfer { url, temporary, expectedSha256, onProgress ->
            ModelDownloader().download(
                url = url,
                temporary = temporary,
                onProgress = onProgress,
                onStatus = {},
                expectedSha256 = expectedSha256,
            )
        }
        val modelStore: EmbeddingModelStore = EmbeddingModelStore(File(app.filesDir, "models"), transfer)
        val vectorStore: SQLiteMemoryVectorStore =
            SQLiteMemoryVectorStore(File(app.noBackupFilesDir, "memory-embeddings.db"))
        val service: MemoryEmbeddingService = MemoryEmbeddingService(LiteRtLmMemoryEmbeddingEngine(modelStore))
        val indexer: MemoryEmbeddingIndexer = MemoryEmbeddingIndexer(AndroidMemoryOs.get(app), vectorStore, service)
        val hybridRetrieval: MemoryHybridRetrieval = MemoryHybridRetrieval(service, vectorStore)
        val syncScheduler: MemoryEmbeddingSyncScheduler = MemoryEmbeddingSyncScheduler(
            scope = appScope,
            idleProbe = { syncIdleProbe() },
            sync = { indexer.sync() },
        )
    }

    @Volatile private var instance: Services? = null

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: Services(context).also { instance = it }
    }

    fun modelStore(context: Context): EmbeddingModelStore = services(context).modelStore
    fun vectorStore(context: Context): MemoryVectorStore = services(context).vectorStore
    fun indexer(context: Context): MemoryEmbeddingIndexer = services(context).indexer
    fun hybridRetrieval(context: Context): MemoryHybridRetrieval = services(context).hybridRetrieval

    /** Memory settings install surface: acquisition plus an immediate post-install sync. */
    fun setupController(context: Context): MemoryEmbeddingSetupController {
        val bound = services(context)
        return MemoryEmbeddingSetupController(bound.modelStore) { bound.syncScheduler.syncNow() }
    }

    /** Install the conversation-idleness rule background projection syncs yield to. */
    fun installSyncIdleProbe(probe: () -> Boolean) {
        syncIdleProbe = probe
    }

    /**
     * Queue a background projection sync (app start, approved-state changes).
     * Coalesced and idle-gated by the scheduler; never runs on the caller's path.
     */
    fun requestSync(context: Context) {
        services(context).syncScheduler.request()
    }

    /** Immediate sync for user-initiated flows (the Settings install action). */
    suspend fun syncNow(context: Context) {
        services(context).syncScheduler.syncNow()
    }

    /** Live, read-only diagnostics line for the standard Copy Diagnostics export. */
    fun diagnosticsLine(context: Context): String = services(context).indexer.diagnosticsLine()
}
