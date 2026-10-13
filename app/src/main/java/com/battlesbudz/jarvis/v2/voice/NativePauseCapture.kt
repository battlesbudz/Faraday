package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** A completed, unpadded RAW VAD frame watermark. Coordinates start at microphone reader start. */
data class RawVadCoverage(
    val receivedPcmBytes: Long,
    val classifiedThroughSample: Long,
    /** Every completed frame after this boundary has raw probability strictly below .15. */
    val quietFromSample: Long,
    val completedFrames: Int
)

enum class NativePauseInvalidation {
    RESUMED_OR_UNCERTAIN_AUDIO, UNKNOWN_RAW_COVERAGE, HARDWARE_BACKLOG, TAIL_LIMIT,
    WINDOW_ROLLED, AUDIO_LIMIT, CANDIDATE_DISCARDED, CAPTURE_FAILED, CANCELLED,
    FINAL_INPUT_MISMATCH, ENDPOINT_REJECTED
}

/** Nonblocking capture callbacks. Admission must enqueue only, never run or wait for inference. */
interface NativePauseObserver {
    /** False leaves the ordinary retained-PCM encoder untouched. At most one proposal per capture. */
    fun onProposal(proposal: NativePauseProposal): Boolean
    /** Revoke synchronously, before returning. Worker cancellation/drain belongs to the turn owner. */
    fun onInvalidated(reason: NativePauseInvalidation)
}

/**
 * Explicit NEW request identity: audio frozen at the clean-pause boundary, including pre-roll.
 * This is not the complete recording. PCM getters copy; no caller can change the certified bytes.
 */
class NativePauseProposal internal constructor(
    val turnId: String,
    val generation: Long,
    val candidateId: Long,
    val captureSampleBoundary: Long,
    val proposedAtNs: Long,
    pcm16: ByteArray
) {
    private val pcm = pcm16.copyOf()
    val inputPolicyVersion: String = "native_frozen_pause_raw_coverage_v1"
    val sampleCount: Int = pcm.size / 2
    val pcmSha256: String = pcmHash(pcm)
    init { require(pcm.isNotEmpty() && pcm.size % 2 == 0 && generation > 0 && turnId.isNotBlank()) }
    fun pcm16(): ByteArray = pcm.copyOf()
    fun wav(): ByteArray = WavEncoder.pcm16Mono(pcm, 16_000)
}

/** Created only after producer/collector join, never merely from a silence timer or queue emptiness. */
class NativePauseCertificate internal constructor(
    val proposal: NativePauseProposal,
    val fullRecordingSampleCount: Int,
    val fullRecordingPcmSha256: String,
    val excludedSampleCount: Int,
    val rawClassifiedThroughSample: Long,
    val endpointDecisionAtNs: Long
) {
    /** Exact full-recording fallback identity, independently checked again by the turn owner. */
    fun matchesCompleteWav(wav: ByteArray): Boolean = runCatching {
        require(wav.size == 44 + fullRecordingSampleCount * 2)
        val pcm = wav.copyOfRange(44, wav.size)
        // The capture emits the canonical PCM16/mono WAV encoder, not arbitrary RIFF layouts.
        wav.contentEquals(WavEncoder.pcm16Mono(pcm, 16_000)) && pcmHash(pcm) == fullRecordingPcmSha256
    }.getOrDefault(false)
}

internal fun pcmHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * One reversible attempt. The complete recording is owned by AudioTurnCapture; this bounded tail
 * is independent evidence that no continuation was sent into, or lost after, the frozen encoder.
 * Raw callbacks run on the VAD producer; retention and proposal run on the capture collector.
 */
internal class NativePauseCapture(
    private val observer: NativePauseObserver,
    private val turnId: String,
    private val generation: Long,
    private val maxTailBytes: Int = 1200 * 32
) {
    private var latest: RawVadCoverage? = null
    private var attempted = false
    private var invalidated = false
    private var proposal: NativePauseProposal? = null
    private val tail = ByteArrayOutputStream()
    @Volatile var encoderFrozen: Boolean = false
        private set

    val canPropose: Boolean @Synchronized get() = !attempted && !invalidated

    @Synchronized fun observeRaw(raw: SpeechDecision) {
        val coverage = raw.rawCoverage
        latest = coverage
        val candidate = proposal ?: return
        if (coverage == null) invalidate(NativePauseInvalidation.UNKNOWN_RAW_COVERAGE)
        else if (coverage.quietFromSample > candidate.captureSampleBoundary || raw.isSpeech) {
            invalidate(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO)
        }
    }

    @Synchronized fun retain(pcm: ByteArray) {
        if (!encoderFrozen || invalidated) return
        if (tail.size().toLong() + pcm.size > maxTailBytes) invalidate(NativePauseInvalidation.TAIL_LIMIT)
        else tail.write(pcm)
    }

    @Synchronized fun propose(pcm: ByteArray, captureSampleBoundary: Long, proposedAtNs: Long): Boolean {
        if (attempted || invalidated) return false
        val coverage = latest ?: return false
        // An unfinished frame inside the candidate is retained, not certified as silence.
        if (coverage.receivedPcmBytes != captureSampleBoundary * 2 ||
            coverage.classifiedThroughSample <= coverage.quietFromSample) return false
        attempted = true
        val next = NativePauseProposal(turnId, generation, 1, captureSampleBoundary, proposedAtNs, pcm)
        if (!observer.onProposal(next)) return false
        proposal = next
        encoderFrozen = true
        return true
    }

    @Synchronized fun invalidate(reason: NativePauseInvalidation) {
        if (invalidated) return
        invalidated = true
        tail.reset()
        if (encoderFrozen) observer.onInvalidated(reason)
    }

    @Synchronized fun certificateAfterJoin(fullPcm: ByteArray, endpointNs: Long?, hardwareBacklogMs: Long): NativePauseCertificate? {
        val candidate = proposal ?: return null
        if (invalidated) return null
        if (hardwareBacklogMs > 0) { invalidate(NativePauseInvalidation.HARDWARE_BACKLOG); return null }
        if (endpointNs == null || endpointNs < candidate.proposedAtNs) { invalidate(NativePauseInvalidation.ENDPOINT_REJECTED); return null }
        val excludedBytes = fullPcm.size - candidate.sampleCount * 2
        val expectedTail = tail.toByteArray()
        if (fullPcm.size % 2 != 0 || excludedBytes < 0 || expectedTail.size != excludedBytes ||
            !fullPcm.copyOfRange(0, candidate.sampleCount * 2).contentEquals(candidate.pcm16()) ||
            !fullPcm.copyOfRange(candidate.sampleCount * 2, fullPcm.size).contentEquals(expectedTail)) {
            invalidate(NativePauseInvalidation.FINAL_INPUT_MISMATCH); return null
        }
        val coverage = latest
        val requiredEnd = candidate.captureSampleBoundary + excludedBytes / 2
        if (coverage == null || coverage.receivedPcmBytes % 2 != 0L ||
            coverage.classifiedThroughSample < requiredEnd || coverage.quietFromSample > candidate.captureSampleBoundary) {
            invalidate(NativePauseInvalidation.UNKNOWN_RAW_COVERAGE); return null
        }
        return NativePauseCertificate(candidate, fullPcm.size / 2, pcmHash(fullPcm), excludedBytes / 2,
            coverage.classifiedThroughSample, endpointNs)
    }
}
