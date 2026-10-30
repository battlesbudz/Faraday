package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SQLite projection store contract; needs a real SQLiteDatabase, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
class MemoryVectorStoreTest {
    private fun tempDb(): File = File(Files.createTempDirectory("memory-vectors").toFile(), "memory-embeddings.db")
    private fun entry(id: String, model: String = "model-x", marker: Float = 1f) =
        MemoryVectorEntry(id, "f".repeat(64), model, 4, floatArrayOf(marker, 0f, 0f, 0f), 1_000L)

    @Test fun putReadRoundTripAndReplace() {
        val store = SQLiteMemoryVectorStore(tempDb())
        assertTrue(store.put(entry("m1")))
        val read = store.read("model-x")
        assertNull(read.error)
        assertEquals(1, read.entries!!.size)
        assertEquals("m1", read.entries[0].memoryId)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), read.entries[0].vector, 0f)

        assertTrue(store.put(entry("m1", marker = 0.5f)))
        val replaced = store.read("model-x").entries!!
        assertEquals(1, replaced.size)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f, 0f), replaced[0].vector, 0f)
        store.close()
    }

    @Test fun readReturnsOnlyRowsStampedWithTheRequestedModel() {
        val store = SQLiteMemoryVectorStore(tempDb())
        assertTrue(store.put(entry("m1", model = "model-x")))
        assertTrue(store.put(entry("m2", model = "model-y")))
        assertEquals(listOf("m1"), store.read("model-x").entries!!.map { it.memoryId })
        assertEquals(listOf("m2"), store.read("model-y").entries!!.map { it.memoryId })
        assertTrue(store.read("model-z").entries!!.isEmpty())
        store.close()
    }

    @Test fun removeDeletesKnownIdsAndIgnoresUnknownOnes() {
        val store = SQLiteMemoryVectorStore(tempDb())
        assertTrue(store.put(entry("m1")))
        assertTrue(store.put(entry("m2")))
        assertTrue(store.remove(listOf("m1", "never-existed")))
        assertEquals(listOf("m2"), store.read("model-x").entries!!.map { it.memoryId })
        assertTrue(store.remove(emptyList()))
        store.close()
    }

    @Test fun rowsPersistAcrossReopenAndStoredBytesTrackTheFile() {
        val file = tempDb()
        val first = SQLiteMemoryVectorStore(file)
        assertEquals(0L, first.storedBytes())
        assertTrue(first.put(entry("m1")))
        assertTrue(first.storedBytes() > 0L)
        first.close()

        val reopened = SQLiteMemoryVectorStore(file)
        val read = reopened.read("model-x")
        assertNull(read.error)
        assertEquals(listOf("m1"), read.entries!!.map { it.memoryId })
        reopened.close()
    }
}
