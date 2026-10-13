package com.battlesbudz.jarvis.v2.voice

import kotlin.math.log10

/** Measurements of PCM consumed by capture, after hardware/wrapper processing.
 * Speech/non-speech split uses whole externally admitted VAD frames. It is not
 * speaker identity, ASR confidence, intelligibility, or a measured clean/noisy SNR.
 * Digital silence has no finite dBFS level and is exported as null, never infinity. */
data class CaptureAcousticMetrics(
    val sampleCount: Long,
    val speechSampleCount: Long,
    val nearSilentSampleCount: Long,
    val clippedSampleCount: Long,
    val rmsDbfs: Double?,
    val peakDbfs: Double?,
    val nonSpeechRmsDbfs: Double?,
    val speechRmsDbfs: Double?,
    val speechToNonSpeechEnergyDb: Double?,
    val noiseFloorRms: Double
)

internal class CaptureAcousticAccumulator {
    private var samples = 0L
    private var speechSamples = 0L
    private var nearSilent = 0L
    private var clipped = 0L
    private var energy = 0.0
    private var speechEnergy = 0.0
    private var peak = 0
    private var noiseFloor = 0.0

    fun record(signal: Pcm16Signal, decision: SpeechDecision, noiseFloorRms: Double) {
        samples += signal.sampleCount
        nearSilent += signal.nearSilentSampleCount
        clipped += signal.clippedSampleCount
        val frameEnergy = signal.rms * signal.rms * signal.sampleCount
        energy += frameEnergy
        peak = maxOf(peak, signal.peak)
        if (decision.isSpeech) { speechSamples += signal.sampleCount; speechEnergy += frameEnergy }
        noiseFloor = noiseFloorRms
    }

    fun snapshot(): CaptureAcousticMetrics {
        val speechMeanEnergy = mean(speechEnergy, speechSamples)
        val noiseMeanEnergy = mean((energy - speechEnergy).coerceAtLeast(0.0), samples - speechSamples)
        return CaptureAcousticMetrics(samples, speechSamples, nearSilent, clipped,
            dbfs(mean(energy, samples)), dbfs(peak.toDouble() * peak),
            dbfs(noiseMeanEnergy), dbfs(speechMeanEnergy),
            if (speechMeanEnergy != null && noiseMeanEnergy != null)
                10.0 * log10(speechMeanEnergy / noiseMeanEnergy) else null,
            noiseFloor)
    }

    private fun mean(sum: Double, count: Long): Double? =
        if (sum > 0.0 && count > 0) sum / count else null
    private fun dbfs(meanEnergy: Double?): Double? = meanEnergy?.takeIf { it > 0 }?.let {
        10.0 * log10(it / (32768.0 * 32768.0))
    }
}
