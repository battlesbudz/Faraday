package com.battlesbudz.jarvis.v2.eval

/**
 * M8 adoption decision: compares a candidate model path (FunctionGemma,
 * a different E2B/E4B build) against the current baseline (exact-command
 * path or incumbent model) on matched benchmark results and produces an
 * enable/retain recommendation.
 *
 * The plan's rule, encoded here as deterministic logic:
 * - **Reliability non-regression is required.** The candidate's correctness
 *   (tool choice + argument agreement on the matched fixture set) must be at
 *   least the baseline's. A faster model that gets more calls wrong is never
 *   adopted.
 * - **Meaningful latency improvement is required.** The candidate's warm p95
 *   full-action latency must beat the baseline's by at least
 *   [MIN_LATENCY_IMPROVEMENT]. Marginal gains do not justify switching the
 *   production path.
 * - **Never decide on tokens/second alone.** This decision consumes
 *   full-action latency (utterance to complete tool-call emission) and
 *   correctness, never a raw throughput number.
 * - Otherwise the existing path is retained.
 *
 * Inputs are measured [BenchmarkResult] values — never fabricated. D-level
 * evidence comes from the physical Fold 6 benchmark run; JVM tests cover the
 * decision logic with synthetic results.
 */
data class BenchmarkResult(
    val modelId: String,
    /** Fraction of fixtures fully correct (tool + args + no extras), 0.0..1.0. */
    val correctness: Double,
    val warmP50Ms: Long?,
    val warmP95Ms: Long?,
    val coldP50Ms: Long?,
    /** Identity of the measured artifact; results are void without it. */
    val modelFingerprint: String?,
    val suiteVersion: Int
)

sealed interface AdoptionDecision {
    /** Adopt the candidate for the production tool-calling path. */
    data class Adopt(val rationale: String) : AdoptionDecision
    /** Keep the existing path; the candidate did not clear the bar. */
    data class Retain(val rationale: String) : AdoptionDecision
}

object AdoptionDecider {
    /**
     * Minimum relative warm-p95 improvement required to adopt: the
     * candidate's warm p95 must be at most (1 - this) of the baseline's.
     * A measured engineering default, not a user-selected constant.
     */
    const val MIN_LATENCY_IMPROVEMENT = 0.15

    fun decide(baseline: BenchmarkResult, candidate: BenchmarkResult): AdoptionDecision {
        require(baseline.suiteVersion == candidate.suiteVersion) {
            "cannot compare across suite versions (${baseline.suiteVersion} vs ${candidate.suiteVersion})"
        }
        if (baseline.modelFingerprint.isNullOrBlank() || candidate.modelFingerprint.isNullOrBlank()) {
            return AdoptionDecision.Retain(
                "missing artifact identity (baseline=${baseline.modelId} " +
                    "fingerprint=${baseline.modelFingerprint}, candidate=${candidate.modelId} " +
                    "fingerprint=${candidate.modelFingerprint}); no decision without measured artifacts"
            )
        }
        if (candidate.correctness < baseline.correctness) {
            return AdoptionDecision.Retain(
                "reliability regression: candidate ${candidate.modelId} correctness " +
                    "${pct(candidate.correctness)} < baseline ${baseline.modelId} " +
                    "${pct(baseline.correctness)}"
            )
        }
        val baseP95 = baseline.warmP95Ms
        val candP95 = candidate.warmP95Ms
        if (baseP95 == null || candP95 == null) {
            return AdoptionDecision.Retain(
                "missing warm-p95 latency (baseline=$baseP95, candidate=$candP95); " +
                    "no adoption without measured full-action latency"
            )
        }
        if (baseP95 <= 0) {
            return AdoptionDecision.Retain("baseline warm-p95 is non-positive; cannot compute improvement")
        }
        val improvement = (baseP95 - candP95).toDouble() / baseP95
        if (improvement < MIN_LATENCY_IMPROVEMENT) {
            return AdoptionDecision.Retain(
                "insufficient latency improvement: candidate warm p95 ${candP95}ms vs " +
                    "baseline ${baseP95}ms (${pct(improvement)} improvement, " +
                    "need ${pct(MIN_LATENCY_IMPROVEMENT)})"
            )
        }
        return AdoptionDecision.Adopt(
            "candidate ${candidate.modelId} clears the bar: correctness " +
                "${pct(candidate.correctness)} >= baseline ${pct(baseline.correctness)}, " +
                "warm p95 ${candP95}ms vs ${baseP95}ms (${pct(improvement)} improvement)"
        )
    }

    private fun pct(fraction: Double): String =
        "%.1f".format((fraction * 100).coerceAtLeast(0.0)) + "%"
}
