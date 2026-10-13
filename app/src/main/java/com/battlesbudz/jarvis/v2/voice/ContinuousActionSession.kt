package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** A stable final ASR result. The runtime supplies the ID from its capture source. */
data class SessionCapture(
    val utteranceId: String,
    val text: String,
    val recognitionIssue: String? = null,
    val wav: ByteArray = byteArrayOf(),
    val audioIsComplete: Boolean = true,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val origin: TranscriptOrigin = TranscriptOrigin.SPOKEN
)

/** An executor-derived terminal result that still needs an audible report. */
data class PendingReport(val taskId: String, val text: String)

/**
 * A single speech attempt. Its reports remain pending until this exact attempt is confirmed by
 * [ContinuousActionSession.markDelivered].
 */
data class ReportDelivery(val attemptId: Long, val reports: List<PendingReport>)

/** Result of the runtime's existing final-turn classification. This class does not authorize tools. */
sealed interface CapturedKind {
    data object AcceptedAction : CapturedKind
    data object RejectedAction : CapturedKind
    data object Ordinary : CapturedKind
    data class Control(val value: VoiceActionControl) : CapturedKind
}

/** What the runtime should do with a completed ASR turn. */
sealed interface CaptureOutcome {
    data object Duplicate : CaptureOutcome
    data class RecognitionIssue(val capture: SessionCapture) : CaptureOutcome
    data class AcceptedAction(val capture: SessionCapture) : CaptureOutcome
    data class RejectedAction(val capture: SessionCapture) : CaptureOutcome
    data class Control(val capture: SessionCapture, val value: VoiceActionControl) : CaptureOutcome
    /** Hold this one ordinary turn until accepted work drains; it is never sent to the busy model. */
    data class DeferredConversation(val capture: SessionCapture) : CaptureOutcome
    /** The caller may start its ordinary conversation path now. */
    data class ConversationReady(val capture: SessionCapture) : CaptureOutcome
    /** One ordinary follow-up is already retained, so the caller can give a bounded local reply. */
    data class ConversationBusy(val capture: SessionCapture) : CaptureOutcome
}

/** Persistent pump event selection. It deliberately does not cancel losing deferreds: the
 * runtime owns their lifetime so a typed cancellation cannot discard an in-progress ASR floor. */
enum class ActionPumpEvent { CAPTURED, DELIVERY_READY, WORKER_IDLE, TYPED_AVAILABLE }
suspend fun awaitActionPumpEvent(
    followup: Deferred<CapturedVoiceTurn>,
    deliveryReady: Deferred<Unit>,
    workerIdle: Deferred<Unit>?,
    typedAvailable: Deferred<Unit>,
    onCaptured: (CapturedVoiceTurn) -> Unit
): ActionPumpEvent = select {
    followup.onAwait { capture -> onCaptured(capture); ActionPumpEvent.CAPTURED }
    deliveryReady.onAwait { ActionPumpEvent.DELIVERY_READY }
    workerIdle?.onAwait { ActionPumpEvent.WORKER_IDLE }
    typedAvailable.onAwait { ActionPumpEvent.TYPED_AVAILABLE }
}

/**
 * Call-owned capture/report state for continuous accepted actions.
 *
 * The queue remains the only owner of accepted task execution. This coordinator deliberately has
 * no microphone, TTS, model, or Android dependency: the host feeds capture boundaries and terminal
 * task receipts into it, then performs the actual ASR/TTS work around the returned tokens.
 */
