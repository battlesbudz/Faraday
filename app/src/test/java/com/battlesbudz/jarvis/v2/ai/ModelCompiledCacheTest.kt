package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The compiled engine cache moved out of the evictable cache directory into
 * files storage beside the model file (reliability epic M5, item 1). These
 * tests pin the new location and the accounting that knows about it:
 * [ModelStore.storedBytes] counts the new directory (and a pre-move legacy
 * cache while one still exists), and [ModelStore.deleteModel] removes both.
 */
@RunWith(RobolectricTestRunner::class)
class ModelCompiledCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spec: LocalModelSpec = ModelCatalog.gemma4E2b

    private fun freshStore(): ModelStore = ModelStore(context)

    @Test fun compiledCacheLivesBesideTheModelInFilesStorage() {
        val store = freshStore()
        val cache = store.compiledCacheDirectory(spec)
        assertEquals(store.fileFor(spec).parentFile, cache.parentFile)
        assertEquals(store.fileFor(spec).parentFile, store.engineCacheRoot())
        assertTrue(cache.path.startsWith(context.filesDir.path))
        assertFalse(cache.path.startsWith(context.cacheDir.path))
    }

    @Test fun storedBytesCountsTheCompiledCacheBesideTheModel() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        val cache = store.compiledCacheDirectory(spec).apply { mkdirs() }
        cache.resolve("compiled.bin").writeBytes(ByteArray(40))
        assertEquals(140L, store.storedBytes(spec))
    }

    @Test fun storedBytesStillCountsTheLegacyCacheWhileItExists() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        val legacy = File(context.cacheDir, spec.id).apply { mkdirs() }
        legacy.resolve("compiled.bin").writeBytes(ByteArray(30))
        assertEquals(130L, store.storedBytes(spec))
    }

    @Test fun deleteModelRemovesCompiledAndLegacyCaches() {
        val store = freshStore()
        store.fileFor(spec).writeBytes(ByteArray(100))
        val cache = store.compiledCacheDirectory(spec).apply { mkdirs() }
        cache.resolve("compiled.bin").writeBytes(ByteArray(40))
        val legacy = File(context.cacheDir, spec.id).apply { mkdirs() }
        legacy.resolve("compiled.bin").writeBytes(ByteArray(30))
        assertTrue(store.tryBeginModelOperation())
        try {
            store.deleteModel(spec)
        } finally {
            store.endModelOperation()
        }
        assertFalse(store.fileFor(spec).exists())
        assertFalse(cache.exists())
        assertFalse(legacy.exists())
        assertEquals(0L, store.storedBytes(spec))
    }
}
