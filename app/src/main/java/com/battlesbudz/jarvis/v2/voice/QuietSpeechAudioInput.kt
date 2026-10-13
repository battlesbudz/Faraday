package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.map

/** Bounded level conditioning shared by VAD, ASR and the retained Gemma audio. */
class QuietSpeechAudioInput(private val input: AudioInput, private val log: (String) -> Unit = {}, maxGain: Double = 3.0) : AudioInput {
    private val gain = QuietSpeechGain(maxGain)
    private var chunks = 0
    override val sampleRateHz get() = input.sampleRateHz
    override val channelCount get() = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override val stoppedUnconsumedPcmBytes get() = input.stoppedUnconsumedPcmBytes
    override val captureNoiseProfile get() = input.captureNoiseProfile
    override val lastChunkSequence get() = input.lastChunkSequence
    override fun deferConsumptionAcknowledgement() = input.deferConsumptionAcknowledgement()
    override fun acknowledgeConsumed(sequence: Long) = input.acknowledgeConsumed(sequence)
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = input.chunks().map { pcm ->
        gain.apply(pcm).also {
            // This percentile includes speech. It must not be presented as the
            // VAD-qualified room floor or a foreground/background SNR estimate.
            if (++chunks % 10 == 0) log("quiet_speech_gain gain=${gain.currentGain} inputRms=${gain.inputRms} levelPercentileRms=${gain.noiseFloorRms}")
        }
    }
}

/** Never gates speech by loudness or lowers Silero's probability threshold. */
class QuietSpeechGain(private val maxGain: Double = 3.0) {
    init { require(maxGain in 1.0..8.0) }
    var currentGain = 1.0
        private set
    private val levels = ArrayDeque<Double>()
    var noiseFloorRms = 0.0
        private set
    var inputRms = 0.0
        private set
    fun apply(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0) { "PCM16 requires whole samples." }
        if (pcm.isEmpty()) return pcm
        var energy = 0.0
        var peak = 0
        for (i in pcm.indices step 2) {
            val value = sample(pcm, i)
            energy += value.toDouble() * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        inputRms = kotlin.math.sqrt(energy / (pcm.size / 2))
        // Leave digital silence alone; cap amplification and reserve headroom for sudden speech.
        levels.addLast(inputRms)
        if (levels.size > 30) levels.removeFirst()
        noiseFloorRms = levels.sorted()[(levels.size - 1) / 5].coerceAtLeast(8.0)
        // Never amplify a steady room floor. This estimates noise, not speaker identity,
        // and leaves the original samples available even when gain is withheld.
        val target = if (inputRms < noiseFloorRms * 1.6) 1.0
            else (900.0 / inputRms).coerceIn(1.0, maxGain)
        currentGain = if (target < currentGain) target else currentGain + (target - currentGain) * 0.5
        currentGain = minOf(currentGain, if (peak == 0) 1.0 else (28000.0 / peak).coerceAtLeast(1.0))
        return ByteArray(pcm.size).also { result ->
            for (i in pcm.indices step 2) {
                val value = (sample(pcm, i) * currentGain).toInt().coerceIn(-32768, 32767)
                result[i] = value.toByte(); result[i + 1] = (value shr 8).toByte()
            }
        }
    }
    private fun sample(pcm: ByteArray, i: Int) =
        ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
}
