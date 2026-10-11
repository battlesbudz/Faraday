package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.ToolCall
import kotlin.math.ceil

/**
 * JVM-pure latency tracking for the M8 model comparison benchmark.
 *
 * The M8 plan requires cold/warm p50/p95 *full-action* latency — the
 * wall-clock time from utterance submission to the model's complete tool-call
 * emission — not tokens/second. Cold samples are the first utterance after a
 * model load (engine init, weights warm-up); warm samples are everything
 * after. This distinction matters because a model that is fast warm but slow
 * cold behaves differently in Jarvis's always-resident vs on-demand paths.
 *
 * This tracker is measurement only: it records samples and computes
 * percentiles. Wiring it to a real engine happens through
 * [TimingToolCallRunner], which decorates any [ToolCallRunner].
 */
data class LatencySample(
    val isCold: Boolean,
    val millis: Long
)

/**
 * Percentile summary of recorded latency samples, split by cold/warm.
 * All values are in milliseconds. A null percentile means no samples were
 * recorded for that class.
 */
data class LatencyStats(
    val coldP50Ms: Long?,
    val coldP95Ms: Long?,
    val warmP50Ms: Long?,
    val warmP95Ms: Long?,
    val coldCount: Int,
    val warmCount: Int
) {
    val totalCount: Int get() = coldCount + warmCount
}

/**
 * Records latency samples and computes cold/warm p50/p95.
 *
 * Percentiles use the nearest-rank method: for N sorted samples, p50 is the
 * value at rank ceil(0.50 * N), p95 at rank ceil(0.95 * N). This is
 * deterministic and needs no interpolation, which keeps JVM tests exact.
 */
class ModelLatencyTracker {
    private val coldSamples = mutableListOf<Long>()
    private val warmSamples = mutableListOf<Long>()

    fun record(sample: LatencySample) {
        require(sample.millis >= 0) { "latency millis must be non-negative" }
        if (sample.isCold) coldSamples.add(sample.millis)
        else warmSamples.add(sample.millis)
    }

    fun stats(): LatencyStats = LatencyStats(
        coldP50Ms = percentile(coldSamples, 50),
        coldP95Ms = percentile(coldSamples, 95),
        warmP50Ms = percentile(warmSamples, 50),
        warmP95Ms = percentile(warmSamples, 95),
        coldCount = coldSamples.size,
        warmCount = warmSamples.size
    )

    fun reset() {
        coldSamples.clear()
        warmSamples.clear()
    }

    companion object {
        internal fun percentile(sorted: List<Long>, p: Int): Long? {
            if (sorted.isEmpty()) return null
            require(p in 1..99) { "percentile must be 1..99" }
            val ordered = sorted.sorted()
            val rank = ceil(p / 100.0 * ordered.size).toInt().coerceAtLeast(1)
            return ordered[(rank - 1).coerceAtMost(ordered.size - 1)]
        }
    }
}

/**
 * [ToolCallRunner] decorator that measures full-action latency per utterance
 * and records it into a [ModelLatencyTracker].
 *
 * The first utterance run through this decorator after [markModelLoaded] (or
 * construction) is recorded as cold; all subsequent utterances are warm.
 * Timing uses the injectable [clock] (millis) so JVM tests can use a fake.
 *
 * This measures wall-clock time around the delegate's [ToolCallRunner.runUtterance],
 * which for on-device engines covers prompt encoding, prefill, decode to the
 * complete tool-call emission, and response parsing — the full action, not
 * tokens/second.
 */
class TimingToolCallRunner(
    private val delegate: ToolCallRunner,
    private val tracker: ModelLatencyTracker,
    private val clock: () -> Long = System::currentTimeMillis
) : ToolCallRunner {
    private var coldPending = true

    /** Call after each model load so the next utterance is measured as cold. */
    fun markModelLoaded() {
        coldPending = true
    }

    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> {
        val isCold = coldPending
        val start = clock()
        try {
            return delegate.runUtterance(modelId, utterance)
        } finally {
            val elapsed = (clock() - start).coerceAtLeast(0)
            tracker.record(LatencySample(isCold = isCold, millis = elapsed))
            coldPending = false
        }
    }
}
