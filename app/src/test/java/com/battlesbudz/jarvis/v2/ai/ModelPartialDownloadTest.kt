package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The download controls' honest semantics at the store level: a pause keeps
 * the resumable state — the partial file and its chunk checkpoints — and a
 * cancel discards exactly that state through [ModelStore.discardPartialDownload],
 * never the installed model file or its compiled cache.
 */
@RunWith(RobolectricTestRunner::class)
class ModelPartialDownloadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spec: LocalModelSpec = ModelCatalog.gemma4E2b

    private fun freshStore(): ModelStore = ModelStore(context)

    private fun modelsDir(store: ModelStore): File = store.fileFor(spec).parentFile!!

    private fun partialFile(store: ModelStore): File =
        File(modelsDir(store), "${spec.fileName}.part")

    private fun chunksDir(store: ModelStore): File =
        File(modelsDir(store), "${spec.fileName}.part.chunks")

    private fun writePartialState(store: ModelStore, partialBytes: Int, chunkBytes: Int) {
        partialFile(store).writeBytes(ByteArray(partialBytes))
        val chunks = chunksDir(store).apply { mkdirs() }
        chunks.resolve("0.part").writeBytes(ByteArray(chunkBytes))
        chunks.resolve("source").writeText("identity")
    }

    @Test fun partialBytesCountsOnlyThePartialFileAndItsCheckpoints() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        val cache = store.compiledCacheDirectory(spec).apply { mkdirs() }
        cache.resolve("compiled.bin").writeBytes(ByteArray(40))
        writePartialState(store, partialBytes = 50, chunkBytes = 10)
        // 50 partial + 10 chunk + the 8-byte "identity" checkpoint file.
        assertEquals(68L, store.partialDownloadBytes(spec))
    }

    @Test fun partialBytesIsZeroWithoutResumableState() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        assertEquals(0L, store.partialDownloadBytes(spec))
    }

    @Test fun discardRemovesPartialStateAndKeepsTheModelAndItsCache() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        val cache = store.compiledCacheDirectory(spec).apply { mkdirs() }
        cache.resolve("compiled.bin").writeBytes(ByteArray(40))
        writePartialState(store, partialBytes = 50, chunkBytes = 10)
        assertTrue(store.tryBeginModelOperation())
        try {
            store.discardPartialDownload(spec)
        } finally {
            store.endModelOperation()
        }
        assertFalse(partialFile(store).exists())
        assertFalse(chunksDir(store).exists())
        assertEquals(0L, store.partialDownloadBytes(spec))
        assertTrue("the installed model file is not partial state", store.fileFor(spec).isFile)
        assertTrue("the compiled cache is not partial state", cache.resolve("compiled.bin").isFile)
    }

    @Test fun discardRequiresExclusiveOwnership() {
        val store = freshStore()
        writePartialState(store, partialBytes = 50, chunkBytes = 10)
        assertThrows(IllegalStateException::class.java) { store.discardPartialDownload(spec) }
        assertTrue("a refused discard removes nothing", partialFile(store).isFile)
        assertTrue(chunksDir(store).exists())
    }

    @Test fun discardWithNoPartialStateIsHarmless() {
        val store = freshStore()
        assertTrue(store.tryBeginModelOperation())
        try {
            store.discardPartialDownload(spec)
        } finally {
            store.endModelOperation()
        }
        assertEquals(0L, store.partialDownloadBytes(spec))
    }
}
