package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest

object MemoryPolicy {
    private val generatedIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val eventKeyPattern = Regex("[0-9a-f]{32}")
    private val fingerprintPattern = Regex("[0-9a-f]{64}")
    private val metadataSeparatorPattern = Regex("[^a-z0-9]+")
    private val restrictedMetadataTokens = listOf("bank", "banking", "financial", "transaction", "credit_card", "debit_card", "payroll", "brokerage", "restricted_source", "restricted_summary")
    const val MAX_CONTENT_CHARS = 2_000
    const val MAX_EVENT_ID_CHARS = 128
    const val MAX_EVENT_SOURCE_CHARS = 128
    const val MAX_PROVENANCE = 8
    const val MAX_PROVENANCE_KIND_CHARS = 96
    const val MAX_PROVENANCE_ID_CHARS = 160
    const val MAX_PROVENANCE_LABEL_CHARS = 240
    const val MAX_MEMORIES = 500
    const val MAX_TOMBSTONES = 2_000

    data class Decision(val outcome: MemoryOutcome?, val message: String) { val allowed get() = outcome == null }

    /** Applies the one persisted metadata representation at ingress and before fingerprinting. */
    fun canonicalize(proposal: MemoryProposal): MemoryProposal = proposal.copy(
        content = proposal.content.trim(),
        source = proposal.source.copy(
            eventId = proposal.source.eventId.trim(),
            eventSource = proposal.source.eventSource.trim(),
            provenance = proposal.source.provenance.map { p -> p.copy(
                kind = p.kind.trim(), id = p.id.trim(), label = p.label?.trim()?.takeIf { it.isNotEmpty() }
            ) }
        ),
        correctsMemoryId = proposal.correctsMemoryId?.trim()?.takeIf { it.isNotEmpty() },
        wikiAssignment = proposal.wikiAssignment?.let { a -> MemoryWikiAssignment(a.category, MemoryWiki.canonicalTopic(a.topic)) }
    )

    fun assess(proposal: MemoryProposal, nowMs: Long): Decision {
        val canonical = canonicalize(proposal)
        val content = canonical.content
        if (content.isEmpty() || content.length > MAX_CONTENT_CHARS) return Decision(MemoryOutcome.INVALID, "Memory content must be 1-$MAX_CONTENT_CHARS characters.")
        if (canonical.source.eventId.isEmpty() || canonical.source.eventId.length > MAX_EVENT_ID_CHARS ||
            canonical.source.eventSource.isEmpty() || canonical.source.eventSource.length > MAX_EVENT_SOURCE_CHARS) return Decision(MemoryOutcome.INVALID, "A bounded source event is required.")
        assessWikiAssignment(canonical.wikiAssignment)?.let { return Decision(MemoryOutcome.INVALID, it) }
        if (canonical.wikiAssignment?.topic?.let { isRestrictedMetadata(it) || containsRawRestrictedContent(it) } == true) return Decision(MemoryOutcome.EXCLUDED, "Restricted wiki metadata is not stored in memory.")
        if (canonical.confidence !in 0..100) return Decision(MemoryOutcome.INVALID, "Confidence must be 0-100.")
        if (canonical.source.createdAtMs <= 0 || canonical.source.createdAtMs > nowMs + 86_400_000L) return Decision(MemoryOutcome.INVALID, "Source timestamp is invalid.")
        if (canonical.expiresAtMs != null && canonical.expiresAtMs <= nowMs) return Decision(MemoryOutcome.INVALID, "Expiry must be in the future.")
        if (canonical.source.provenance.size > MAX_PROVENANCE) return Decision(MemoryOutcome.INVALID, "Too many provenance entries.")
        if (canonical.source.provenance.any { it.kind.isEmpty() || it.id.isEmpty() || it.kind.length > MAX_PROVENANCE_KIND_CHARS || it.id.length > MAX_PROVENANCE_ID_CHARS || (it.label?.length ?: 0) > MAX_PROVENANCE_LABEL_CHARS }) return Decision(MemoryOutcome.INVALID, "Provenance fields are invalid.")
        val metadata = listOf(canonical.source.eventId, canonical.source.eventSource) + canonical.source.provenance.flatMap { listOf(it.kind, it.id, it.label.orEmpty()) }
        if (canonical.source.provenance.any { it.restricted } || metadata.any(::isRestrictedMetadata) || metadata.any(::containsRawRestrictedContent) || canonical.source.sensitivity == MemorySensitivity.RESTRICTED || containsRawRestrictedContent(content)) return Decision(MemoryOutcome.EXCLUDED, "Raw financial, identity, or restricted provenance data is not stored in memory.")
        return Decision(null, "ok")
    }

