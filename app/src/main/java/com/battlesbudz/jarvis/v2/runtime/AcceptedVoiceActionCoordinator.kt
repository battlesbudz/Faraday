package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.AcceptedActionLease
import com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue
import com.battlesbudz.jarvis.v2.actions.AcceptedActionState
import com.battlesbudz.jarvis.v2.actions.AcceptedActionTask
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.voice.ContinuousActionSession
import com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome
import com.battlesbudz.jarvis.v2.voice.VoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceTaskState
import com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Frozen final ASR evidence; this is scheduled work, not a second action executor. */
internal data class AcceptedVoiceInvocation(
    val callId: String,
    val replyId: String,
    val utteranceId: String,
    val prompt: String,
    val history: List<ChatEntry>,
    val plan: ActionTurnPlan.Ready,
    val admittedAtMonotonicMs: Long = System.nanoTime() / 1_000_000
)

/** The worker receives only the immutable authorized request and its result callbacks. */
internal fun interface AcceptedActionConversation {
    fun start(invocation: AcceptedVoiceInvocation, benchmark: PipelineBenchmarkCapture,
              callbacks: AcceptedActionCallbacks): Job?
}

internal data class AcceptedActionCallbacks(
    val onComplete: (String) -> Unit,
    val onActionResult: (String, String, Boolean) -> Unit,
    val onLiveInference: (Long?, Long?, Double?, Boolean) -> Unit,
    val onPhonePlanFinished: (ActionTurnRunner.Outcome) -> Unit
)

/**
 * Owns the process-lifetime accepted-action queue, one model lease per FIFO batch, and
 * saved-ID terminal receipts. Ending microphone/TTS delivery does not end this worker.
 * The live call pump may inspect [queue] but must not release the worker's lease itself.
 */
