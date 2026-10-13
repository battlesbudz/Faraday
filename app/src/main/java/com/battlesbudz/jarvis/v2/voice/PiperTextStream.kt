package com.battlesbudz.jarvis.v2.voice

/** One checked opening clause, then longer linguistic context before each bounded VITS call. */
internal class PiperTextStream(private val waitForEnd: Boolean = false, openingTargetChars: Int = TARGET_CHARS,
                               @Suppress("UNUSED_PARAMETER") nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(openingTargetChars in 1..TARGET_CHARS) }
    private val buffer = StringBuilder()
    private var opening = true
    fun append(text: String) { buffer.append(text) }

    /** Opening boundaries release immediately; elapsed time can never complete an unfinished clause. */
    fun openingWaitMs(): Long? = null

    fun take(final: Boolean = false): String? {
        if (buffer.isBlank()) { if (final) buffer.clear(); return null }
        if (waitForEnd && !final) return null
        if (final && buffer.length <= MAX_CHARS) return release(buffer.length)
        val sentenceEnds = SpeechTextBoundaries.sentenceEnds(buffer, final, MAX_CHARS)
        if (!waitForEnd && opening) {
            val clause = SpeechTextBoundaries.openingClauseEnd(buffer, MAX_CHARS)
            val first = listOfNotNull(clause, sentenceEnds.firstOrNull()).minOrNull()
            if (first != null) return release(first)
        }
        var lastSentence = -1
        for (end in sentenceEnds) {
            lastSentence = end
            if (!waitForEnd && end >= TARGET_CHARS) return release(end)
        }
        if (buffer.length < MAX_CHARS) return null
        // Preserve the existing emergency bound for run-ons and oversized unbroken tokens.
        val boundary = if (lastSentence > 0) lastSentence else
            (MAX_CHARS - 1 downTo MAX_CHARS / 2).firstOrNull { buffer[it].isWhitespace() } ?: MAX_CHARS
        return release(boundary)
    }

    private fun release(end: Int): String {
        opening = false
        val text = buffer.substring(0, end).trim()
        buffer.delete(0, end)
        while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        return text
    }

    companion object {
        const val TARGET_CHARS = 320
        const val MAX_CHARS = 640
    }
}
