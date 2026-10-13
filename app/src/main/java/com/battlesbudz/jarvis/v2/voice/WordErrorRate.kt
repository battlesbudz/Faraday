package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Word edit distance, not model confidence. Punctuation and case are ignored. */
object WordErrorRate {
    data class Score(val edits: Int, val referenceWords: Int) {
        val percent: Double get() = edits * 100.0 / referenceWords
    }

    fun score(reference: String, hypothesis: String): Score? {
        val expected = words(reference)
        if (expected.isEmpty()) return null
        val actual = words(hypothesis)
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { i, word ->
            val current = IntArray(actual.size + 1)
            current[0] = i + 1
            actual.forEachIndexed { j, heard ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (word == heard) 0 else 1)
            }
            previous = current
        }
        return Score(previous.last(), expected.size)
    }

    private fun words(text: String) = text.lowercase(Locale.ROOT)
        .replace("’", "'").replace("'", "")
        .split(Regex("[^\\p{L}\\p{N}]+")) .filter { it.isNotBlank() }
}
