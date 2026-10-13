package com.battlesbudz.jarvis.v2.voice

/** Committed ASR segments plus a revisable current segment. Never truncates into a valid command. */
class UtteranceAccumulator(private val maxChars: Int = 12_000) {
    private var committed = ""
    private var overlapping = false
    var issue: String? = null
        private set
    var segments = 0
        private set

    fun partial(text: String): String = combine(text, final = false)
    fun commit(text: String, nextOverlaps: Boolean) {
        if (TranscriptContent.speech(text).isBlank()) issue = "unrecognized_segment"
        committed = combine(text, final = true)
        overlapping = nextOverlaps
        segments++
    }
    fun finish(text: String, speechExpected: Boolean = false): String {
        if (speechExpected && TranscriptContent.speech(text).isBlank()) issue = "unrecognized_segment"
        return combine(text, final = true)
    }
    private fun combine(text: String, final: Boolean): String {
        var suffix = TranscriptContent.speech(text).trim()
        if (overlapping && committed.isNotBlank()) {
            val old = words(committed).takeLast(16)
            val matches = Regex("[\\p{L}\\p{N}']+").findAll(suffix).toList()
            val next = matches.map { it.value.lowercase(java.util.Locale.ROOT) }
            val count = (minOf(old.size, next.size) downTo 2).firstOrNull {
                old.takeLast(it) == next.take(it)
            }
            if (count != null) suffix = suffix.substring(matches[count - 1].range.last + 1).trimStart(' ', ',', '.', '!', '?', ';', ':')
            else {
                // Native hypotheses disagree at a forced acoustic boundary. Do not guess a
                // seam and then execute a command with potentially missing/duplicated words.
                if (final) issue = "segment_boundary_uncertain"
                return committed
            }
        }
        val joined = listOf(committed, suffix).filter { it.isNotBlank() }.joinToString(" ")
        if (joined.length > maxChars) { issue = "transcript_capacity"; return committed }
        return joined
    }
    private fun words(text: String) = Regex("[\\p{L}\\p{N}']+").findAll(text)
        .map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
}
