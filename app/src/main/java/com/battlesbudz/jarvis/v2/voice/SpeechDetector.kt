package com.battlesbudz.jarvis.v2.voice

/** Classification of audio content; loudness is only diagnostic information. */
data class SpeechDecision(val isSpeech: Boolean, val probability: Float,
    val speechSamples: Int? = null, val strongSpeechSamples: Int? = null,
    val rawCoverage: RawVadCoverage? = null)

interface SpeechDetector : AutoCloseable {
    fun accept(pcm: ByteArray): SpeechDecision
}

/** Frames arbitrary PCM16 chunks for Silero and rejects isolated positive frames. */
class FrameSpeechDetector(
    private val computeProbability: (FloatArray) -> Float,
    private val releaseModel: () -> Unit = {}
) : SpeechDetector {
    private val frame = FloatArray(512)
    private var frameSize = 0
    private var lowByte: Int? = null
    private var consecutiveSpeechFrames = 0
    private var lastProbability = 0f
    private var consecutiveStrongFrames = 0
    private var closed = false
    private var receivedPcmBytes = 0L
    private var classifiedSamples = 0L
    private var quietFromSample = 0L

    override fun accept(pcm: ByteArray): SpeechDecision {
        check(!closed) { "Speech detector has been released." }
        var confirmedSpeech = false
        var speechSamples = 0
        var strongSamples = 0
        var maxProbability = 0f
        var computed = false
        var completedFrames = 0
        receivedPcmBytes += pcm.size
        for (byte in pcm) {
            val low = lowByte
            if (low == null) {
                lowByte = byte.toInt() and 0xff
                continue
            }
            val word = low or ((byte.toInt() and 0xff) shl 8)
            frame[frameSize++] = word.toShort().toInt() / 32768f
            lowByte = null
            if (frameSize == frame.size) {
                val probability = computeProbability(frame)
                check(probability.isFinite() && probability in 0f..1f) {
                    "Speech detector returned an invalid probability."
                }
                classifiedSamples += 512
                completedFrames++
                if (probability >= .15f) quietFromSample = classifiedSamples
                lastProbability = probability
                maxProbability = maxOf(maxProbability, probability)
                computed = true
                val previouslyConfirmed = consecutiveSpeechFrames >= 3
                consecutiveStrongFrames = if (probability >= .8f) (consecutiveStrongFrames + 1).coerceAtMost(3) else 0
                consecutiveSpeechFrames = if (probability >= 0.5f) {
                    (consecutiveSpeechFrames + 1).coerceAtMost(3)
                } else 0
                // Three 32 ms frames confirm speech. Pre-roll preserves onset.
                confirmedSpeech = confirmedSpeech || consecutiveSpeechFrames >= 3
                if (consecutiveSpeechFrames >= 3) {
                    // Credit the two onset frames once, when the third confirms them.
                    speechSamples += if (previouslyConfirmed) 512 else 1536
                    strongSamples += if (previouslyConfirmed) { if (probability >= .8f) 512 else 0 }
                        else consecutiveStrongFrames * 512
                }
                frameSize = 0
            }
        }
        return SpeechDecision(confirmedSpeech, if (computed) maxProbability else lastProbability, speechSamples, strongSamples,
            RawVadCoverage(receivedPcmBytes, classifiedSamples, quietFromSample, completedFrames))
    }

    override fun close() {
        if (!closed) {
            closed = true
            releaseModel()
        }
    }
}
