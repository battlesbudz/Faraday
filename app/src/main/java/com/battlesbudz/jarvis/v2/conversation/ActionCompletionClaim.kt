package com.battlesbudz.jarvis.v2.conversation

/** Detects first-person completion announcements that caused build 1972 to overstate actions. */
internal object ActionCompletionClaim {
    const val CORRECTION = "\nThe previous output claimed a phone action was performed, but no action ran in this turn. Answer the user's current message directly and honestly: do not claim any message was sent, posted, opened, or changed unless it actually happened in this turn. If the user asked for an action you could not perform, say what you need from them instead."
    const val FALLBACK = "I didn't perform any phone action in this turn. If you asked me to do something, I couldn't carry it out — please try again."

    private val patterns = listOf(
        Regex("(?:^|[.!?]\\s+)(sending|texting|messaging|posting|opening)\\s", RegexOption.IGNORE_CASE),
        Regex("\\bi(?:'m| am) (sending|texting|messaging|posting|opening)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi will (send|text|message|post|open|call)\\b[^.!?]*\\bnow\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi(?:'ll) (send|text|message|post|open)\\b[^.!?]*\\bnow\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi (?:have |'ve )?(sent|texted|messaged|posted|opened)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(message|text) (?:has been |was )?sent\\b", RegexOption.IGNORE_CASE),
        Regex("\\breminder (?:is|has been) set\\b", RegexOption.IGNORE_CASE),
    )

    fun isClaim(displayText: String): Boolean = patterns.any { it.containsMatchIn(displayText) }
}
