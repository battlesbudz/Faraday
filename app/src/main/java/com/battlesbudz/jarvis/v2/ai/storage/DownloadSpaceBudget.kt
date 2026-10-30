package com.battlesbudz.jarvis.v2.ai.storage

/**
 * Storage budget for a model download. An installed model costs more than
 * its file size: the engine keeps a compiled-weights cache beside it,
 * counted here at half the model size again, so a download that merely fits
 * the file can still strand setup when the cache is written. The budget is
 * checked before the first byte moves, crediting the bytes this model
 * already occupies on the phone — a resumed download only pays for what is
 * missing, and an installed cache counts toward the cache half.
 */
internal object DownloadSpaceBudget {
    /** Total bytes an installed model of [modelBytes] is budgeted to occupy. */
    fun budgetBytes(modelBytes: Long): Long = (modelBytes * 3L + 1L) / 2L

    /**
     * Bytes that must still become available before a model of [modelBytes]
     * can be installed, given [existingBytes] already on disk for it and
     * [allocatableBytes] the system can hand out. Zero means it fits.
     */
    fun shortfallBytes(modelBytes: Long, existingBytes: Long, allocatableBytes: Long): Long {
        val remaining = (budgetBytes(modelBytes) - existingBytes).coerceAtLeast(0L)
        return (remaining - allocatableBytes).coerceAtLeast(0L)
    }
}
