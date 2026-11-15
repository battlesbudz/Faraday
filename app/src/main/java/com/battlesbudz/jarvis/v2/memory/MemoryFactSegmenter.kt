package com.battlesbudz.jarvis.v2.memory

/**
 * Splits one finalized user message into individual fact statements before
 * extraction/evaluation, so a message carrying several facts ("My name is X
 * and I have traveled to 27 countries and I like ice cream") becomes one
 * memory candidate per fact instead of a single blob record holding the raw
 * message.
 *
 * Deliberately conservative: a split happens only at a sentence boundary or
 * at an "and" whose right-hand side opens a new clause (a pronoun/name-class
 * subject). "I like fish and chips" stays one statement; "I like ice cream
 * and I live in Austin" becomes two. A message that does not split returns as
 * a single-element list holding the normalized message, so single-fact
 * behavior upstream stays byte-identical to the pre-segmentation pipeline.
 */
object MemoryFactSegmenter {
    private val CLAUSE_START = Regex(
        "^(?:i|i've|i have|my|he|she|we|they|it)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun segment(text: String): List<String> {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return emptyList()
        val sentences = normalized.split(Regex("(?<=[.!?])\\s+"))
        val segments = sentences.flatMap { sentence -> splitClauses(sentence) }
            .map { it.trim().trim(',', ' ') }
            .filter { it.isNotEmpty() }
        return segments.ifEmpty { listOf(normalized) }
    }

    private fun splitClauses(sentence: String): List<String> {
        // Semicolons separate statements outright.
        val parts = sentence.split(Regex(";\\s+"))
        val result = mutableListOf<String>()
        for (part in parts) {
            var start = 0
            val conjunction = Regex(",?\\s+and\\s+", RegexOption.IGNORE_CASE)
            for (match in conjunction.findAll(part)) {
                val rest = part.substring(match.range.last + 1)
                if (CLAUSE_START.containsMatchIn(rest)) {
                    result += part.substring(start, match.range.first)
                    start = match.range.last + 1
                }
            }
            result += part.substring(start)
        }
        return result
    }
}
