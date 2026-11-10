package com.battlesbudz.jarvis.v2.memory

import kotlin.math.sqrt

/**
 * Pure vector primitives for the memory embedding projection. Stored vectors are
 * L2-normalized at write time so cosine ranking is a dot product, but [cosine] also
 * tolerates unnormalized inputs. Everything here is deterministic and JVM-testable.
 */
object MemoryVectorMath {
    /** Matryoshka truncation: keep the first [storedDimension] components, then re-normalize to unit length. */
    fun truncateAndNormalize(vector: FloatArray, storedDimension: Int): FloatArray {
        require(storedDimension in 1..vector.size) { "Stored dimension must be within the vector." }
        val out = FloatArray(storedDimension)
        var sumSquares = 0.0
        for (i in 0 until storedDimension) {
            val component = vector[i].toDouble()
            out[i] = vector[i]
            sumSquares += component * component
        }
        return scaleToUnit(out, sumSquares)
    }

    /** Returns a unit-length copy of [vector]. */
    fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty()) { "Cannot normalize an empty vector." }
        var sumSquares = 0.0
        for (component in vector) sumSquares += component.toDouble() * component
        return scaleToUnit(vector.copyOf(), sumSquares)
    }

    private fun scaleToUnit(vector: FloatArray, sumSquares: Double): FloatArray {
        val norm = sqrt(sumSquares)
        require(norm > 0.0) { "Cannot normalize a zero vector." }
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        return vector
    }

    /** Cosine similarity in -1..1. Zero vectors score 0 against everything. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector dimensions must match." }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0 else dot / denominator
    }
}

data class ScoredMemoryVector(val entry: MemoryVectorEntry, val score: Double)

/**
 * Brute-force exact cosine search. At personal-memory scale (hundreds to tens of
 * thousands of short vectors) a full scan is milliseconds and exact; an ANN index is
 * adopted only when measured scale requires it. Ordering is score descending with the
 * memory id as a deterministic tie-break, independent of store iteration order.
 */
object MemoryVectorSearch {
    fun search(query: FloatArray, entries: List<MemoryVectorEntry>, limit: Int): List<ScoredMemoryVector> {
        require(limit > 0) { "Search limit must be positive." }
        return entries.asSequence()
            .filter { it.vector.size == query.size }
            .map { ScoredMemoryVector(it, MemoryVectorMath.cosine(query, it.vector)) }
            .sortedWith(compareByDescending<ScoredMemoryVector> { it.score }.thenBy { it.entry.memoryId })
            .take(limit)
            .toList()
    }
}