    /** Structural validation deliberately does not judge expiry against the current clock: old records remain erasable. */
    fun validatePersisted(record: MemoryRecord): Boolean {
        val proposal = MemoryProposal(record.content, record.source, record.category, record.tier, record.type, record.confidence, null, record.correctsMemoryId, null, record.wikiAssignment)
        val canonical = canonicalize(proposal)
        if (canonical.content != record.content || canonical.source != record.source || canonical.correctsMemoryId != record.correctsMemoryId || canonical.wikiAssignment != record.wikiAssignment) return false
        if (assessWikiAssignment(record.wikiAssignment) != null || record.wikiAssignment?.topic?.let { isRestrictedMetadata(it) || containsRawRestrictedContent(it) } == true) return false
        if (record.content.isEmpty() || record.content.length > MAX_CONTENT_CHARS || record.confidence !in 0..100 || record.source.createdAtMs <= 0 || record.createdAtMs <= 0 || record.updatedAtMs < record.createdAtMs || record.revision < 1 || (record.expiresAtMs != null && record.expiresAtMs <= 0)) return false
        if (!isOpaqueEventKey(record.source.eventId) || record.source.eventSource.isEmpty() || record.source.eventSource.length > MAX_EVENT_SOURCE_CHARS) return false
        if (record.source.provenance.size > MAX_PROVENANCE || record.source.sensitivity == MemorySensitivity.RESTRICTED) return false
        val metadata = listOf(record.source.eventSource) + record.source.provenance.flatMap { listOf(it.kind, it.id, it.label.orEmpty()) }
        return record.source.provenance.all { it.kind.isNotEmpty() && it.id.isNotEmpty() && it.kind.length <= MAX_PROVENANCE_KIND_CHARS && it.id.length <= MAX_PROVENANCE_ID_CHARS && (it.label?.length ?: 0) <= MAX_PROVENANCE_LABEL_CHARS && !it.restricted } &&
            metadata.none(::isRestrictedMetadata) && metadata.none(::containsRawRestrictedContent) && !containsRawRestrictedContent(record.content)
    }

    fun assessWikiAssignment(assignment: MemoryWikiAssignment?): String? {
        if (assignment == null) return null
        return if (assignment.topic.isBlank() || assignment.topic.length > 120) "Wiki topic must be 1-120 characters." else null
    }

    fun isGeneratedMemoryId(value: String): Boolean = generatedIdPattern.matches(value)
    fun isOpaqueEventKey(value: String): Boolean = eventKeyPattern.matches(value)
    fun isFingerprint(value: String): Boolean = fingerprintPattern.matches(value)

    fun isRestrictedMetadata(value: String): Boolean {
        val normalized = value.lowercase().replace(metadataSeparatorPattern, "_")
        return restrictedMetadataTokens.any { token -> normalized == token || normalized.contains("_${token}_") || normalized.startsWith("${token}_") || normalized.endsWith("_${token}") }
    }

    // Immutable compiled patterns are safe to share. Validation reads hundreds of records;
    // recompiling all detectors for every field adds avoidable overhead to large snapshots.
    private val rawRestrictedPatterns = listOf(
            "\\b(?:account|routing|card|debit|credit)\\s*(?:number|no\\.?|#|ending(?:\\s+in)?|last\\s+four)?\\s*[:#-]?\\s*(?:\\d[\\s-]?){4,}\\b",
            "\\blast\\s+four\\s*(?:digits?)?\\s*(?:are|is|[:#-])?\\s*(?:\\d[\\s-]?){4}\\b",
            "\\b(?:ssn|social security)\\b[\\s\\S]{0,40}\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}\\b",
            "\\b(?:available|current|ending)\\s+balance\\b[\\s\\S]{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\b(?:bank|checking|savings|account)\\s+balance\\b[\\s\\S]{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\bbalance\\b[\\s\\S]{0,40}\\b(?:bank|checking|savings|account)\\b[\\s\\S]{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\b(?:bank|checking|savings|account)(?:\\s+account)?\\b[\\s\\S]{0,80}(?:\\$\\d[\\d,]*(?:\\.\\d{2})?|\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?\\b)",
            "(?:\\$\\d[\\d,]*(?:\\.\\d{2})?|\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?\\b)[\\s\\S]{0,80}\\b(?:bank|checking|savings|account)(?:\\s+account)?\\b",
            "^\\s*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+.{2,}\\s+[-+]?\\$?\\d[\\d,]*(?:\\.\\d{2})?\\s*$",
            "^\\s*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s*,\\s*[^,\\n]{2,}\\s*,\\s*[-+]?\\$?\\d[\\d,]*(?:\\.\\d{2})?\\s*$",
            """\b(?:password|passcode|api[ _-]?key|access[ _-]?token|auth(?:entication)?[ _-]?token|secret)\b\s*(?:is|:|=)?\s*["']?[a-z0-9_./+=-]{6,}""",
        ).map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    /** Conservative bounded detectors, deliberately not a claim to detect every secret. */
    fun containsRawRestrictedContent(content: String): Boolean {
        val bounded = content.take(MAX_CONTENT_CHARS)
        return rawRestrictedPatterns.any { it.containsMatchIn(bounded) }
    }

    /** Opaque persisted event key: deletion never retains a caller supplied identifier. */
    fun sourceKey(eventId: String): String = sha256("memory-event\u0000" + eventId.trim()).take(32)

    /** Length-prefix encoding preserves null, empty, delimiters, controls and list boundaries. */
    fun fingerprint(proposal: MemoryProposal): String {
        val p = canonicalize(proposal)
        fun field(value: String?): String = if (value == null) "N;" else "S${value.length}:$value"
        // Keep the exact v2 payload for records that predate explicit wiki placement.
        // Wiki assignments are user metadata, so only an explicitly supplied assignment opts into v3.
        val legacyFields = listOf(p.content, p.category.name, p.tier.name, p.type.name, p.confidence.toString(), p.expiresAtMs?.toString(), p.correctsMemoryId, p.source.eventSource, p.source.createdAtMs.toString(), p.source.sensitivity.name)
        val fields = if (p.wikiAssignment == null) legacyFields else legacyFields + listOf(p.wikiAssignment.category.name, p.wikiAssignment.topic)
        return sha256(buildString {
            append(if (p.wikiAssignment == null) "memory-fingerprint-v2|" else "memory-fingerprint-v3|"); fields.forEach { append(field(it)) }
            append("L${p.source.provenance.size}:")
            p.source.provenance.forEach { x -> append(field(x.kind)); append(field(x.id)); append(field(x.label)); append(field(x.restricted.toString())) }
        })
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
