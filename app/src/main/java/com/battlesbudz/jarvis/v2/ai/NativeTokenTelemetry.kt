package com.battlesbudz.jarvis.v2.ai

/** SDK snapshot with mixed scopes: last completed prefill/decode counters and first-recorded-turn proxy.
 * Only attributed to an app submission after successful checked idle; never after cancellation/error.
 * Callback chunks and character estimates never enter this record. */
internal data class NativeTokenTelemetry(
    val inputTokens: Int,
    val outputTokens: Int,
    val sdkFirstPrefillDecodeTtftProxyMs: Double?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val readMs: Double
) {
    /** Recover the SDK phase duration from its exact count/rate pair; not an app-clock residual. */
    val derivedLastPrefillExecutorMs: Double? get() = duration(inputTokens, prefillTokensPerSecond)
    val derivedLastDecodeLoopMs: Double? get() = duration(outputTokens, decodeTokensPerSecond)
    private fun duration(count: Int, rate: Double?): Double? = rate?.takeIf { count > 0 && it.isFinite() && it > 0 }
        ?.let { (count * 1000.0 / it).takeIf { value -> value.isFinite() && value > 0 } }

    companion object {
        fun checked(input: Int, output: Int, firstSeconds: Double, prefillRate: Double,
                    decodeRate: Double, readMs: Double): NativeTokenTelemetry? {
            if (input < 0 || output < 0 || !readMs.isFinite() || readMs < 0) return null
            fun rate(value: Double) = value.takeIf { it.isFinite() && it > 0 }
            return NativeTokenTelemetry(input, output,
                firstSeconds.takeIf { output > 0 && it.isFinite() && it >= 0 }?.times(1000)?.takeIf(Double::isFinite),
                rate(prefillRate).takeIf { input > 0 }, rate(decodeRate).takeIf { output > 0 }, readMs)
        }
    }
}
