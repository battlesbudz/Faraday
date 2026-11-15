package com.battlesbudz.jarvis.v2.memory

data class DreamGateDecision(val allowed: Boolean, val reason: String) {
    companion object {
        val ALLOW = DreamGateDecision(true, DreamReasons.OK)
        fun refuse(reason: String) = DreamGateDecision(false, reason)
    }
}

/**
 * Vessel's veto pattern sets, adapted verbatim in shape from dreamPolicy.ts.
 * They are deliberately blunt: a consolidation output that talks about
 * credentials, medical/legal matters, relationships, contradictions, or in
 * hedged language is refused and journaled rather than committed flat.
 */
object DreamVetoPatterns {
    private val sensitive = listOf(
        Regex("\\b(password|passcode|api[\\s_-]*key|secret|token|credential|private key|seed phrase|oauth)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(ssn|social security|credit card|debit card|routing number|bank account|tax return|irs)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(diagnosis|diagnosed|prescription|medication|therapy|therapist|medical|symptom)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(lawsuit|sue|attorney|lawyer|contract|nda|legal|compliance)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(husband|wife|girlfriend|boyfriend|partner|dating|married|divorced|pregnant)\\b", RegexOption.IGNORE_CASE),
    )
    private val contradiction = listOf(
        Regex("\\bcontradict(s|ed|ion)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bconflict(s|ed|ing)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsupersed(es|ed|ing)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bchanged (their|his|her|my) mind\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno longer\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnot generally\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcorrection\\b", RegexOption.IGNORE_CASE),
    )
    private val hedging = listOf(
        Regex("\\bmaybe\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmight\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcould\\b", RegexOption.IGNORE_CASE),
        Regex("\\bperhaps\\b", RegexOption.IGNORE_CASE),
        Regex("\\bseems?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmay indicate\\b", RegexOption.IGNORE_CASE),
        Regex("\\bunclear\\b", RegexOption.IGNORE_CASE),
    )

    fun isSensitive(content: String): Boolean = sensitive.any { it.containsMatchIn(content) }
    fun hasContradictionSignal(content: String): Boolean = contradiction.any { it.containsMatchIn(content) }
    fun isHedged(content: String): Boolean = hedging.any { it.containsMatchIn(content) }
}

/**
 * The deterministic commit gate (scope §3.4, hardened by OD-4). It is the only
 * thing between a small model's nightly judgment and the ledger, so every
 * check is code, not prompt: fence first, the OD-4 verb ban, evidence floor,
 * derived-sole-source ban, confidence band (conservative lane for
 * PERSON/PREFERENCE), Vessel's veto sets, and the 500-record cap headroom.
 * Refusals are journaled by the caller with the reason returned here (OD-3).
 */
object MemoryDreamGate {
    /** Dream-specific confidence floor; NOT the capture thresholds (30/60). */
    const val DREAM_MIN_CONFIDENCE = 90

    /** PERSON/PREFERENCE groups must clear the judge's highest band. */
    const val CONSERVATIVE_MIN_CONFIDENCE = 95

    const val MIN_SYNTHESIS_SOURCES = 3

    fun evaluate(
        verb: DreamVerb,
        content: String,
        confidence: Int,
        sourceRecords: List<MemoryRecord>,
        fenced: Boolean,
        currentStoredCount: Int,
    ): DreamGateDecision {
        if (fenced) return DreamGateDecision.refuse(DreamReasons.FENCED_CONTENT)
        // OD-4: the dream creates; it never supersedes autonomously.
        if (verb == DreamVerb.MERGE || verb == DreamVerb.SUPERSEDE) {
            return DreamGateDecision.refuse(DreamReasons.AUTONOMOUS_SUPERSEDE_DISABLED)
        }
        if (sourceRecords.size < MIN_SYNTHESIS_SOURCES) {
            return DreamGateDecision.refuse(DreamReasons.EVIDENCE_FLOOR)
        }
        if (sourceRecords.all { MemoryDreamGrouping.isDreamDerived(it) }) {
            return DreamGateDecision.refuse(DreamReasons.DERIVED_SOLE_SOURCE)
        }
        val conservative = sourceRecords.any {
            it.category == MemoryCategory.PERSON || it.category == MemoryCategory.PREFERENCE
        }
        val floor = if (conservative) CONSERVATIVE_MIN_CONFIDENCE else DREAM_MIN_CONFIDENCE
        if (confidence < floor) return DreamGateDecision.refuse(DreamReasons.CONFIDENCE_FLOOR)
        if (MemoryPolicy.containsRawRestrictedContent(content)) {
            return DreamGateDecision.refuse(DreamReasons.VETO_RESTRICTED)
        }
        if (DreamVetoPatterns.isSensitive(content)) {
            return DreamGateDecision.refuse(DreamReasons.VETO_SENSITIVE)
        }
        if (DreamVetoPatterns.hasContradictionSignal(content)) {
            return DreamGateDecision.refuse(DreamReasons.VETO_CONTRADICTION)
        }
        // Conservative lane: a contradiction signal anywhere in the evidence
        // forces no action for people/preferences, even in a clean output.
        if (conservative && sourceRecords.any { DreamVetoPatterns.hasContradictionSignal(it.content) }) {
            return DreamGateDecision.refuse(DreamReasons.VETO_CONTRADICTION)
        }
        if (DreamVetoPatterns.isHedged(content)) {
            return DreamGateDecision.refuse(DreamReasons.VETO_HEDGE)
        }
        if (currentStoredCount >= MemoryPolicy.MAX_MEMORIES) {
            return DreamGateDecision.refuse(DreamReasons.CAP_HEADROOM)
        }
        return DreamGateDecision.ALLOW
    }
}
