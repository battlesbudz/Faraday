package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import com.battlesbudz.jarvis.v2.actions.ActionApprovalStore
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.AdmitResult
import com.battlesbudz.jarvis.v2.actions.AndroidToolCapabilityProbe
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.JournaledActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.MobileToolCatalog
import com.battlesbudz.jarvis.v2.actions.ScheduleDecision
import com.battlesbudz.jarvis.v2.actions.ScreenApprovalAdmission
import com.battlesbudz.jarvis.v2.actions.ScreenControlService
import com.battlesbudz.jarvis.v2.actions.TaskProgressNotification
import com.battlesbudz.jarvis.v2.actions.TaskProgressProjector
import com.battlesbudz.jarvis.v2.actions.TaskResource
import com.battlesbudz.jarvis.v2.actions.TaskResourceKind
import com.battlesbudz.jarvis.v2.actions.TaskScheduler
import com.battlesbudz.jarvis.v2.actions.ToolSourceAccess
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.actions.ToolTaskState
import com.battlesbudz.jarvis.v2.actions.ToolTaskStorageException
import com.battlesbudz.jarvis.v2.actions.ToolTaskStorageFailure
import com.battlesbudz.jarvis.v2.actions.androidLockGate
import com.battlesbudz.jarvis.v2.actions.describeForOverlay
import com.battlesbudz.jarvis.v2.actions.isWorkflowOwned
import com.battlesbudz.jarvis.v2.chat.ActionReceipt
import com.battlesbudz.jarvis.v2.voice.SilentWorkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns durable phone-task admission, approval, recovery and conversation projections.
 * Android execution and conversation storage enter through explicit ports; this component
 * never owns a model, microphone, activity or the wider application runtime.
 *
 * M1d screen-task discipline lives here: model-proposed screen mutations are
 * parked for approval (never auto-dispatched), panel approval admits the
 * screen-control session grant atomically with dispatch eligibility, and one
 * addressable task-status projection feeds chat, the panel and notifications.
 */
