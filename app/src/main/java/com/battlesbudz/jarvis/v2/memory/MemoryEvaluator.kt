package com.battlesbudz.jarvis.v2.memory

/**
 * M6: relevance/confidence evaluation for automatic memory candidates (D46).
 *
 * Evaluation is separate from candidate capture and from task execution. Scores are
 * versioned: every [MemoryEvaluation] records [MemoryEvaluator.EVALUATOR_VERSION], so
 * scores produced by different evaluator generations can be distinguished and old
 * scores can be re-evaluated after an evaluator change.
 */
data class MemoryEvaluation(
    val candidateId: String,
    /** 0-100: how likely this fact is worth keeping long-term. */
    val relevance: Int,
    /** 0-100: how likely the captured fact is correct. */
    val confidence: Int,
    /** Evaluator generation that produced this score. */
    val evaluatorVersion: Int,
    val evaluatedAtMs: Long,
    val save: Boolean,
    val reason: String,
)

data class EvaluationContext(
    val existingMemories: List<MemoryRecord> = emptyList(),
    val tombstones: List<MemoryTombstone> = emptyList(),
)

interface MemoryEvaluator {
    fun evaluate(candidate: MemoryCandidate, context: EvaluationContext = EvaluationContext()): MemoryEvaluation

    companion object {
        /** Bump when the scoring logic changes; persisted on every evaluation. */
        const val EVALUATOR_VERSION = 1
    }
}

/**
 * Measured engineering defaults, tuned on the held-out fixture set in
 * AutomaticMemoryTest (see [AutomaticMemoryTest.thresholdFixtures]). These are
 * measured constants, not user-selected values (D46).
 */
object EvaluationThresholds {
    const val SAVE_RELEVANCE = 30
    const val SAVE_CONFIDENCE = 60
}

/**
 * Deterministic, rule-based evaluator. Used in tests and as the on-device fallback
 * when the local model is unavailable. Scoring is transparent: relevance accumulates
 * from content signals, confidence from source reliability and clarity.
 */
class HeuristicMemoryEvaluator : MemoryEvaluator {
    override fun evaluate(candidate: MemoryCandidate, context: EvaluationContext): MemoryEvaluation {
        val content = candidate.content
        val lower = content.lowercase()

        // Conflicting evidence: a contradicting approved memory with a clear subject match
        // blocks automatic save; the conflict is reported for the reason log.
        val conflict = context.existingMemories.firstOrNull {
            it.reviewStatus == MemoryReviewStatus.APPROVED && contradicts(it.content, content)
        }

        var relevance = 0
        if (PREFERENCE_PATTERN.containsMatchIn(lower)) relevance += 35
        if (FACT_PATTERN.containsMatchIn(lower)) relevance += 30
        if (PERSON_PATTERN.containsMatchIn(lower)) relevance += 25
        if (TEMPORAL_PATTERN.containsMatchIn(lower)) relevance += 15
        if (Regex("\\b[A-Z][a-z]{2,}\\b").containsMatchIn(content)) relevance += 10
        if (content.length in 15..200) relevance += 10
        if (TRIVIAL_PATTERN.matches(lower)) relevance = 0
        relevance = relevance.coerceIn(0, 100)

        var confidence = when (candidate.source) {
            AutoMemorySource.TOOL -> 85
            AutoMemorySource.CHAT -> 80
            AutoMemorySource.VOICE -> 65
        }
        if (content.length > 20) confidence += 5
        if (content.contains("?")) confidence -= 15
        confidence = confidence.coerceIn(0, 100)

        val save = conflict == null &&
            relevance >= EvaluationThresholds.SAVE_RELEVANCE &&
            confidence >= EvaluationThresholds.SAVE_CONFIDENCE

        val reason = when {
            conflict != null -> "Conflicts with existing memory; not auto-saved."
            !save && relevance < EvaluationThresholds.SAVE_RELEVANCE -> "Relevance $relevance below threshold ${EvaluationThresholds.SAVE_RELEVANCE}."
            !save -> "Confidence $confidence below threshold ${EvaluationThresholds.SAVE_CONFIDENCE}."
            else -> "Relevance $relevance, confidence $confidence meet thresholds."
        }
        return MemoryEvaluation(
            candidateId = candidate.id,
            relevance = relevance,
            confidence = confidence,
            evaluatorVersion = MemoryEvaluator.EVALUATOR_VERSION,
            evaluatedAtMs = System.currentTimeMillis(),
            save = save,
            reason = reason,
        )
    }

    /**
     * Conservative contradiction check for location-type facts: both statements assert
     * the same "I live in <place>" relationship with different places.
     */
    private fun contradicts(existing: String, candidate: String): Boolean {
        val placeOf = { text: String ->
            Regex("\\bi live in ([a-z ,.'-]+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()?.lowercase()
        }
        val a = placeOf(existing)
        val b = placeOf(candidate)
        return a != null && b != null && a != b
    }

    companion object {
        private val PREFERENCE_PATTERN = Regex("\\b(i like|i love|i prefer|i hate|i dislike|my favorit\\w*|i can't stand)\\b")
        private val FACT_PATTERN = Regex("\\b(i live in|i work at|i work as|i am a|my name is|my birthday is|appointment|meeting)\\b")
        private val PERSON_PATTERN = Regex("\\b(my (wife|husband|son|daughter|mom|dad|mother|father|brother|sister|friend|boss|partner))\\b")
        private val TEMPORAL_PATTERN = Regex("\\b(tomorrow|today|yesterday|every day|every week|birthday|appointment|meeting|at \\d)\\b")
        private val TRIVIAL_PATTERN = Regex("^(hi|hello|hey|thanks|thank you|ok|okay|yes|no|sure|lol|lmao)\\.?$")
    }
}
