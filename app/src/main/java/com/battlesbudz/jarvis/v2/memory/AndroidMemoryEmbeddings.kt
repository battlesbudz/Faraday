package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import com.battlesbudz.jarvis.v2.ai.storage.ModelDownloader
import java.io.File

/**
 * Process-wide access to the memory embedding projection: its vector store (a sibling
 * of the canonical ledger in noBackupFilesDir), its model acquisition (sharing the
 * chat models directory and the existing downloader), and the indexer that reconciles
 * the two. The engine is the LiteRT-LM binding: it reports unavailable until the
 * pinned embedding model is installed, and nothing here fabricates embeddings.
 */
object AndroidMemoryEmbeddings {
    private data class Services(
        val modelStore: EmbeddingModelStore,
        val vectorStore: SQLiteMemoryVectorStore,
        val indexer: MemoryEmbeddingIndexer,
    )

    @Volatile private var instance: Services? = null

    fun modelStore(context: Context): EmbeddingModelStore = services(context).modelStore
    fun vectorStore(context: Context): MemoryVectorStore = services(context).vectorStore
    fun indexer(context: Context): MemoryEmbeddingIndexer = services(context).indexer

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val transfer = EmbeddingTransfer { url, temporary, expectedSha256, onProgress ->
                ModelDownloader().download(
                    url = url,
                    temporary = temporary,
                    onProgress = onProgress,
                    onStatus = {},
                    expectedSha256 = expectedSha256,
                )
            }
            val modelStore = EmbeddingModelStore(File(app.filesDir, "models"), transfer)
            val vectorStore = SQLiteMemoryVectorStore(File(app.noBackupFilesDir, "memory-embeddings.db"))
            val service = MemoryEmbeddingService(LiteRtLmMemoryEmbeddingEngine(modelStore))
            Services(modelStore, vectorStore, MemoryEmbeddingIndexer(AndroidMemoryOs.get(app), vectorStore, service)).also { instance = it }
        }
    }
}
