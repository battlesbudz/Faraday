package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

object VoiceCallPolicy {
    const val TURN_SILENCE_MS = 3_000L
    const val CALL_INACTIVITY_MS = 20_000L
    const val ENDED_PREFIX = "Voice Call ended:"

    /** A direct final farewell, optionally preceded by a short polite acknowledgement. */
    fun isGoodbye(transcript: String): Boolean {
        if (transcript.any { it in "\"“”" }) return false
        // Apostrophes inside words preserve "that's all". Quote marks around a
        // farewell remain reported text even when punctuation normalization strips them.
        if (transcript.indices.any { index ->
            transcript[index] in "'‘’" && (index == 0 || index == transcript.lastIndex ||
                !transcript[index - 1].isLetterOrDigit() || !transcript[index + 1].isLetterOrDigit())
        }) return false
        val words = transcript.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")
        val farewell = "(?:goodbye(?: jarvis)?|jarvis goodbye|stop listening(?: jarvis)?|jarvis stop listening)"
        val polite = "(?:(?:[0-9]+|uh|um|okay|ok|yeah|yes|alright|well|no thank you|no thanks|thank you|thanks|please|sir|that s all|that is all) )*"
        val tail = "(?: (?:[0-9]+|yeah|yes|okay|ok|please|thanks|thank you|sir))*"
        return Regex("^$polite$farewell$tail$").matches(words)
    }
}