internal class PhoneTaskCoordinator(
    private val scope: CoroutineScope,
    private val appContext: Context,
    ledgerFactory: () -> ToolTaskLedger,
    private val isDeviceLocked: () -> Boolean,
    private val createExecutor: () -> MobileActionExecutor,
    private val conversationExists: (String) -> Boolean,
    private val projectReply: (conversationId: String, replyId: String, text: String, receipts: List<ActionReceipt>) -> Unit,
    private val silentWork: SilentWorkController,
    private val recordDiagnostic: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val ledger by lazy(ledgerFactory)
    val tasks = MutableStateFlow<ToolTaskJournal?>(null)
    val error = MutableStateFlow<String?>(null)
    private var loadError: String? = null
    private var operationError: String? = null

    private fun publishError() {
        error.value = listOfNotNull(loadError, operationError).distinct().takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
    private fun storageFailure(failure: ToolTaskStorageException, writeMessage: String? = null) {
        if (failure.failure == ToolTaskStorageFailure.WRITE_FAILED) operationError = writeMessage ?: failure.userMessage()
        else loadError = failure.userMessage()
        publishError()
    }
    private fun operationStatus(message: String?) { operationError = message; publishError() }

    fun reportError(message: String) = operationStatus(message)


    /** Called synchronously during runtime construction, before any alarm can own an effect. */
    fun recoverAfterRestart(): Boolean {
        return try {
            ledger.recoverAfterRestart()
            val journal = ledger.journal()
            journal.groups.filter { group -> !journal.isWorkflowOwned(group) && journal.attempts.any {
                it.groupId == group.id && it.state != ToolTaskState.SUCCEEDED
            } }.forEach { projectPhoneTask(it.id, recovered = true) }
            true
        } catch (failure: ToolTaskStorageException) {
            storageFailure(failure)
            false
        } finally { refreshPhoneTasks() }
    }

    fun admitPhoneTask(plan: ActionTurnPlan.Ready, conversationId: String): String? = try {
        ledger.admit(plan.steps.map { it.request }, conversationId,
            resumeAfterRestart = plan.batteryCondition == null).id.also { operationStatus(null); refreshPhoneTasks() }
    } catch (failure: ToolTaskStorageException) {
        storageFailure(failure, "I couldn't save the new phone task. No new action was started.")
        null
    }

    fun refreshPhoneTasks() {
        try {
            tasks.value = ledger.journal()
            loadError = null
            publishError()
        } catch (failure: ToolTaskStorageException) {
            tasks.value = null
            storageFailure(failure)
            return
        }
        // M1d: a required question asked during silent work (D22) temporarily
        // opens an answer window so the user can answer by voice; the mode
        // returns to silence once the question resolves or the window lapses.
        if (silentWork.isSilent && !silentWork.isAnswerWindowOpen()) {
            val journal = tasks.value
            val question = journal?.attempts
                ?.filter { it.state == ToolTaskState.WAITING_APPROVAL }
                ?.mapNotNull { attempt -> attempt.approvalId?.let { id -> journal.approvals.find { it.id == id } } }
                ?.lastOrNull { !it.consumed }
            if (question != null) silentWork.requestAnswer(question.id)
        }
    }

    fun cancelPhoneTask(groupId: String) {
        try { ledger.cancelGroup(groupId); operationStatus(null) }
        catch (failure: ToolTaskStorageException) {
            storageFailure(failure, "I couldn't save the cancellation. The task is not confirmed cancelled; please try again.")
        }
        refreshPhoneTasks()
    }

    private fun phoneActionPipeline(executor: MobileActionExecutor): JournaledActionPipeline =
        JournaledActionPipeline(
            ledger,
            executor,
            sourceAccess = ToolSourceAccess(ledger),
            capabilityProbe = AndroidToolCapabilityProbe(appContext),
            lockGate = androidLockGate(appContext),
            onJournalChanged = ::refreshPhoneTasks,
            onStorageFailure = ::storageFailure
        )

    fun executePhoneAction(
        request: ActionRequest,
        executor: MobileActionExecutor,
        groupId: String? = null,
        stepIndex: Int = 0
    ): ExecutionResult = try {
        val pipeline = phoneActionPipeline(executor)
        if (groupId == null) pipeline.execute(request) else {
            val journal = ledger.journal()
            val id = journal.groups.find { it.id == groupId }?.attemptIds?.getOrNull(stepIndex)
            val attempt = journal.attempts.find { it.id == id }
            if (attempt == null) ExecutionResult(false,
                "The saved action no longer matches this request. I didn't start it.")
            else pipeline.executeBound(attempt, request)
        }
    } catch (failure: ToolTaskStorageException) {
        storageFailure(failure)
        ExecutionResult(false, failure.userMessage())
    } finally { refreshPhoneTasks() }

    /**
     * M1d: a model-proposed screen mutation never auto-dispatches (D23). Park
     * it in the ledger awaiting the user's explicit approval; the panel's
     * Approve button then admits the session grant and dispatches it exactly
     * (approval consumption and dispatch eligibility commit together in the
     * ledger claim). Returns a truthful not-yet receipt, never a success.
     */
    fun parkScreenTaskForApproval(request: ActionRequest, conversationId: String): ExecutionResult {
        return try {
            val group = ledger.admit(listOf(request), conversationId)
            val attempt = ledger.get(group.attemptIds.single())
                ?: return ExecutionResult(false,
                    "I couldn't save the screen action, so I didn't start it.")
            ledger.requestApproval(attempt.id, attempt.generation, "native",
                MobileToolCatalog.VERSION)
            refreshPhoneTasks()
            recordDiagnostic("Screen task parked for approval: ${request.name} group=${group.id}")
            ExecutionResult(false,
                "I need your approval before I control the screen. " +
                    "Approve \"${request.describeForOverlay()}\" in the phone tasks panel to continue.")
        } catch (_: ToolTaskStorageException) {
            ExecutionResult(false,
                "I couldn't save the screen action, so I didn't start it.")
        } catch (_: IllegalArgumentException) {
            ExecutionResult(false,
                "I couldn't verify the requested screen action.")
        }
    }

    /** Re-evaluate only at process startup or foreground/unlock; no periodic memory polling. */
    fun resumePhoneTasksAfterUnlock() {
        scope.launch(dispatcher) {
            if (isDeviceLocked()) return@launch
            try {
                ledger.pauseExpiredGroups()
                val journal = ledger.journal()
                for (group in journal.groups) {
                    // Workflow IDs are not chat IDs. Their occurrence owns dispatch and recovery.
                    if (journal.isWorkflowOwned(group)) continue
                    if (!conversationExists(group.conversationId)) {
                        ledger.cancelGroup(group.id)
                        continue
                    }
                    if (System.currentTimeMillis() >= group.expiresAtMs) continue
                    for (id in group.attemptIds) {
                        val attempt = ledger.get(id) ?: break
                        if (attempt.state == ToolTaskState.SUCCEEDED) continue
                        if (attempt.state != ToolTaskState.READY) break
                        val result = phoneActionPipeline(createExecutor()).executeAttempt(attempt)
                        projectPhoneTask(group.id, recovered = true)
                        if (!result.succeeded) break
                    }
                }
            } catch (failure: ToolTaskStorageException) {
                storageFailure(failure)
            } finally { refreshPhoneTasks() }
        }
    }

    /** Resources currently held by running work, for the M1d scheduling policy (D18). */
    private fun runningTaskResources(excludeGroupId: String? = null): List<TaskResource> {
        val scheduler = TaskScheduler()
        val out = mutableListOf<TaskResource>()
        try {
            // M1d: a task approving its own group's next step is not
            // conflicting with itself — the lease it holds is its own.
            val holder = ScreenControlService.sharedSession.holderGroupId
            if (holder != null && holder != excludeGroupId) {
                out += TaskResource(TaskResourceKind.SCREEN_LEASE)
            }
            ledger.journal().attempts
                .filter { it.state == ToolTaskState.RUNNING }
                .forEach { out += scheduler.resourceFor(it.request) }
        } catch (_: ToolTaskStorageException) {
            // Fail open to the session's atomic verdict below.
        }
        return out
    }

    private fun projectPhoneTask(groupId: String, recovered: Boolean = false) {
        val j = ledger.journal()
        val group = j.groups.find { it.id == groupId } ?: return
        if (j.isWorkflowOwned(group)) return
        val attempts = group.attemptIds.mapNotNull { id -> j.attempts.find { it.id == id } }
        val receipts = attempts.mapNotNull { a -> a.result?.let {
            ActionReceipt(a.request.name, it, a.state == ToolTaskState.SUCCEEDED) } }
        val status = when {
            attempts.any { !it.state.isTerminalForUi() } -> "Some steps are still waiting."
            attempts.any { it.state == ToolTaskState.UNKNOWN_OUTCOME } ->
                "Task finished, but some steps could not be confirmed."
            else -> "Task finished."
        }
        projectReply(group.conversationId, "phone-task:$groupId",
            (if (recovered) "Recovered phone task. " else "Phone task. ") + status, receipts)
        // M1d: one addressable task-status projection feeds chat, the panel and
        // notifications alike. Progress continues into notifications after call
        // end (T04); a finished group releases the screen lease and removes the
        // Stop overlay (T04, D26).
        val projection = TaskProgressProjector().project(j, groupId)
        if (projection != null) {
            if (projection.isTerminal) {
                if (ScreenControlService.sharedSession.releaseIf(groupId)) {
                    ScreenControlService.bridge(appContext).hideStopOverlay()
                    recordDiagnostic("Screen control released for finished task group $groupId.")
                }
                TaskProgressNotification.postFinished(appContext, projection)
            } else {
                TaskProgressNotification.postProgress(appContext, projection)
            }
        }
    }

    fun phoneTaskAction(id: String, generation: Long, command: String) {
        scope.launch(dispatcher) {
            operationStatus(null)
            try {
                ledger.pauseExpiredGroups()
                val a = ledger.get(id)?.takeIf { it.generation == generation } ?: return@launch
                val approvals = ActionApprovalStore(ledger.store)
                when (command) {
                    "approve" -> {
                        if (isDeviceLocked()) return@launch
                        val approval = a.approvalId?.let { approvals.get(it) } ?: return@launch
                        // M1d scheduling (T02): a follow-up runs independently when it
                        // does not conflict; a conflicting task queues behind the
                        // running work instead of racing it.
                        val scheduler = TaskScheduler()
                        when (val decision = scheduler.schedule(
                            scheduler.resourceFor(a.request), runningTaskResources(a.groupId))) {
                            is ScheduleDecision.Queue -> {
                                operationStatus(decision.reason)
                                refreshPhoneTasks()
                                return@launch
                            }
                            ScheduleDecision.RunNow -> Unit
                        }
                        // M1d: approving a screen task admits the screen-control
                        // session grant for its group in the same approval
                        // decision (D23). The ledger claim below still consumes
                        // the approval atomically with dispatch eligibility; a
                        // changed target invalidates the prior approval (D13),
                        // so a stale panel approval can never admit the session.
                        val screenSession = ScreenControlService.sharedSession
                        val admission = ScreenApprovalAdmission(screenSession)
                        val screenTask = admission.needsSession(a.request)
                        val admittedHere = when (val verdict = admission.admitForApproval(a, approval)) {
                            is AdmitResult.Admitted -> true
                            is AdmitResult.AlreadyAdmitted,
                            is AdmitResult.NeedsApproval -> false
                            is AdmitResult.Denied -> {
                                // Another task holds the screen lease (T02): keep
                                // this task waiting for its turn. The approval
                                // stays unconsumed so it can be approved again
                                // once the lease releases.
                                operationStatus(verdict.reason)
                                refreshPhoneTasks()
                                return@launch
                            }
                        }
                        val result = phoneActionPipeline(createExecutor()).executeAttempt(a, approval)
                        if (!result.succeeded) {
                            operationStatus(result.message)
                            // The approval did not survive the claim: never
                            // leave a lease behind for an undispatched task.
                            if (screenTask && admittedHere) a.groupId?.let { screenSession.releaseIf(it) }
                        } else {
                            silentWork.onAnswerReceived(approval.id)
                            if (screenTask && admittedHere) ScreenControlService.bridge(appContext)
                                .showStopOverlay(a.request.describeForOverlay())
                        }
                    }
                    "deny" -> a.approvalId?.let {
                        approvals.deny(it)
                        silentWork.onAnswerReceived(it)
                    }
                    "cancel" -> if (a.groupId != null) ledger.cancelGroup(a.groupId)
                        else ledger.cancelLegacyAttempt(a.id, a.generation)
                    "checked" -> if (ledger.reconcileUnknown(a.id, a.generation)) a.groupId?.let { ledger.cancelGroup(it) }
                    else -> return@launch
                }
                a.groupId?.let { projectPhoneTask(it) }
            } catch (failure: ToolTaskStorageException) {
                storageFailure(failure, "I couldn't save that decision. Please try again.")
            } finally { refreshPhoneTasks() }
        }
    }

    private fun ToolTaskState.isTerminalForUi() = this in setOf(
        ToolTaskState.SUCCEEDED, ToolTaskState.FAILED,
        ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
}
