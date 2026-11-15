package com.battlesbudz.jarvis.v2.memory

/**
 * UI state for the Memory settings embeddings row. The store's own words are the
 * failure copy — these states never paraphrase a failure into something vaguer.
 */
sealed interface MemoryEmbeddingSetupState {
    /** No verified model file. [savedBytes] is resumable partial-download progress. */
    data class NotInstalled(val savedBytes: Long, val downloadBytes: Long) : MemoryEmbeddingSetupState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : MemoryEmbeddingSetupState

    data class Installed(val sizeBytes: Long) : MemoryEmbeddingSetupState

    /** [message] is the acquisition layer's human-readable failure, verbatim. */
    data class Failed(val message: String, val savedBytes: Long) : MemoryEmbeddingSetupState
}

/**
 * The Memory settings install action, kept out of the composable so it is unit
 * testable: state reads reflect the store's verified view (a file counts as
 * installed only when its bytes match the catalog pin), install runs the store's
 * reuse/resume/pin/atomic-rename acquisition with progress surfaced, and a
 * completed install fires [onInstalled] — the production binding runs an
 * immediate projection sync, so a fresh install starts answering semantic
 * queries without waiting for the next lifecycle trigger.
 */
class MemoryEmbeddingSetupController(
    private val store: EmbeddingModelStore,
    val spec: EmbeddingModelSpec = EmbeddingModelCatalog.default,
    private val onInstalled: suspend () -> Unit = {},
) {
    fun state(): MemoryEmbeddingSetupState =
        if (store.isInstalled(spec)) MemoryEmbeddingSetupState.Installed(store.storedBytes(spec))
        else MemoryEmbeddingSetupState.NotInstalled(store.storedBytes(spec), spec.downloadBytes)

    suspend fun install(onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): MemoryEmbeddingSetupState {
        val result = store.downloadOrReuse(spec, onProgress)
        if (!result.installed) return MemoryEmbeddingSetupState.Failed(result.message, store.storedBytes(spec))
        onInstalled()
        return MemoryEmbeddingSetupState.Installed(store.storedBytes(spec))
    }
}
