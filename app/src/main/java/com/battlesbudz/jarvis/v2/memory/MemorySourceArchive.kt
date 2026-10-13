package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest

/** Personal source text has an explicit history-only read boundary in this checkpoint. */
interface MemorySourceArchive {
    fun captureSource(input: FinalMemoryInput): SourceArchiveCapture
    fun searchExplicitHistory(query: String, limit: Int = 20): SourceArchiveSearch
    fun purgeExpiredSources(): Boolean
}

enum class SourceArchiveOutcome { STORED, ALREADY_RECORDED, IGNORED, EXCLUDED, EXPIRED, INVALID, CONFLICT, LOCKED, FULL, STORAGE_FAILURE }
data class SourceArchiveCapture(val outcome: SourceArchiveOutcome)
data class SourceEpisode(
    val eventKey: String,
    val conversationKey: String,
    val callKey: String?,
    val source: ConversationMemorySource,
    val capturedAtMs: Long,
    val expiresAtMs: Long,
    val text: String,
    val fingerprint: String,
)
data class SourceArchiveSearch(val outcome: SourceArchiveOutcome?, val episodes: List<SourceEpisode> = emptyList())
data class SourceArchivePreparation(val episode: SourceEpisode? = null, val rejection: SourceArchiveOutcome? = null)

/** No partial redaction: a detected secret excludes the whole event before any database write. */
object MemoryArchivePolicy {
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1_000
    const val MAX_TEXT_CHARS = 32_768
    const val MAX_QUERY_CHARS = 200
    const val MAX_EVENTS = 20_000
    const val MAX_TEXT_BYTES = 16L * 1_024 * 1_024

    private val secrets = listOf(
        Regex("\\b(?:password|passwd|pwd|passphrase|passcode|pin|otp|api[ _-]?key|access[ _-]?token|auth(?:entication)?[ _-]?token|secret)\\b\\s*(?:(?:is|equals)\\s+|[:=]\\s*)?[\\p{L}\\p{N}\"'_/+=.-]+", RegexOption.IGNORE_CASE),
        Regex("\\b(?:(?:access|security|verification|authentication|door|unlock|recovery|backup|two.factor|2fa)\\s+)?code\\b\\s*(?:(?:is|equals)\\s+|[:=]\\s*)?[\\d][\\d\\s-]{2,}", RegexOption.IGNORE_CASE),
        Regex("\\b(?:(?:credit|debit)\\s+card|card|cvv|cvc|ssn|social security)\\b[\\s\\S]{0,40}\\d{3,}", RegexOption.IGNORE_CASE),
        Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"),
        Regex("^\\s*\\d(?:[ -]?\\d){2,9}\\s*$"),
    )

    fun containsSecret(text: String): Boolean = secrets.any { it.containsMatchIn(text) }

    fun prepare(input: FinalMemoryInput, nowMs: Long): SourceArchivePreparation {
        if (!input.complete || (input.source == ConversationMemorySource.VOICE && !input.recognitionSucceeded)) return SourceArchivePreparation(rejection = SourceArchiveOutcome.IGNORED)
        if (input.eventId.isBlank() || input.conversationId.isBlank() || input.eventId.length > 512 || input.conversationId.length > 512 || (input.callId?.length ?: 0) > 512 || input.text.isBlank() || input.text.length > MAX_TEXT_CHARS || input.capturedAtMs <= 0 || nowMs <= 0 || input.capturedAtMs > nowMs) return SourceArchivePreparation(rejection = SourceArchiveOutcome.INVALID)
        // Scan the complete bounded input, including secrets beyond the old 2,000-character fact limit.
        if (containsSecret(input.text)) return SourceArchivePreparation(rejection = SourceArchiveOutcome.EXCLUDED)
        val expiry = try { Math.addExact(input.capturedAtMs, RETENTION_MS) } catch (_: ArithmeticException) { return SourceArchivePreparation(rejection = SourceArchiveOutcome.INVALID) }
        if (expiry <= nowMs) return SourceArchivePreparation(rejection = SourceArchiveOutcome.EXPIRED)
        val eventKey = MemoryPolicy.sourceKey(input.eventId)
        val conversationKey = MemoryPolicy.sourceKey(input.conversationId)
        val callKey = input.callId?.trim()?.takeIf { it.isNotEmpty() }?.let(MemoryPolicy::sourceKey)
        val text = input.text.trim()
        val fields = listOf(eventKey, conversationKey, callKey.orEmpty(), input.source.name, input.capturedAtMs.toString(), text)
        val encoded = fields.joinToString("") { "${it.length}:$it" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return SourceArchivePreparation(SourceEpisode(eventKey, conversationKey, callKey, input.source, input.capturedAtMs, expiry, text, fingerprint))
    }
}