internal class AcceptedVoiceActionCoordinator(
    private val scope: CoroutineScope,
    private val acquireModelLease: () -> Boolean,
    private val releaseModelLease: () -> Unit,
    private val controllerProvider: () -> VoiceSessionController,
    private val callStoreProvider: () -> VoiceCallStore,
    private val benchmarkStoreProvider: () -> AndroidPipelineBenchmarkStore,
    private val newBenchmark: (String, String) -> PipelineBenchmarkCapture,
    private val finishBenchmarkResources: (PipelineBenchmarkCapture) -> Unit,
    private val bindPromptRecorder: (AcceptedActionTask<AcceptedVoiceInvocation>) -> Unit,
    private val recordDiagnostic: (String) -> Unit,
    private val conversation: AcceptedActionConversation
) {
    val queue = AcceptedActionQueue<AcceptedVoiceInvocation>(scope = scope)
    private val lease = AcceptedActionLease()
    private val controller get() = controllerProvider()
    private val callStore get() = callStoreProvider()
    private val benchmarkStore get() = benchmarkStoreProvider()

    fun observeTerminalReports() {
        // Persist terminal reply evidence even after End Call detached its audio session. Waiting
        // for queue idle means active cancellation has joined its exact native child first.
        scope.launch {
            queue.events.collect { event ->
                if (event.task.state in setOf(AcceptedActionState.COMPLETED,
                        AcceptedActionState.FAILED,
                        AcceptedActionState.CANCELLED,
                        AcceptedActionState.INTERRUPTED)) {
                    scope.launch {
                        queue.awaitIdle()
                        queue.tasks.value.forEach { terminal ->
                            if (terminal.state in setOf(AcceptedActionState.COMPLETED,
                                    AcceptedActionState.FAILED,
                                    AcceptedActionState.CANCELLED,
                                    AcceptedActionState.INTERRUPTED)) {
                                persistTerminalActionReply(terminal)
                                val benchmarkId = "${terminal.value.replyId}:action"
                                if (terminal.id == event.task.id && benchmarkStore.samples.value.none { it.turnId == benchmarkId }) {
                                    val receipt = newBenchmark(benchmarkId, "accepted_action_terminal")
                                    receipt.configuration("utterance_id", terminal.value.utteranceId)
                                    receipt.configuration("request_scope", "terminal_receipt_execution_timings_unavailable")
                                    val outcome = when (terminal.state) {
                                        AcceptedActionState.COMPLETED -> PipelineBenchmarkOutcome.COMPLETE
                                        AcceptedActionState.CANCELLED,
                                        AcceptedActionState.INTERRUPTED -> PipelineBenchmarkOutcome.CANCELLED
                                        else -> PipelineBenchmarkOutcome.ERROR
                                    }
                                    receipt.finish(outcome, terminal.value.callId)?.let { sample ->
                                        benchmarkStore.append(sample.copy(stageOffsetsMs = emptyMap(),
                                            observedMetrics = mapOf("admission_to_terminal_observation_ms" to
                                                (System.nanoTime() / 1_000_000 - terminal.value.admittedAtMonotonicMs).coerceAtLeast(0).toDouble())))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Schedules an already parsed final voice action. The callback uses saved call/reply IDs so a
     * result remains durable after an End Call UI transition and can never attach to a newer call.
     */
    fun enqueueAcceptedVoiceAction(
        actionSession: ContinuousActionSession<AcceptedVoiceInvocation>,
        invocation: AcceptedVoiceInvocation
    ): Boolean {
        // Every admission advances a shared generation. An older idle observer may release only
        // if no later admission has occurred while it was suspended in awaitIdle().
        val leaseGeneration = retainAcceptedVoiceLease() ?: return false
        // Reserve this task's eventual terminal report before admission. The reservation survives
        // queue completion until a real playback delivery acknowledges it.
        if (!actionSession.reserveActionAdmission(invocation.replyId)) {
            releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
            return false
        }
        val admitted = queue.admit(invocation.replyId, invocation.utteranceId, invocation)
        if (admitted == null || admitted.id != invocation.replyId) {
            actionSession.abandonActionAdmission(invocation.replyId)
            releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
            return false
        }
        queue.start { task ->
            val accepted = task.value
            val taskBenchmark = newBenchmark("${accepted.replyId}:action", "accepted_action")
            taskBenchmark.configuration("reply_id", accepted.replyId)
            taskBenchmark.configuration("utterance_id", accepted.utteranceId)
            taskBenchmark.configuration("request_scope", "accepted_executor_task_excludes_capture_and_report_playback")
            taskBenchmark.metric("accepted_queue_wait_ms", (System.nanoTime() / 1_000_000 - accepted.admittedAtMonotonicMs).coerceAtLeast(0))
            val planned = accepted.plan.steps.map { it.request.name }
            val completedSteps = mutableListOf<String>()
            var allSucceeded = true
            var validatedOutcome: ActionTurnRunner.Outcome? = null
            controller.updateTaskForCall(accepted.callId, VoiceTaskStatus(
                VoiceTaskState.WAITING_FOR_USER,
                pendingSteps = planned
            ))
            // The action-mode admission transferred one lease for the complete FIFO batch.
            check(leaseActive()) { "accepted_action_batch_missing_model_lease" }
            // This callback is installed only by the serial queue worker. It prevents a queued
            // task from inheriting the initial voice turn's diagnostic attribution.
            bindPromptRecorder(task)
            val completed = CompletableDeferred<String>()
            val job = conversation.start(accepted, taskBenchmark, AcceptedActionCallbacks(
                onComplete = { completed.complete(it) },
                onActionResult = { name, message, succeeded ->
                    allSucceeded = allSucceeded && succeeded
                    completedSteps += name
                    controller.recordReplyAction(accepted.callId, accepted.replyId,
                        VoiceActionOutcome(name, message, succeeded))
                    controller.updateTaskForCall(accepted.callId, VoiceTaskStatus(
                        if (succeeded) VoiceTaskState.WAITING_FOR_USER
                        else VoiceTaskState.FAILED,
                        completedSteps.toList(), planned.drop(completedSteps.size)
                    ))
                },
                onLiveInference = { submittedAt, firstTokenAt, tokensPerSecond, durable ->
                    controller.updateReplyMetrics(accepted.callId, accepted.replyId, durable = durable) { current ->
                        var updated = current
                        submittedAt?.let { updated = updated.submitted(it) }
                        firstTokenAt?.let { updated = updated.firstRawToken(it) }
                        if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                        updated
                    }
                },
                onPhonePlanFinished = { outcome ->
                    validatedOutcome = outcome
                    controller.updateTerminalReplyTextForCall(accepted.callId, accepted.replyId, outcome.message)
                }
            ))
            if (job == null) {
                finishBenchmarkResources(taskBenchmark)
                taskBenchmark.finish(PipelineBenchmarkOutcome.REJECTED,
                    accepted.callId, "accepted_invocation_rejected")?.let { benchmarkStore.append(it) }
                val terminal = "Failed; unattempted: ${planned.joinToString()}."
                controller.updateTaskForCall(accepted.callId, VoiceTaskStatus(
                    VoiceTaskState.FAILED, emptyList(), planned
                ))
                controller.updateTerminalReplyTextForCall(accepted.callId, accepted.replyId, terminal)
                return@start false
            }
            // The exact invocation handle, not mutable conversationJob, is the queue completion
            // boundary. Cancellation joins this handle before the next accepted task begins.
            try {
                completed.await()
                job.join()
                validatedOutcome?.completed ?: (completedSteps.size == planned.size && allSucceeded)
            } finally {
                val cancelledBeforeCleanup = !kotlin.coroutines.coroutineContext.isActive
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!job.isCompleted) job.cancel()
                    job.join()
                    val state = when {
                        (validatedOutcome?.completed ?: (completedSteps.size == planned.size && allSucceeded)) -> VoiceTaskState.COMPLETED
                        cancelledBeforeCleanup -> VoiceTaskState.CANCELLED
                        else -> VoiceTaskState.FAILED
                    }
                    val remaining = if (validatedOutcome?.completed == true) emptyList() else planned.drop(completedSteps.size)
                    controller.updateTaskForCall(accepted.callId, VoiceTaskStatus(
                        state, completedSteps.toList(), remaining
                    ))
                    val terminal = when (state) {
                        VoiceTaskState.COMPLETED -> ""
                        VoiceTaskState.CANCELLED -> "Cancelled; unattempted: ${remaining.joinToString()}."
                        else -> "Failed; unattempted: ${remaining.joinToString()}."
                    }
                    val receipts = acceptedVoiceSummary(accepted.callId, setOf(accepted.replyId))
                    controller.updateTerminalReplyTextForCall(accepted.callId, accepted.replyId,
                        listOf(receipts, terminal).filter { it.isNotBlank() }.joinToString(" "))
                    finishBenchmarkResources(taskBenchmark)
                    val benchmarkState = when (state) {
                        VoiceTaskState.COMPLETED -> PipelineBenchmarkOutcome.COMPLETE
                        VoiceTaskState.CANCELLED -> PipelineBenchmarkOutcome.CANCELLED
                        else -> PipelineBenchmarkOutcome.ERROR
                    }
                    taskBenchmark.finish(benchmarkState, accepted.callId)?.let { benchmarkStore.append(it) }
                }
            }
        }
        releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
        return true
    }

    /** Retain or transfer a batch lease and publish a generation for its idle observer. */
    fun retainAcceptedVoiceLease(): Long? = lease.retain(acquireModelLease)

    /** Called only while the initial voice turn already owns ModelStore's lease. */
    fun transferAcceptedVoiceLease(): Long = lease.transfer()

    private fun leaseActive(): Boolean = lease.active()

    private fun releaseAcceptedVoiceLeaseAfterIdle(generation: Long) {
        scope.launch {
            queue.awaitIdle()
            if (lease.releaseIfCurrent(generation, !queue.hasUnfinished())) {
                releaseModelLease()
            }
        }
    }

    /** Failed admission after a transferred initial lease has no task to drain. */
    fun releaseAcceptedVoiceLeaseIfIdle() =
        releaseAcceptedVoiceLeaseAfterIdle(lease.generation())

    fun publishTerminalActionReports(
        session: ContinuousActionSession<AcceptedVoiceInvocation>, callId: String
    ) {
        queue.tasks.value.filter { it.value.callId == callId && it.state.isTerminalActionState() && session.ownsActionTask(it.id) }
            .forEach { task ->
                val terminalText = persistTerminalActionReply(task)
                if (!session.onTaskEvent(task.id, terminalText))
                    recordDiagnostic("Accepted terminal report remains durable pending retry task=${task.id}")
            }
    }

    /** Saved-ID terminal text is authoritative even when no voice session remains to speak it. */
    private fun persistTerminalActionReply(
        task: AcceptedActionTask<AcceptedVoiceInvocation>
    ): String {
        val invocation = task.value
        val callId = invocation.callId
        val receipts = acceptedVoiceSummary(callId, setOf(invocation.replyId))
        val completed = callStore.list().firstOrNull { it.id == callId }?.transcript
            ?.firstOrNull { it.replyId == invocation.replyId }?.actions.orEmpty()
        val remaining = if (task.state == AcceptedActionState.COMPLETED) emptyList()
            else invocation.plan.steps.map { it.request.name }.drop(completed.size)
        val terminal = when (task.state) {
            AcceptedActionState.CANCELLED -> "Cancelled; unattempted: ${remaining.joinToString()}."
            AcceptedActionState.INTERRUPTED -> "Interrupted; unattempted: ${remaining.joinToString()}."
            AcceptedActionState.FAILED -> "Failed; unattempted: ${remaining.joinToString()}."
            else -> ""
        }
        val terminalState = when (task.state) {
            AcceptedActionState.COMPLETED -> VoiceTaskState.COMPLETED
            AcceptedActionState.CANCELLED,
            AcceptedActionState.INTERRUPTED -> VoiceTaskState.CANCELLED
            else -> VoiceTaskState.FAILED
        }
        val terminalText = listOf(receipts, terminal).filter { it.isNotBlank() }.joinToString(" ")
        controller.updateTaskForCall(callId, VoiceTaskStatus(
            terminalState, completed.map { it.name }, remaining
        ))
        controller.updateTerminalReplyTextForCall(callId, invocation.replyId, terminalText)
        return terminalText
    }

    private fun AcceptedActionState.isTerminalActionState(): Boolean =
        this in setOf(AcceptedActionState.COMPLETED,
            AcceptedActionState.FAILED,
            AcceptedActionState.CANCELLED,
            AcceptedActionState.INTERRUPTED)

    fun acceptedVoiceSummary(callId: String, replyIds: Set<String>, includeTerminalText: Boolean = false): String {
        if (includeTerminalText) return callStore.list().firstOrNull { it.id == callId }?.transcript
            ?.filter { it.replyId in replyIds && it.generationComplete && it.text.isNotBlank() }
            ?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
            ?: "I couldn't complete the accepted phone action."
        val outcomes = callStore.list().firstOrNull { it.id == callId }?.transcript
            ?.filter { it.replyId in replyIds }?.flatMap { it.actions }.orEmpty()
        return outcomes.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.message }
            ?: callStore.list().firstOrNull { it.id == callId }?.transcript
                ?.filter { it.replyId in replyIds && it.generationComplete && it.text.isNotBlank() }
                ?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
            ?: "I couldn't complete the accepted phone action."
    }

}
