package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Compare a whole ASR clause with a contiguous playback span, including ASR substitutions.
 * Do not turn the unmatched edge of an otherwise echoed sentence into a new user word. */
internal object PlaybackEchoText {
    fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}']+")
        .findAll(TranscriptContent.speech(text).lowercase(Locale.ROOT))
        .map { it.value.trim('\'') }.filter { it.any(Char::isLetterOrDigit) }.toList()

    fun resemblesPlayback(text: String, reference: String): Boolean = resemblesPlayback(words(text), words(reference))

    fun resemblesPlayback(heard: List<String>, reference: List<String>): Boolean {
        if (heard.isEmpty() || reference.isEmpty()) return false
        // Single words remain eligible for the independent owner-versus-playback check.
        if (heard.size < 3) return false
        // Semi-global edit distance: free start/end in the playback reference, but every
        // heard word must be accounted for. Matching a bag of common words is insufficient.
        var previous = IntArray(reference.size + 1)
        for (i in heard.indices) {
            val current = IntArray(reference.size + 1)
            current[0] = i + 1
            for (j in reference.indices) current[j + 1] = minOf(
                previous[j] + if (heard[i] == reference[j]) 0 else 1,
                previous[j + 1] + 1, current[j] + 1)
            previous = current
        }
        return previous.minOrNull()!! <= heard.size / 3
    }
}
