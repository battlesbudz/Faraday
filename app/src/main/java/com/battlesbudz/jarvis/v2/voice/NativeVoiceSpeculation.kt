package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope

/** Read-only prompt evidence. Final context adoption and assembled prompt remain authoritative. */
internal data class NativeVoicePromptPreview(val exactPrompt: String, val isCurrent: () -> Boolean)

/** Native operations stay on one owner; tests can prove ordering without loading weights. */
internal interface NativeSpeculationDriver {
    suspend fun generate(proposal: NativePauseProposal, exactPrompt: String, onToken: (String) -> Unit,
                         onProgress: (com.battlesbudz.jarvis.v2.ai.InferenceProgress) -> Unit): GenerationResult
    suspend fun rollback()
    fun requestCancel()
    fun safeToRelease(): Boolean
    fun timing(): NativeAudioCaptureTiming?
}

/**
 * One native pause candidate under the existing voice/model lease. An invalidated
 * consumed encoder is never resumed: the confirmed path uses its unchanged full
 * recording through ordinary audio encoding, after this owner has drained.
 */
internal class NativeVoiceSpeculation(
    scope: CoroutineScope,
    private val callId: String,
    private val turnId: String,
    private val generation: Long,
    private val driver: NativeSpeculationDriver,
    private val preview: NativeVoicePromptPreview,
    private val admissible: () -> Boolean,
    private val ownerIsCurrent: () -> Boolean,
    private val observe: (String) -> Unit = {},
) : NativePauseObserver {
    private fun event(message: String) { runCatching { observe(message) } }
    private val timingGate = Any()
    private var submittedAtMs: Long? = null
    private var firstRawTokenAtMs: Long? = null
    private var progressPromoted = false
    private var progressSink: ((com.battlesbudz.jarvis.v2.ai.InferenceProgress) -> Unit)? = null
    private val recordProgress: (com.battlesbudz.jarvis.v2.ai.InferenceProgress) -> Unit = { progress ->
        synchronized(timingGate) {
            progress.submittedAtMs?.let { submittedAtMs = submittedAtMs ?: it }
            progress.firstRawTokenAtMs?.let { firstRawTokenAtMs = firstRawTokenAtMs ?: it }
            if (progressPromoted) progressSink?.invoke(progress)
        }
    }
    private val proposed = AtomicReference<NativePauseProposal?>(null)
    private val certified = AtomicReference<NativePauseCertificate?>(null)
    private val revoked = AtomicBoolean(false)
    private val coordinator = SpeculativeResponseCoordinator(scope,
        generate = { proposal: NativePauseProposal, onToken ->
            driver.generate(proposal, preview.exactPrompt, onToken, recordProgress)
        }, rollback = driver::rollback, nativeSafeToRelease = driver::safeToRelease,
        ownerIsCurrent = { !revoked.get() && ownerIsCurrent() && preview.isCurrent() },
        budget = SpeculativeResponseCoordinator.Budget(maxAttempts = 1), observe = observe)

    val consumedEncoder: Boolean get() = proposed.get() != null
    val promoted: Boolean get() = promotedFlag.get()
    private val promotedFlag = AtomicBoolean(false)
    private val timingPublished = AtomicBoolean(false)
    private val firstReleasedToken = AtomicBoolean(false)
    @Volatile private var confirmedTimingSink: (NativeAudioCaptureTiming) -> Unit = {}

    override fun onProposal(proposal: NativePauseProposal): Boolean {
        if (proposal.turnId != turnId || proposal.generation != generation ||
            revoked.get() || !admissible() || !ownerIsCurrent() || !preview.isCurrent()) return false
        if (!proposed.compareAndSet(null, proposal)) return false
        val pcm = proposal.pcm16()
        val accepted = try {
            coordinator.propose(SpeculativeInputIdentity.audio(callId, turnId, generation, preview.exactPrompt, pcm), proposal)
        } finally { pcm.fill(0) }
        if (!accepted) proposed.compareAndSet(proposal, null)
        else event("native_speculation_proposed samples=${proposal.sampleCount} policy=${proposal.inputPolicyVersion}")
        return accepted
    }

    override fun onInvalidated(reason: NativePauseInvalidation) {
        revoke(SpeculativeResponseCoordinator.Invalidation.INPUT_CHANGED)
        event("native_speculation_invalidated reason=${reason.name.lowercase()}")
    }

    fun revoke(reason: SpeculativeResponseCoordinator.Invalidation) {
        revoked.set(true)
        certified.set(null)
        coordinator.invalidate(reason)
        if (consumedEncoder) driver.requestCancel()
    }

    /** Called only after capture.stop joined. Retains the complete recording for every fallback. */
    fun confirmCapture(certificate: NativePauseCertificate?, fullWav: ByteArray): Boolean {
        val candidate = proposed.get()
        if (candidate == null) return false
        val valid = !revoked.get() && ownerIsCurrent() && preview.isCurrent() &&
            certificate?.proposal === candidate && certificate.matchesCompleteWav(fullWav)
        if (!valid) { revoke(SpeculativeResponseCoordinator.Invalidation.INPUT_CHANGED); return false }
        certified.set(certificate)
        event("native_speculation_endpoint_confirmed excludedSamples=${certificate.excludedSampleCount}")
        return true
    }

    /** Ordinary context/native mutations invalidate first and wait for checked rollback. */
    suspend fun beforeNativeMutation() {
        if (!consumedEncoder) return
        if (!promoted) event("native_speculation_fallback reason=native_context_mutation_or_input_rejection")
        revoke(SpeculativeResponseCoordinator.Invalidation.INPUT_CHANGED)
        coordinator.awaitIdle()
    }

    suspend fun promote(exactFinalPrompt: String, ordinaryReplyAccepted: Boolean,
                        onToken: (String) -> Unit): GenerationResult? {
        val certificate = certified.get()
        if (certificate == null) { beforeNativeMutation(); return null }
        val pcm = certificate.proposal.pcm16()
        val identity = try { SpeculativeInputIdentity.audio(callId, turnId, generation, exactFinalPrompt, pcm) }
            finally { pcm.fill(0) }
        val result = coordinator.promote(identity, endpointConfirmed = !revoked.get(),
            ordinaryReplyAccepted = ordinaryReplyAccepted && preview.isCurrent(), onToken = { token ->
                // No candidate receipt is attributed to the actual answer until
                // an exact-match promoted token is released. Every null-result
                // full-recording fallback therefore retains its own provenance.
                if (token.isNotEmpty()) {
                    synchronized(timingGate) {
                        if (!progressPromoted) {
                            progressPromoted = true
                            progressSink?.invoke(com.battlesbudz.jarvis.v2.ai.InferenceProgress(submittedAtMs, firstRawTokenAtMs))
                        }
                    }
                    confirmedTiming()?.let { timing ->
                        if (timingPublished.compareAndSet(false, true)) confirmedTimingSink(timing)
                    }
                }
                onToken(token)
                if (token.isNotEmpty() && firstReleasedToken.compareAndSet(false, true))
                    event("native_speculation_first_released_token")
            })
        promotedFlag.set(result != null)
        event(if (result != null) "native_speculation_reused" else "native_speculation_fallback reason=final_prompt_or_generation_rejected")
        return result
    }

    /** Preserve launch-time native timing when ordinary telemetry attaches after confirmation. */
    fun bindProgress(sink: (com.battlesbudz.jarvis.v2.ai.InferenceProgress) -> Unit) = synchronized(timingGate) {
        progressSink = sink
        if (progressPromoted) sink(com.battlesbudz.jarvis.v2.ai.InferenceProgress(submittedAtMs, firstRawTokenAtMs))
    }

    fun bindConfirmedTiming(sink: (NativeAudioCaptureTiming) -> Unit) { confirmedTimingSink = sink }

    private fun confirmedTiming(): NativeAudioCaptureTiming? = certified.get()?.let { certificate ->
        driver.timing()?.copy(endpointDecisionAtNs = certificate.endpointDecisionAtNs)
    }

    suspend fun closeAndDrain(): Boolean {
        // No accepted proposal means this coordinator has never borrowed encoder/model.
        if (!consumedEncoder) return true
        return coordinator.closeAndDrain()
    }
}
