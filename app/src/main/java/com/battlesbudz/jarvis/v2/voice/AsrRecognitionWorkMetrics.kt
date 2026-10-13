package com.battlesbudz.jarvis.v2.voice

/** Actual recognizer work on its executing thread. Queue waits, microphone time,
 * model loading and caller accept wall time are outside this measurement.
 * Whisper measures its decode callback, including backend work and CPU fallback. */
data class AsrRecognitionWorkMetrics(
    val scope: String,
    val feedWorkNs: Long = 0,
    val partialDecodeWorkNs: Long = 0,
    val finalDecodeWorkNs: Long = 0,
    val recoveryDecodeWorkNs: Long = 0,
    val invocations: Int = 0,
    /** Sum of 16 kHz mono samples submitted to measured decode/native work.
     * Overlapping Whisper windows and explicit recoveries each count their input. */
    val submittedAudioSamples: Long = 0,
    val complete: Boolean = true
) {
    val workNs: Long get() = feedWorkNs + partialDecodeWorkNs + finalDecodeWorkNs + recoveryDecodeWorkNs
    val workMs: Long get() = workNs / 1_000_000
    val feedWorkMs: Long get() = feedWorkNs / 1_000_000
    val partialDecodeWorkMs: Long get() = partialDecodeWorkNs / 1_000_000
    val finalDecodeWorkMs: Long get() = finalDecodeWorkNs / 1_000_000
    val recoveryDecodeWorkMs: Long get() = recoveryDecodeWorkNs / 1_000_000
    val submittedAudioMs: Long get() = submittedAudioSamples * 1000 / 16_000
    val realtimeFactor: Double? get() = if (complete && submittedAudioSamples > 0)
        workNs / (submittedAudioSamples * (1_000_000_000.0 / 16_000)) else null

    internal fun plus(other: AsrRecognitionWorkMetrics): AsrRecognitionWorkMetrics = AsrRecognitionWorkMetrics(
        if (scope == other.scope) scope else "mixed_recognizer_api_wall",
        feedWorkNs + other.feedWorkNs, partialDecodeWorkNs + other.partialDecodeWorkNs,
        finalDecodeWorkNs + other.finalDecodeWorkNs, recoveryDecodeWorkNs + other.recoveryDecodeWorkNs,
        invocations + other.invocations, submittedAudioSamples + other.submittedAudioSamples, complete && other.complete)
}

internal class AsrRecognitionWorkAccumulator(
    private val scope: String,
    private val nowNs: () -> Long = System::nanoTime
) {
    enum class Phase { FEED, PARTIAL, FINAL, RECOVERY }
    private var metrics = AsrRecognitionWorkMetrics(scope)
    fun <T> measure(phase: Phase, submittedAudioSamples: Int = 0, operation: () -> T): T {
        require(submittedAudioSamples >= 0)
        val began = nowNs()
        try { return operation() }
        finally {
            val duration = (nowNs() - began).coerceAtLeast(0)
            synchronized(this) {
                metrics = metrics.copy(
                    feedWorkNs = metrics.feedWorkNs + if (phase == Phase.FEED) duration else 0,
                    partialDecodeWorkNs = metrics.partialDecodeWorkNs + if (phase == Phase.PARTIAL) duration else 0,
                    finalDecodeWorkNs = metrics.finalDecodeWorkNs + if (phase == Phase.FINAL) duration else 0,
                    recoveryDecodeWorkNs = metrics.recoveryDecodeWorkNs + if (phase == Phase.RECOVERY) duration else 0,
                    invocations = metrics.invocations + 1,
                    submittedAudioSamples = metrics.submittedAudioSamples + submittedAudioSamples)
            }
        }
    }
    @Synchronized fun snapshot(): AsrRecognitionWorkMetrics = metrics
}

/** Unknown stream work never becomes an apparently complete zero-duration run. */
internal class AsrRecognitionWorkLedger {
    private var completed: AsrRecognitionWorkMetrics? = null
    private var missing = false
    fun retain(measured: AsrRecognitionWorkMetrics?) {
        if (measured == null) missing = true else completed = completed?.plus(measured) ?: measured
    }
    fun withActive(measured: AsrRecognitionWorkMetrics?, hasActive: Boolean): AsrRecognitionWorkMetrics? {
        val result = completed?.let { if (measured == null) it else it.plus(measured) } ?: measured
        return result?.copy(complete = result.complete && !missing && (!hasActive || measured != null))
    }
}
