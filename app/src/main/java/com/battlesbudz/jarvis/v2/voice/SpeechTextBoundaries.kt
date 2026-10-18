package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Text-only boundaries shared by publication and Piper; never invents words or punctuation. */
internal object SpeechTextBoundaries {
    const val MIN_OPENING_WORDS = 6
    const val MIN_OPENING_CHARS = 32
    private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "st", "e.g", "i.e")
    private val initials = Regex("(?:[a-z]\\.)+[a-z]")
    private val dependentStarts = setOf("if", "unless", "although", "though", "because", "while", "when",
        "whenever", "until", "before", "after", "once", "since", "whether", "as", "despite", "without",
        "with", "for", "from", "at", "in", "on", "by", "to", "of", "instead", "rather", "having",
        "given", "assuming", "considering", "regarding", "during", "following", "using")
    private val unfinishedEnds = setOf("a", "an", "the", "and", "or", "but", "so", "because", "if", "then",
        "than", "as", "to", "of", "for", "from", "with", "without", "by", "in", "on", "at", "into", "that",
        "which", "who", "whose", "is", "are", "was", "were", "be", "been", "being", "am", "can", "could",
        "will", "would", "shall", "should", "may", "might", "must", "have", "has", "had", "do", "does", "did")
    private val ambiguousWords = setOf("not", "no", "never", "neither", "nor", "cannot", "that", "which", "who", "whose")
    private val controlMarkup = Regex("(?i)(tool_call|function_call|start_function|call:)")
    private val predicates = unfinishedEnds.intersect(setOf("is", "are", "was", "were", "am", "can", "could",
        "will", "would", "shall", "should", "may", "might", "must", "have", "has", "had", "do", "does", "did")) +
        setOf("start", "starts", "begins", "began", "need", "needs", "means", "makes", "made", "takes", "took",
            "helps", "keeps", "kept", "works", "shows", "showed", "says", "said", "looks", "seems", "gives",
            "gave", "uses", "supports", "contains", "includes", "provides", "allows", "returns", "remains",
            "runs", "ran", "waits", "stays", "holds", "held", "found", "saw", "went", "came", "rose", "stood",
            "measured", "walked", "moved", "arrived", "reached", "noticed")

    /** Conservative English opening heuristic, not a grammatical or semantic guarantee.
     * Ambiguous, dependent and negated clauses wait for the ordinary sentence boundary. */
    fun openingClauseEnd(text: CharSequence, limit: Int = text.length): Int? {
        for (i in 0 until minOf(text.length, limit)) {
            if (text[i] !in ",;:") continue
            // Require real lookahead, including across callbacks; never split numbers or words.
            if (i + 1 >= text.length || !text[i + 1].isWhitespace()) continue
            if (i > 0 && text[i - 1].isDigit()) continue
            val phrase = text.subSequence(0, i).toString().trim()
            if (phrase.length < MIN_OPENING_CHARS || phrase.any { it in "<>[]{}|`\"“”()" }) continue
            val tokens = words.findAll(phrase.lowercase(Locale.ROOT)).map { it.value }.toList()
            if (tokens.size < MIN_OPENING_WORDS || tokens.first() in dependentStarts ||
                tokens.last() in unfinishedEnds) continue
            // A relative clause, pending negation or tool notation is too ambiguous to speak early.
            if (tokens.any { it in ambiguousWords ||
                    it.endsWith("n't") || it.endsWith("n’t") } ||
                controlMarkup.containsMatchIn(phrase)) continue
            if (phrase.endsWith("for example", ignoreCase = true) || phrase.endsWith("such as", ignoreCase = true)) continue
            if (tokens.none { it in predicates }) continue
            return i + 1
        }
        return null
    }

    fun sentenceEnds(text: CharSequence, final: Boolean = false, limit: Int = text.length): List<Int> {
        val ends = mutableListOf<Int>()
        for (i in 0 until minOf(text.length, limit)) {
            if (text[i] !in ".!?\n") continue
            var end = i + 1
            while (end < text.length && text[end] in "\"'’”)!?") end++
            if (end > limit || end < text.length && !text[end].isWhitespace()) continue
            val word = text.subSequence(0, i).toString().takeLastWhile { !it.isWhitespace() }
                .trimStart('"', '\'', '‘', '“', '(').lowercase(Locale.ROOT)
            if (text[i] == '.') {
                if (word in abbreviations || word.length == 1 && word[0].isLetter() || initials.matches(word)) continue
                // A digit-period at a callback boundary may still become a decimal.
                if (!final && end == text.length && word.isNotEmpty() && word.all(Char::isDigit)) continue
            }
            ends += end
        }
        return ends
    }
}
