package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryVectorMathTest {
    private fun entry(id: String, vector: FloatArray) =
        MemoryVectorEntry(id, "f".repeat(64), "model-x", vector.size, vector, 1_000L)

    @Test fun truncationKeepsLeadingComponentsAndRenormalizesToUnitLength() {
        val raw = FloatArray(768)
        raw[0] = 3f; raw[1] = 4f; raw[300] = 100f // beyond the truncation point: must not influence the result
        val stored = MemoryVectorMath.truncateAndNormalize(raw, 256)
        assertEquals(256, stored.size)
        assertEquals(0.6f, stored[0], 1e-6f)
        assertEquals(0.8f, stored[1], 1e-6f)
        val norm = kotlin.math.sqrt(stored.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-5)
    }

    @Test fun truncationRejectsOutOfRangeDimensionsAndZeroVectors() {
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.truncateAndNormalize(FloatArray(4) { 1f }, 0) }
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.truncateAndNormalize(FloatArray(4) { 1f }, 5) }
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.truncateAndNormalize(FloatArray(768), 256) }
    }

    @Test fun normalizeReturnsAUnitCopyWithoutMutatingTheInput() {
        val input = floatArrayOf(3f, 4f)
        val out = MemoryVectorMath.normalize(input)
        assertEquals(0.6f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
        assertEquals(3f, input[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.normalize(FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.normalize(floatArrayOf(0f, 0f)) }
    }

    @Test fun cosineCoversIdenticalOrthogonalOppositeAndUnnormalizedInputs() {
        assertEquals(1.0, MemoryVectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 1e-9)
        assertEquals(0.0, MemoryVectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(-1.0, MemoryVectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-9)
        assertEquals(1.0, MemoryVectorMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(3f, 0f)), 1e-9)
        assertEquals(0.0, MemoryVectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)) }
    }

    @Test fun searchRanksByCosineWithDeterministicIdTieBreakAndLimit() {
        val query = floatArrayOf(1f, 0f, 0f, 0f)
        val entries = listOf(
            entry("m-b", floatArrayOf(1f, 0f, 0f, 0f)),
            entry("m-a", floatArrayOf(1f, 0f, 0f, 0f)),
            entry("m-c", floatArrayOf(0.6f, 0.8f, 0f, 0f)),
            entry("m-d", floatArrayOf(0f, 1f, 0f, 0f)),
            entry("m-wrong-dim", floatArrayOf(1f, 0f, 0f)),
        )
        val all = MemoryVectorSearch.search(query, entries, 10)
        assertEquals(listOf("m-a", "m-b", "m-c", "m-d"), all.map { it.entry.memoryId })
        assertEquals(1.0, all[0].score, 1e-9)
        assertEquals(0.6, all[2].score, 1e-6)
        assertEquals(0.0, all[3].score, 1e-9)
        assertEquals(listOf("m-a", "m-b"), MemoryVectorSearch.search(query, entries, 2).map { it.entry.memoryId })
        assertThrows(IllegalArgumentException::class.java) { MemoryVectorSearch.search(query, entries, 0) }
    }

    @Test fun entryRejectsWidthThatDisagreesWithItsStampedDimension() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryVectorEntry("m", "f".repeat(64), "model-x", 3, floatArrayOf(1f, 0f), 0L)
        }
    }
}
