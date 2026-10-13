package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.conversation.ConversationSessionState
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceActionCoordinator
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Joins exact native/speech children before releasing turn leases or resident call resources. */
internal class VoiceTurnFinalizer(
    private val call: VoiceCallAccess,
    private val conversation: VoiceConversationAccess,
    private val nativeState: ConversationSessionState,
    private val actions: AcceptedVoiceActionCoordinator,
    private val resources: RuntimeVoiceResources,
    private val memory: VoiceMemoryAccess,
    private val turnOrchestrator: TurnOrchestrator,
    private val typedInputs: VoiceTypedInputOwnership,
    private val recorder: DiagnosticRecorder
) {
    suspend fun close(request: VoiceTurnRequest, lifetime: VoiceTurnLifetime, cancelled: Boolean) = withContext(NonCancellable) {
        var encoderDrained = lifetime.nativeAudioCapture == null && !lifetime.captionNativeReleaseFailed
        lifetime.nativeSpeculation?.revoke(com.battlesbudz.jarvis.v2.voice.SpeculativeResponseCoordinator.Invalidation.STOP)
        lifetime.nativeAudioCapture?.requestCancel()
        if (cancelled && !actions.queue.hasUnfinished()) { conversation.job?.cancel(); conversation.job?.join() }
        try {
            lifetime.promotionLease.releaseIfUnadmitted(actions::releaseAcceptedVoiceLeaseIfIdle)
            typedInputs.relinquish(request.queuedTypedInput)
            lifetime.activePumpTypedInput.getAndSet(null)?.let(typedInputs::terminalize)
            runCatching { lifetime.capture?.stop() }
            runCatching { lifetime.microphone?.stop() }
            val speculationDrained = runCatching { lifetime.nativeSpeculation?.closeAndDrain() ?: true }.getOrDefault(false)
            val captureDrained = runCatching { lifetime.nativeAudioCapture?.closeAndDrain(releaseArtifact = speculationDrained) ?: true }.getOrDefault(false)
            encoderDrained = speculationDrained && captureDrained && !lifetime.captionNativeReleaseFailed
            if (encoderDrained) lifetime.nativeAudioArtifact?.close()
            lifetime.preparation?.close()
            if (!actions.queue.hasUnfinished()) nativeState.engine?.onPromptSubmitted = { _, _ -> }
        } finally {
            lifetime.speechChunks.close()
            runCatching { lifetime.output?.stopSpeaking() }
            lifetime.speechJob?.cancel()
            lifetime.speechJob?.join()
            runCatching { call.controller.flushCheckpoint() }
                .onFailure { recorder.recordImportant("Voice checkpoint flush failed: ${it.javaClass.simpleName}") }
            runCatching { lifetime.output?.release() }
            lifetime.answerMemoryBinding.getAndSet(null)?.let { binding ->
                binding.expiryJob.getAndSet(null)?.cancel()
                memory.delivery.binding.compareAndSet(binding, null)
            }
            lifetime.finalSpeechDelivery.get()?.let { turnOrchestrator.reconcileVoiceDelivery(it.deliveredText) }
            if (call.state.output === lifetime.output) call.state.output = null
            if (call.state.capture === lifetime.capture) call.state.capture = null
            if (!actions.queue.hasUnfinished()) call.state.acceptedSession = null
            val callEnded = !call.state.armed || call.controller.currentCallId() == null ||
                (lifetime.expectedResourceCall != null && call.controller.currentCallId() != lifetime.expectedResourceCall) ||
                lifetime.finalMessage.contains("turn failed", true)
            // Revoke immediately even when Gemma quarantine retains resident models.
            // The exact shadow owner/budget stays retained until its worker actually exits.
            if (callEnded) resources.smartTurn.closeCall(lifetime.expectedResourceCall)
            try {
                if (callEnded || VoiceSessionUi.paused.value || cancelled && !lifetime.preserveCaptureOnCancellation)
                    resources.resources.closeMicrophone()
                if (callEnded) {
                    LiveCallAudioEvidence.finish()
                    // A process-owned accepted native child must join before its resident models close.
                    if (!actions.queue.hasUnfinished() && encoderDrained && nativeState.engine?.nativeResourcesSafeToRelease != false)
                        resources.resources.closeModels()
                }
            } finally {
                // Accepted process work may already own the transferred lease and
                // its busy engine. That is not this turn's failed native borrower.
                lifetime.modelLease.finishNativeDrain(encoderDrained,
                    nativeState.engine?.nativeResourcesSafeToRelease != false) {
                    NativeVoiceQuarantine.retain(lifetime.modelLease, lifetime.nativeAudioCapture, nativeState.engine)
                    recorder.recordImportant("Native voice drain failed; model ownership retained, reuse disabled")
                }
            }
        }
    }
}
