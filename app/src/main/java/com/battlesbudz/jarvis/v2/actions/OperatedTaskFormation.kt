package com.battlesbudz.jarvis.v2.actions

/** A narrowly parsed request that needs the operated-app path. */
data class OperatedTaskRequest(
    val kind: Kind,
    val appName: String,
    val recipient: String?,
    val body: String?,
    val sourceText: String,
) {
    enum class Kind { SEND_MESSAGE }
}

sealed interface OperatedTaskOutcome {
    val message: String
    data class Completed(override val message: String) : OperatedTaskOutcome
    data class Blocked(override val message: String) : OperatedTaskOutcome
    data class Declined(override val message: String) : OperatedTaskOutcome
    data class Failed(override val message: String) : OperatedTaskOutcome
}

/** Deterministic, deliberately small grammar; it does not infer from general chat. */
object OperatedTaskFormation {
    private val negation = Regex("(?i)\\b(?:don't|do not|never)\\b")
    private val quotedBody = Regex("(?s)(\"[^\"]*\"|'[^']*')\\s*[.!?]*$")
    private val patterns = listOf(
        Regex("(?i)^send\\s+(?:a\\s+)?(?:message|sms|text)\\s+to\\s+(.+?)\\s+(?:saying|that\\s+says|with\\s+the\\s+message|:)\\s*(.+)$"),
        Regex("(?i)^send\\s+(.+?)\\s+(?:a\\s+)?(?:message|sms|text)\\s+(?:saying|that\\s+says|with\\s+the\\s+message|:)\\s*(.+)$"),
        Regex("(?i)^(?:text|message)\\s+(.+?)\\s+(?:saying|that\\s+says|with\\s+the\\s+message|:)\\s*(.+)$"),
    )

    fun form(text: String): OperatedTaskRequest? {
        if (negation.containsMatchIn(text)) return null
        val source = text.trim()
        // normalizedRequest intentionally removes quoted examples. Protect only a terminal
        // quoted message body while applying its request-prefix normalization.
        val quote = quotedBody.find(source)
        val marker = "OPERATED_BODY_QUOTE"
        val normalized = if (quote != null) {
            val masked = source.replaceRange(quote.range, marker)
            ActionRequestText.normalizedRequest(masked).replace(marker, quote.value)
        } else ActionRequestText.normalizedRequest(source)
        val candidate = normalized.trim().trimEnd('.', '!', '?').trim()
        if (candidate.endsWith('?') || Regex("(?i)^(?:should\\s+i|should\\s+we|would\\s+it\\s+be\\s+okay\\s+if)\\b").containsMatchIn(candidate)) return null
        val match = patterns.firstNotNullOfOrNull { it.matchEntire(candidate) } ?: return null
        val recipient = match.groupValues[1].trim().trim('"', '\'', '“', '”')
        var body = match.groupValues[2].trim().trimEnd('.', '!', '?').trim()
        if (recipient.isBlank() || recipient.length > 120 || body.isBlank()) return null
        if (recipient.contains(Regex("(?i)\\b(?:please|should|can you|could you)\\b"))) return null
        if ((body.firstOrNull() == '"' && body.lastOrNull() == '"') ||
            (body.firstOrNull() == '\'' && body.lastOrNull() == '\'')) body = body.substring(1, body.length - 1)
        if (body.isBlank()) return null
        return OperatedTaskRequest(OperatedTaskRequest.Kind.SEND_MESSAGE, "Messages", recipient, body, text)
    }
}
