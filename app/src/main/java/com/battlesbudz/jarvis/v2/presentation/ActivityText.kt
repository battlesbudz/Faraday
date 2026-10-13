package com.battlesbudz.jarvis.v2.presentation

/**
 * A short public progress sentence, never a prompt, answer-token stream or private reasoning.
 * Producers must supply deliberately public metadata. This is a defensive display bound, not a
 * general-purpose PII classifier: unknown tool arguments must never be passed to it.
 */
internal object ActivityText {
    const val MAX_LENGTH = 160

    fun publicBlurb(value: String, fallback: String = "Working on your request"): String {
        if (value.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return fallback
        val text = value.trim().replace(Regex(" +"), " ")
        if (text.isEmpty() || text.length > MAX_LENGTH || sensitive.containsMatchIn(text)) return fallback
        return text
    }

    /** The only supported text argument is an app display name, never an arbitrary tool payload. */
    fun appName(value: String?): String? = value?.trim()?.takeIf {
        it.length in 1..40 && it.any(Char::isLetter) &&
            it.all { char -> char.isLetterOrDigit() || char in " .'-&+()" } &&
            !sensitive.containsMatchIn(it)
    }

    private val sensitive = Regex(
        "(?i)(?:https?://|www\\.|@|bearer\\s|(?:api[_ -]?key|password|secret|token)\\s*[:=]|\\bsk-[a-z0-9]|\\b[0-9]{7,}\\b|[a-z0-9_-]{40,})")
}