class ContinuousActionSession<T>(
    private val queue: AcceptedActionQueue<T>,
    private val captureHistoryCapacity: Int = 32,
    private val reportCapacity: Int = 3,
    /**
     * M1d explicit silent work (D21/T05). When present, final spoken captures
     * are classified through it: ordinary speech is ignored while silently
     * working, the wake phrase reopens conversation, and stop controls always
     * pass through. Accepted tasks continue untouched in every case.
     */
    private val silentWork: SilentWorkController? = null,
    /**
     * M1d: fired when the wake phrase exits silent work inside the capture
     * session, so the runtime can sync its observable posture (e.g. the UI
     * toggle) with the controller's.
     */
    private val onSilentWorkExit: () -> Unit = {}
) {
    init {
        require(captureHistoryCapacity > 0) { "captureHistoryCapacity must be positive" }
        require(reportCapacity > 0) { "reportCapacity must be positive" }
    }

    private val lock = Any()
    private val captureIds = ArrayDeque<String>()
    private val knownCaptureIds = linkedSetOf<String>()
    private val pendingReports = ArrayDeque<PendingReport>()
    /** One slot per accepted task, retained through terminal relay and real playback acknowledgement. */
    private val reservedReportTaskIds = linkedSetOf<String>()
    private val knownReportTaskIds = linkedSetOf<String>()
    private val deliveredReportTaskIds = linkedSetOf<String>()
    /** One bounded, visible local clarification may await the next result delivery. */
    private val localFeedbackIds = linkedSetOf<String>()
    private var nextLocalFeedbackId = 1L
    private val _revision = MutableStateFlow(0L)

    private var captureInProgress = false
    private var captureDetached = false
    private var detached = false
    private var deferredConversation: SessionCapture? = null
    private var delivery: ReportDelivery? = null
    private var nextAttemptId = 1L

    /** True only after a user floor handoff interrupted an audible delivery attempt. */
    var speechDetached: Boolean = false
        private set

    /**
     * Call immediately when real user speech has started. It ends an in-flight speech attempt but
     * retains every pending outcome. It does not cancel ASR or accepted work.
     */
    fun onCaptureStarted() {
        synchronized(lock) {
            if (detached || captureDetached) return
            captureInProgress = true
            interruptDeliveryLocked(null)
            changedLocked()
        }
    }

    /**
     * Consume one final capture after the runtime has applied its existing final classifier.
     * Stable IDs deduplicate callbacks independently of text. A recognition issue ends the floor
     * but never becomes a conversation/action request.
     */
    fun onCaptured(capture: SessionCapture, kind: CapturedKind): CaptureOutcome =
        onCapturedInternal(capture, kind, applySilentGate = true)

    private fun onCapturedInternal(
        capture: SessionCapture,
        kind: CapturedKind,
        applySilentGate: Boolean
    ): CaptureOutcome = synchronized(lock) {
        if (detached) return CaptureOutcome.Duplicate
        captureInProgress = false
        if (!rememberCaptureLocked(capture.utteranceId)) {
            changedLocked()
            return CaptureOutcome.Duplicate
        }
        if (capture.recognitionIssue != null) {
            changedLocked()
            return CaptureOutcome.RecognitionIssue(capture)
        }
        // M1d: explicit silent work ignores ordinary speech until the wake
        // phrase. Control kinds always pass through; the wake utterance itself
        // is handled as ordinary conversation once silence is exited.
        if (applySilentGate && kind !is CapturedKind.Control) {
            when (silentWork?.classify(capture.text)) {
                null, SilentSpeechDecision.Answer -> Unit
                SilentSpeechDecision.Ignored -> {
                    changedLocked()
                    return CaptureOutcome.Duplicate
                }
                SilentSpeechDecision.Wake -> {
                    silentWork?.exitSilentWork()
                    onSilentWorkExit()
                }
                SilentSpeechDecision.Control -> Unit
            }
        }
        val outcome = when (kind) {
            CapturedKind.AcceptedAction -> CaptureOutcome.AcceptedAction(capture)
            CapturedKind.RejectedAction -> CaptureOutcome.RejectedAction(capture)
            CapturedKind.Ordinary -> {
                // Never replace an earlier bounded ordinary turn merely because the
                // action worker went idle between ASR callbacks. Preserve the first
                // complete WAV/identity and make the later request explicitly busy.
                if (deferredConversation != null) CaptureOutcome.ConversationBusy(capture)
                // A terminal executor report is still owed even after the worker becomes idle.
                // Retain this ordinary final turn until that report has a real delivery outcome.
                else if (!queue.hasUnfinished() && reservedReportTaskIds.isEmpty() &&
                    pendingReports.isEmpty() && delivery == null) CaptureOutcome.ConversationReady(capture)
                else {
                    deferredConversation = capture
                    CaptureOutcome.DeferredConversation(capture)
                }
            }
            is CapturedKind.Control -> CaptureOutcome.Control(capture, kind.value)
        }
        changedLocked()
        outcome
    }

    /**
     * Classify a final typed call message through the same action/control/deferred queue without
     * stealing an already-confirmed spoken floor. Typed input is deliberate, so the silent-work
     * speech gate does not apply. The runtime owns the actual queue admission.
     */
    fun onTyped(capture: SessionCapture, kind: CapturedKind): CaptureOutcome = synchronized(lock) {
        val spokenFloorWasActive = captureInProgress
        val outcome = onCapturedInternal(capture, kind, applySilentGate = false)
        captureInProgress = spokenFloorWasActive
        outcome
    }

    /** An ASR cancellation/failure ends the floor while retaining any deferred conversational turn. */
    fun onCaptureStopped() {
        synchronized(lock) {
            if (detached) return
            captureInProgress = false
            changedLocked()
        }
    }

    /**
     * Retain one ordinary capture until the queue is actually idle. The caller should call this
     * after its queue-idle barrier, before restarting normal model ownership.
     */
    fun hasDeferredConversation(): Boolean = synchronized(lock) { deferredConversation != null }

    /** Atomically hand off only when no confirmed ASR floor can race the idle transition. */
    fun takeDeferredConversationIfNoCapture(): SessionCapture? = synchronized(lock) {
        if (captureInProgress || detached || queue.hasUnfinished()) null else deferredConversation.also {
            deferredConversation = null
            if (it != null) changedLocked()
        }
    }

    fun takeDeferredConversation(): SessionCapture? = synchronized(lock) {
        if (detached || queue.hasUnfinished()) return null
        deferredConversation.also {
            deferredConversation = null
            if (it != null) changedLocked()
        }
    }

    /** End Call makes an unprocessed ordinary follow-up visible instead of silently discarding it. */
    fun cancelDeferredConversation(): SessionCapture? = synchronized(lock) {
        deferredConversation.also { deferredConversation = null; if (it != null) changedLocked() }
    }

    /** Classifies only. The runtime delegates explicit queue cancellation with its saved call ID. */
    fun control(text: String, hasUnfinished: Boolean = queue.hasUnfinished()): VoiceActionControl =
        VoiceActionControl.parse(text, hasUnfinished)

    /**
     * Atomically reserve an eventual report before the runtime calls [AcceptedActionQueue.admit]
     * with this same stable task ID. The reservation survives QUEUED/RUNNING/terminal transitions
     * and is released only by [markDelivered], so a delayed terminal-event callback cannot create
     * a report-capacity hole.
     *
     * The invariant is `accepted task reservations + unreserved restored reports <= reportCapacity`.
     * A false result means the final capture must receive a bounded local busy/retry response; it
     * must not be admitted and allowed to lose its eventual success, failure, cancellation, or
     * unattempted-step summary. If queue admission fails, call [abandonActionAdmission]. If its
     * utterance-ID dedupe returns another task ID, abandon this proposed ID and retain the original.
     */
    fun reserveActionAdmission(taskId: String): Boolean = synchronized(lock) {
        if (detached) return false
        if (taskId in reservedReportTaskIds) return true
        if (outstandingReportObligationsLocked() >= reportCapacity) return false
        reservedReportTaskIds += taskId
        changedLocked()
        true
    }

    /** Releases a reservation only when its queue admission was not accepted. */
    fun abandonActionAdmission(taskId: String): Boolean = synchronized(lock) {
        if (taskId !in reservedReportTaskIds || taskId in knownReportTaskIds || taskId in deliveredReportTaskIds) return false
        reservedReportTaskIds.remove(taskId)
        changedLocked()
        true
    }

    /** A non-reserving display hint; use [reserveActionAdmission] to actually admit a task. */
    fun canAdmitAcceptedAction(): Boolean = synchronized(lock) {
        !detached && outstandingReportObligationsLocked() < reportCapacity
    }

    /** Only this session's admissions belong in its terminal scan and final summary. */
    fun ownsActionTask(taskId: String): Boolean = synchronized(lock) {
        taskId in reservedReportTaskIds || taskId in knownReportTaskIds || taskId in deliveredReportTaskIds
    }

    /**
     * Queue one visible local clarification/rejection for speech after accepted work settles.
     * It is separate from reserved executor reports so a full accepted batch can still explain
     * a later rejected or busy final utterance. Only one is retained at a time.
     */
    fun offerLocalFeedback(text: String): Boolean = synchronized(lock) {
        if (detached || text.isBlank()) return false
        // Keep every bounded local response audible without borrowing executor-report
        // reservations. A second response is aggregated into the same fresh delivery attempt.
        val existingId = localFeedbackIds.firstOrNull()
        if (existingId != null) {
            val index = pendingReports.indexOfFirst { it.taskId == existingId }
            if (index >= 0) {
                val existing = pendingReports.removeAt(index)
                pendingReports.add(index, existing.copy(text = "${existing.text} $text"))
                changedLocked()
                return true
            }
        }
        val id = "local-feedback-${nextLocalFeedbackId++}"
        localFeedbackIds += id
        pendingReports += PendingReport(id, text)
        changedLocked()
        true
    }

    /**
     * Add a terminal executor outcome. Duplicate completion callbacks never create a second report.
     *
     * Correct callers reserve the task first with [reserveActionAdmission], so this never overflows
     * for a live session. False is deliberately retryable: retain the durable receipt and retry
     * after [markDelivered] (or restore it into a fresh call), never silently discard the result.
     */
    fun onTaskEvent(taskId: String, text: String): Boolean = synchronized(lock) {
        if (detached) return false
        if (taskId in knownReportTaskIds || taskId in deliveredReportTaskIds) return true
        if (taskId !in reservedReportTaskIds && outstandingReportObligationsLocked() >= reportCapacity) return false
        knownReportTaskIds += taskId
        pendingReports += PendingReport(taskId, text)
        changedLocked()
        true
    }

    /**
     * Starts an aggregate report after the task worker is idle and a final capture has yielded the
     * floor. Nothing is removed here: playback completion is the only delivery acknowledgement.
     */
    fun nextDelivery(): ReportDelivery? = synchronized(lock) {
        if (detached || captureInProgress || delivery != null || !queue.idle.value || pendingReports.isEmpty()) return null
        ReportDelivery(nextAttemptId++, pendingReports.toList()).also {
            delivery = it
            changedLocked()
        }
    }

    /**
     * The action pump must keep an idle observer armed until it has reconciled at least one
     * terminal transition. A worker can complete between an eager scan and listener creation.
     */
    fun needsIdleObservation(terminalReportsObserved: Boolean): Boolean =
        !terminalReportsObserved || queue.hasUnfinished()

    /** A suspension point for a host pump that selects capture, queue events, and report delivery. */
    suspend fun awaitDeliveryReady() {
        combine(queue.idle, _revision) { idle, _ -> idle }
            .first { idle -> idle && synchronized(lock) { deliveryReadyLocked() } }
    }

    /**
     * Acknowledge only task IDs actually present in the current completed speech attempt. Stale
     * TTS callbacks and generated-but-never-played reports are rejected.
     */
    fun markDelivered(attemptId: Long, taskIds: Set<String>): Boolean = synchronized(lock) {
        val active = delivery ?: return false
        if (active.attemptId != attemptId || taskIds.isEmpty()) return false
        val permitted = active.reports.mapTo(linkedSetOf()) { it.taskId }
        if (!permitted.containsAll(taskIds)) return false
        pendingReports.removeAll { it.taskId in taskIds }
        deliveredReportTaskIds += taskIds
        reservedReportTaskIds.removeAll(taskIds)
        localFeedbackIds.removeAll(taskIds)
        delivery = null
        changedLocked()
        true
    }

    /** Barge-in or a cancelled TTS attempt: preserve every report for a later aggregate attempt. */
    fun interruptDelivery(attemptId: Long? = null): Boolean = synchronized(lock) {
        if (detached) return false
        val interrupted = interruptDeliveryLocked(attemptId)
        if (interrupted) changedLocked()
        interrupted
    }

    /** End-call detaches only capture/delivery. The queue continues and its durable receipts remain. */
    fun detach() {
        synchronized(lock) {
            detached = true
            captureInProgress = false
            delivery = null
            deferredConversation = null
            changedLocked()
        }
    }

    /** The pump may cancel its listener only when this locked claim wins over capture start. */
    fun tryDetachIdleCapture(): Boolean = synchronized(lock) {
        if (captureInProgress || detached || captureDetached) false else { captureDetached = true; changedLocked(); true }
    }

    fun isCaptureInProgress(): Boolean = synchronized(lock) { captureInProgress }
    fun pendingReportCount(): Int = synchronized(lock) { pendingReports.size }

    private fun outstandingReportObligationsLocked(): Int = reservedReportTaskIds.size +
        pendingReports.count { it.taskId !in reservedReportTaskIds }

    private fun deliveryReadyLocked(): Boolean =
        !detached && !captureInProgress && delivery == null && pendingReports.isNotEmpty()

    private fun interruptDeliveryLocked(attemptId: Long?): Boolean {
        val active = delivery ?: return false
        if (attemptId != null && active.attemptId != attemptId) return false
        delivery = null
        speechDetached = true
        return true
    }

    private fun rememberCaptureLocked(utteranceId: String): Boolean {
        if (!knownCaptureIds.add(utteranceId)) return false
        captureIds += utteranceId
        if (captureIds.size > captureHistoryCapacity) knownCaptureIds.remove(captureIds.removeFirst())
        return true
    }

    private fun changedLocked() {
        _revision.value += 1
    }
}
