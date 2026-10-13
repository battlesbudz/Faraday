package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.conversation.ConversationCallbacks
import com.battlesbudz.jarvis.v2.conversation.ConversationInvocation
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns FIFO final typed admission and joins its exact native child before model release/restart. */
internal class TypedVoiceInputStage(
    private val applicationScope: CoroutineScope,
    private val call: VoiceCallAccess,
    private val conversation: VoiceConversationAccess,
    private val conversationHistory: ConversationHistory,
    private val memory: VoiceMemoryAccess,
    private val createModelLease: () -> VoiceTurnModelLease,
    private val modelOperationActive: () -> Boolean,
    private val diagnosticRecorder: DiagnosticRecorder
) {
    fun start(input: CallFinalInput) {
        if (!call.state.typedTurnOwner.begin(input.id)) return
        // Every admitted input either completes or is restored and wakes its exact owner again.
        // This must be set before a lease check so a transient external model operation cannot
        // silently strand the FIFO entry.
        val restartAfterCompletion = java.util.concurrent.atomic.AtomicBoolean(true)
        call.state.turnJob = applicationScope.launch(Dispatchers.Default) {
            val modelLease = createModelLease()
            var invocation: Job? = null
            try {
                if (!call.state.armed || call.controller.currentCallId() != input.callId) return@launch
                if (!modelLease.acquireWhenIdle(ConversationWork.activeJobs.get() == 0)) {
                    if (!call.state.inputQueue.restore(input, call.controller.currentCallId())) {
                        check(call.state.pendingTypedHandoff.compareAndSet(null, input)) { "typed_call_handoff_slot_already_owned" }
                    }
                    call.events.post { call.events.report("Your typed Voice Call message is still queued until the current turn finishes.") }
                    return@launch
                }
                val replyId = "typed-" + input.id
                memory.capture(input.id, input.conversationId, input.callId, ConversationMemorySource.TEXT, input.text, input.capturedAtMs)
                val response = StringBuilder()
                if (!call.state.inputQueue.promote(input) {
                        call.state.preparingTypedInput.compareAndSet(input, null)
                        call.controller.appendTranscript("You", input.text, origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                        call.controller.beginReply(input.callId, replyId)
                        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.beginLiveMetrics(
                            com.battlesbudz.jarvis.v2.voice.LiveReplyMetrics(replyId, input.conversationId))
                        invocation = conversation.start(ConversationInvocation(
                            prompt = input.text,
                            history = conversationHistory.context(),
                            imageUri = null,
                            callOwned = true,
                            replyIdentity = replyId,
                            conversationIdentity = input.conversationId,
                            callIdentity = input.callId
                        ), ConversationCallbacks(
                            onToken = { token -> response.append(token); call.controller.updateReplyText(input.callId, replyId, response.toString()) },
                            onComplete = { answer -> call.controller.updateReplyText(input.callId, replyId, answer, finished = true) },
                            onLiveInference = { submittedAt, firstTokenAt, tokensPerSecond, durable ->
                                call.controller.updateReplyMetrics(input.callId, replyId, durable = durable) { current ->
                                    var updated = current
                                    submittedAt?.let { updated = updated.submitted(it) }
                                    firstTokenAt?.let { updated = updated.firstRawToken(it) }
                                    if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                                    updated
                                }
                                com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.updateLiveMetrics(replyId, input.conversationId) { metrics ->
                                    var updated = metrics
                                    submittedAt?.let { updated = updated.submitted(it) }
                                    firstTokenAt?.let { updated = updated.firstText(it) }
                                    if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                                    updated
                                }
                            }
                        ))
                    }) return@launch
                invocation?.join()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Pause/microphone cancellation retains an unpromoted typed final for this call;
                // End has already drained it and wins the visible terminal receipt.
                if (call.state.inputQueue.restore(input, call.controller.currentCallId())) restartAfterCompletion.set(true)
                else if (call.state.inputQueue.terminalize(input)) {
                    call.controller.recordTerminalInputForCall(input.callId, input.id,
                        "Cancelled before processing typed message: ${input.text}")
                }
                throw cancelled
            } catch (error: Throwable) {
                diagnosticRecorder.recordImportant("Typed Voice Call turn failed: ${error.javaClass.simpleName}")
                // If End already won, its queue drain owns the one visible receipt. Otherwise
                // this exact claimed input becomes terminal here and can never be replayed.
                val terminalized = call.state.inputQueue.terminalize(input)
                call.events.post {
                    if (terminalized) call.controller.recordTerminalInputForCall(input.callId, input.id,
                        "Cancelled before processing typed message: ${input.text}")
                    call.controller.updateReplyText(input.callId, "typed-" + input.id,
                        "The typed Voice Call message was interrupted before completion.", finished = true)
                    call.events.report("Typed Voice Call message interrupted; it was not replayed.")
                }
            } finally {
                call.state.typedTurnOwner.finish(input.id, invocation) {
                    modelLease.close()
                }
            }
        }.also { ownerJob ->
            ownerJob.invokeOnCompletion {
                call.state.typedTurnOwner.restartAfterOwnerCompletion(input.id) {
                    if (restartAfterCompletion.get()) applicationScope.launch {
                        // Do not rely on a second user tap: wait for the prior exclusive owner to
                        // leave, then invoke only after this outer typed owner has completed.
                        while (call.state.armed && call.controller.currentCallId() == input.callId &&
                            (ConversationWork.activeJobs.get() != 0 || modelOperationActive())) {
                            kotlinx.coroutines.delay(50)
                        }
                        call.events.post {
                            if (call.state.armed && call.controller.currentCallId() == input.callId) call.events.restartTurn()
                        }
                    }
                }
            }
        }
    }
}
