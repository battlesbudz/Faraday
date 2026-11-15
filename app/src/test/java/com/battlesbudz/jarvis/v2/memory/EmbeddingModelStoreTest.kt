package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmbeddingModelStoreTest {
    private val payload = "embedding-bytes".toByteArray(Charsets.UTF_8) // 15 bytes
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val spec = EmbeddingModelSpec(
        id = "test-model",
        fileName = "test-model.bin",
        downloadUrl = "https://example.invalid/test-model.bin",
        expectedSha256 = sha256(payload),
        downloadBytes = payload.size.toLong(),
        outputDimension = 768,
        storedDimension = 256,
    )

    private class FakeTransfer(private val bytes: ByteArray) : EmbeddingTransfer {
        var calls = 0
        override suspend fun download(url: String, temporary: File, expectedSha256: String?, onProgress: (Long, Long) -> Unit) {
            calls++
            temporary.parentFile?.mkdirs()
            temporary.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("embedding-model").toFile()

    @Test fun downloadVerifiesPinInstallsAndThenReusesWithoutAnotherTransfer() = runBlocking {
        val dir = tempDir()
        val transfer = FakeTransfer(payload)
        val store = EmbeddingModelStore(dir, transfer)
        assertFalse(store.isInstalled(spec))

        val first = store.downloadOrReuse(spec)
        assertTrue(first.installed)
        assertFalse(first.reused)
        assertEquals(1, transfer.calls)
        assertTrue(store.isInstalled(spec))
        assertArrayEquals(payload, store.fileFor(spec).readBytes())
        assertEquals(payload.size.toLong(), store.storedBytes(spec))

        val second = store.downloadOrReuse(spec)
        assertTrue(second.installed)
        assertTrue(second.reused)
        assertEquals(1, transfer.calls)
    }

    @Test fun pinMismatchDeletesThePartialAndInstallsNothing() = runBlocking {
        val dir = tempDir()
        val store = EmbeddingModelStore(dir, FakeTransfer("forged-content".toByteArray(Charsets.UTF_8)))
        val result = store.downloadOrReuse(spec)
        assertFalse(result.installed)
        assertFalse(store.fileFor(spec).exists())
        assertFalse(File(dir, "${spec.fileName}.part").exists())
        assertFalse(store.isInstalled(spec))
    }

    @Test fun deleteRemovesInstalledAndPartialFiles() = runBlocking {
        val dir = tempDir()
        val store = EmbeddingModelStore(dir, FakeTransfer(payload))
        assertTrue(store.downloadOrReuse(spec).installed)
        File(dir, "${spec.fileName}.part").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(payload.size + 3L, store.storedBytes(spec))
        assertTrue(store.delete(spec))
        assertEquals(0L, store.storedBytes(spec))
    }
}
