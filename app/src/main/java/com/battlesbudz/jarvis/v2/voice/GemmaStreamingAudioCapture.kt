package com.battlesbudz.jarvis.v2.voice

import android.os.Looper
import com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.NativeAudioArtifactLease
import com.google.ai.edge.litertlm.NativeAudioIdentity
import com.google.ai.edge.litertlm.NativeAudioOwner
import com.google.ai.edge.litertlm.NativeAudioWorker
import com.google.ai.edge.litertlm.NativeClockContract
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Turn-owned adapter. Capture observes retained bytes; only a joined complete
 * request can expose immutable SDK content. No microphone or tool authority. */
internal class GemmaStreamingAudioCapture(
    private val artifact: GemmaStreamingArtifactStore.Lease,
    turnId: String,
    turnIsCurrent: () -> Boolean,
    private val beforeEncoderCreate: () -> Unit = {},
    private val onEncoderPreparationWait: (Long) -> Unit = {},
    private val cancelEncoderPreparation: () -> Unit = {},
    private val onEncoderWork: (RetainedPcmEncoderWorker.WorkSnapshot) -> Unit = {},
) : RetainedPcmObserver {
    private val valid = AtomicBoolean(true)
    private val turnContext = AtomicReference<(() -> Boolean)?>(turnIsCurrent)
    private val generation = generations.incrementAndGet().also { check(it > 0) }
    private val identityPrefix = MessageDigest.getInstance("SHA-256").digest(turnId.toByteArray(Charsets.UTF_8))
        .take(20).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val nativeArtifact = object : NativeAudioArtifactLease {
        override val immutablePath get() = artifact.file.path
        override fun checkImmutableAndLive() = artifact.checkImmutableAndLive()
    }
    private val worker = RetainedPcmEncoderWorker<Content.SealedAudioEmbeddings>(
        createEncoder = { candidate ->
            val waitedAt = System.nanoTime()
            beforeEncoderCreate()
            runCatching { onEncoderPreparationWait((System.nanoTime() - waitedAt) / 1_000_000) }
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_before_create" }
            artifact.checkImmutableAndLive()
            val workerToken = NativeAudioWorker.bindCurrentThread {
                check(Looper.myLooper() != Looper.getMainLooper()) { "native_audio_on_ui_thread" }
                check(Thread.currentThread().name == "JarvisNativeAudioEncoder") { "native_audio_not_owned_worker" }
            }
            // Allocate the app holder before publishing any native registry handle.
            val adapter = EncoderAdapter()
            adapter.native = NativeAudioOwner.createOnWorker(workerToken, nativeArtifact,
                NativeAudioIdentity("${identityPrefix}_$candidate", generation),
                // Reviewed AOSP API30–36 source contract; the SDK still downgrades
                // host/unknown runtimes and validates bounded live clock alignment.
                clockContract = NativeClockContract.REVIEWED_AOSP_ANDROID_API30_36)
            adapter
        },
        isGenerationCurrent = { valid.get() && turnContext.get()?.invoke() == true },
        // Never exceed the already-existing 30-second accepted PCM budget.
        maxQueuedBytes = PREPARATION_QUEUED_BYTES,
        // Channel storage is lazy. At two bytes per legal PCM packet, the existing
        // byte budget bounds data events; leave room for seal/control events too.
        maxEvents = PREPARATION_MAX_EVENTS,
        onWork = onEncoderWork,
    )

    override fun onPcm(retainedPcm16: ByteArray) = worker.onPcm(retainedPcm16)
    override fun onCandidateDiscarded() = worker.onCandidateDiscarded()
    override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) = worker.onCaptureInvalidated(reason)

    /** WavEncoder's canonical mono16k PCM16 header is checked, never guessed. */
    suspend fun sealAfterCaptureJoined(wav: ByteArray): Content.SealedAudioEmbeddings =
        sealAfterCaptureJoined(wav, null, null).content

    data class CompletedCapture(val content: Content.SealedAudioEmbeddings, val timing: NativeAudioCaptureTiming?)

    suspend fun sealAfterCaptureJoined(wav: ByteArray, endpointDecisionAtNs: Long?,
        retainedPreRollSampleCount: Int?): CompletedCapture {
        check(wav.size > 44 && wav.size % 2 == 0) { "native_audio_invalid_capture_wav" }
        val pcm = wav.copyOfRange(44, wav.size)
        try {
            check(WavEncoder.pcm16Mono(pcm, 16_000).contentEquals(wav)) { "native_audio_noncanonical_capture_wav" }
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_capture" }
            val result = worker.sealAfterCaptureJoined(pcm)
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_capture" }
            return CompletedCapture(result.content, result.timing?.copy(
                endpointDecisionAtNs = endpointDecisionAtNs,
                retainedPreRollSampleCount = retainedPreRollSampleCount))
        } finally { pcm.fill(0) }
    }

    /** Capture has stopped feeding this exact worker at an explicit immutable boundary.
     * A sealed proposal alone never authorizes publication or replaces final capture. */
    suspend fun sealFrozenCandidate(proposal: NativePauseProposal): CompletedCapture {
        val pcm = proposal.pcm16()
        try {
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_proposal" }
            val result = worker.sealFrozenCandidate(pcm)
            check(valid.get() && turnContext.get()?.invoke() == true) { "native_audio_stale_proposal" }
            return CompletedCapture(result.content, result.timing)
        } finally { pcm.fill(0) }
    }

    /** Keep the turn artifact lease until final cleanup; only the candidate worker closes. */
    suspend fun drainFrozenWorker(): Boolean = worker.closeAndDrain()

    fun requestCancel() {
        valid.set(false); turnContext.set(null)
        cancelEncoderPreparation()
        worker.requestCancel()
    }

    /** Caller invokes under NonCancellable, then releases the artifact/model lease
     * only on true. False is permanent quarantine for this turn. */
    suspend fun closeAndDrain(releaseArtifact: Boolean = true): Boolean {
        requestCancel()
        val safe = worker.closeAndDrain()
        if (safe && releaseArtifact) artifact.close()
        return safe
    }

    private class EncoderAdapter : RetainedPcmEncoderWorker.Encoder<Content.SealedAudioEmbeddings> {
        lateinit var native: NativeAudioOwner
        override fun append(pcm: FloatArray) = native.appendOnWorker(pcm)
        override fun seal(): RetainedPcmEncoderWorker.Sealed<Content.SealedAudioEmbeddings> {
            val calledAt = System.nanoTime()
            val result = native.sealOnWorker()
            val returnedAt = System.nanoTime()
            val timing = result.timing?.takeIf { it.isBoundTo(result.provenance) }?.let {
                NativeAudioCaptureTiming(it, calledAt, returnedAt)
            }
            return RetainedPcmEncoderWorker.Sealed(result.provenance.pcmSampleCount, result.content, timing)
        }
        override fun requestCancel() { native.requestCancel() }
        override fun closeOnWorker(timeoutMs: Long) = native.closeOnWorker(timeoutMs)
    }

    companion object {
        internal const val PREPARATION_QUEUED_BYTES = 960_000
        internal const val PREPARATION_MAX_EVENTS = PREPARATION_QUEUED_BYTES / 2 + 2
        private val generations = AtomicLong()
    }
}
