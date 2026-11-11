package com.battlesbudz.jarvis.v2.memory

import com.battlesbudz.jarvis.v2.ai.storage.sha256
import java.io.File

/**
 * Narrow transfer port: the only network implementation in the app is the existing
 * model downloader, injected at the Android edge. Keeping it a port keeps this store
 * JVM-testable and keeps a second HTTP stack out of the memory package.
 */
fun interface EmbeddingTransfer {
    suspend fun download(url: String, temporary: File, expectedSha256: String?, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit)
}

data class EmbeddingAcquisition(val installed: Boolean, val reused: Boolean, val message: String)

/**
 * Acquisition for embedding artifacts, following the chat ModelStore conventions:
 * the model lives in filesDir/models, a verified existing file is reused, transfers
 * land in a `.part` sibling (so the downloader can resume), the SHA-256 pin is
 * verified before an atomic rename installs the file, and failure messages never
 * carry payload or URL text.
 */
class EmbeddingModelStore(
    private val modelDirectory: File,
    private val transfer: EmbeddingTransfer,
) {
    fun fileFor(spec: EmbeddingModelSpec): File = File(modelDirectory, spec.fileName)

    /** True only for a complete file whose bytes match the catalog pin. */
    fun isInstalled(spec: EmbeddingModelSpec): Boolean {
        val file = fileFor(spec)
        return file.isFile && file.length() == spec.downloadBytes && file.sha256() == spec.expectedSha256
    }

    /** Bytes held by the installed file plus any resumable partial download. */
    fun storedBytes(spec: EmbeddingModelSpec): Long =
        listOf(fileFor(spec), partialFor(spec)).sumOf { if (it.isFile) it.length() else 0L }

    fun delete(spec: EmbeddingModelSpec): Boolean {
        partialFor(spec).delete()
        fileFor(spec).delete()
        return !fileFor(spec).exists()
    }

    suspend fun downloadOrReuse(
        spec: EmbeddingModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): EmbeddingAcquisition {
        if (isInstalled(spec)) return EmbeddingAcquisition(true, true, "Embedding model already installed.")
        if (!modelDirectory.isDirectory && !modelDirectory.mkdirs()) {
            return EmbeddingAcquisition(false, false, "Model directory is unavailable.")
        }
        if (modelDirectory.usableSpace < spec.downloadBytes + SPACE_MARGIN_BYTES) {
            return EmbeddingAcquisition(false, false, "Not enough free space for the embedding model download.")
        }
        val partial = partialFor(spec)
        try {
            transfer.download(spec.downloadUrl, partial, spec.expectedSha256, onProgress)
        } catch (e: Exception) {
            return EmbeddingAcquisition(false, false, "Embedding model download failed (${e.javaClass.simpleName}).")
        }
        if (!partial.isFile || partial.length() != spec.downloadBytes || partial.sha256() != spec.expectedSha256) {
            partial.delete()
            return EmbeddingAcquisition(false, false, "Embedding model download did not match its integrity pin.")
        }
        val target = fileFor(spec)
        if (!partial.renameTo(target)) {
            return EmbeddingAcquisition(false, false, "Embedding model could not be installed.")
        }
        return EmbeddingAcquisition(true, false, "Embedding model installed.")
    }

    private fun partialFor(spec: EmbeddingModelSpec): File = File(modelDirectory, "${spec.fileName}.part")

    private companion object { const val SPACE_MARGIN_BYTES = 16L * 1024L * 1024L }
}
