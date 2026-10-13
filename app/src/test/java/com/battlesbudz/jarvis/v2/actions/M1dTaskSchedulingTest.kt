package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.voice.VoiceActionControl
import org.junit.Assert.*
import org.junit.Test

/**
 * M1d task/conversation scheduling (D18/D19/D24, T02/T03/T07).
 *
 * Covers the scheduling policy (independent vs conflicting tasks), the
 * task-targeted stop router, the D24 stop phrases, the approval-to-session
 * admission with exact-approval semantics, ledger cancellation by identity,
 * and the task-status projection.
 */
class M1dTaskSchedulingTest {

    private val scheduler = TaskScheduler()
    private val router = TaskStopRouter()

    private fun tap(target: String = "n1", token: String = "abcdef0123456789") =
        ActionRequest("screen_tap", mapOf("target" to target, "token" to token))

    private fun openApp(app: String) = ActionRequest("open_app", mapOf("app" to app))

    // Scheduling policy (D18, T02).

    @Test fun screenMutationsNeedTheScreenLease() {
        assertEquals(TaskResource(TaskResourceKind.SCREEN_LEASE), scheduler.resourceFor(tap()))
        assertEquals(TaskResource(TaskResourceKind.SCREEN_LEASE),
            scheduler.resourceFor(ActionRequest("screen_scroll", mapOf("target" to "n2", "direction" to "down", "token" to "abcdef0123456789"))))
        assertEquals(TaskResource(TaskResourceKind.SCREEN_LEASE),
            scheduler.resourceFor(ActionRequest("screen_type", mapOf("target" to "n3", "text" to "hi", "token" to "abcdef0123456789"))))
    }

    @Test fun appLaunchesTargetTheirApp() {
        assertEquals(TaskResource(TaskResourceKind.APP, "settings"), scheduler.resourceFor(openApp("Settings")))
        assertEquals(TaskResource(TaskResourceKind.NONE), scheduler.resourceFor(ActionRequest("read_battery")))
        assertEquals(TaskResource(TaskResourceKind.NONE), scheduler.resourceFor(ActionRequest("set_volume", mapOf("level" to "30"))))
    }

    @Test fun secondScreenTaskQueuesBehindTheLease() {
        val decision = scheduler.schedule(
            TaskResource(TaskResourceKind.SCREEN_LEASE),
            listOf(TaskResource(TaskResourceKind.SCREEN_LEASE)))
        assertTrue(decision is ScheduleDecision.Queue)
    }

    @Test fun independentTasksRunNow() {
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            TaskResource(TaskResourceKind.SCREEN_LEASE), listOf(TaskResource(TaskResourceKind.APP, "settings"))))
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            TaskResource(TaskResourceKind.APP, "maps"), listOf(TaskResource(TaskResourceKind.SCREEN_LEASE))))
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            TaskResource(TaskResourceKind.NONE), listOf(TaskResource(TaskResourceKind.SCREEN_LEASE))))
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            TaskResource(TaskResourceKind.SCREEN_LEASE), emptyList()))
    }

    @Test fun sameAppQueuesBehindItself() {
        val decision = scheduler.schedule(
            TaskResource(TaskResourceKind.APP, "settings"),
            listOf(TaskResource(TaskResourceKind.APP, "settings")))
        assertTrue(decision is ScheduleDecision.Queue)
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            TaskResource(TaskResourceKind.APP, "camera"),
            listOf(TaskResource(TaskResourceKind.APP, "settings"))))
    }

    @Test fun planResourcePrefersTheScreenLease() {
        val resource = scheduler.resourceForPlan(listOf(openApp("Settings"), tap()))
        assertEquals(TaskResourceKind.SCREEN_LEASE, resource.kind)
    }

    // Task-targeted cancellation (D19/D24, T03).

    @Test fun d24StopPhrasesParseToTaskControls() {
        assertEquals(VoiceActionControl.CancelCurrent, VoiceActionControl.parse("stop your task", true))
        assertEquals(VoiceActionControl.CancelCurrent, VoiceActionControl.parse("stop this task", true))
        assertEquals(VoiceActionControl.CancelCurrent, VoiceActionControl.parse("cancel my task", true))
        assertEquals(VoiceActionControl.CancelAll, VoiceActionControl.parse("stop all tasks", true))
        assertEquals(VoiceActionControl.CancelAll, VoiceActionControl.parse("cancel all tasks", true))
        // Existing controls are unchanged.
        assertEquals(VoiceActionControl.SpeechOnly, VoiceActionControl.parse("stop", true))
        assertEquals(VoiceActionControl.SpeechOnly, VoiceActionControl.parse("stop speaking", true))
        assertEquals(VoiceActionControl.CancelAll, VoiceActionControl.parse("stop all actions", true))
    }

    @Test fun stopRouterMapsControlsToExactScopes() {
        assertEquals(TaskStopScope.SpeechOnly, router.route(VoiceActionControl.SpeechOnly, "n", "c"))
        assertEquals(TaskStopScope.SingleTask("newest-1"),
            router.route(VoiceActionControl.CancelNewest, "newest-1", "current-1"))
        assertNull(router.route(VoiceActionControl.CancelNewest, null, null))
        assertEquals(TaskStopScope.CurrentTask, router.route(VoiceActionControl.CancelCurrent, "newest-1", "current-1"))
        assertNull(router.route(VoiceActionControl.CancelCurrent, null, null))
        assertEquals(TaskStopScope.AllTasks, router.route(VoiceActionControl.CancelAll, "n", "c"))
        assertEquals(TaskStopScope.QueuedOnly, router.route(VoiceActionControl.CancelQueued, "n", "c"))
        assertNull(router.route(VoiceActionControl.None, "n", "c"))
    }

    @Test fun cancelTaskByIdTargetsExactlyOneGroup() {
        val ledger = ToolTaskLedger()
        val first = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1")
        val second = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1")
        assertTrue(ledger.cancelTaskById(first.attemptIds.single()))
        assertEquals(ToolTaskState.CANCELLED, ledger.get(first.attemptIds.single())?.state)
        assertEquals(ToolTaskState.QUEUED, ledger.get(second.attemptIds.single())?.state)
        assertFalse(ledger.cancelTaskById("no-such-task"))
    }

    @Test fun cancelTaskByIdTargetsALegacyAttempt() {
        val ledger = ToolTaskLedger()
        val legacy = ledger.create(ActionRequest("read_battery"))
        assertTrue(ledger.cancelTaskById(legacy.id))
        assertEquals(ToolTaskState.CANCELLED, ledger.get(legacy.id)?.state)
    }

    @Test fun cancelAllTasksCancelsRemainingWorkButKeepsReceipts() {
        val ledger = ToolTaskLedger()
        val queued = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1")
        val legacy = ledger.create(ActionRequest("read_battery"))
        val done = ledger.create(ActionRequest("read_battery"), ToolTaskState.SUCCEEDED)
        assertTrue(ledger.cancelAllTasks() >= 2)
        assertEquals(ToolTaskState.CANCELLED, ledger.get(queued.attemptIds.single())?.state)
        assertEquals(ToolTaskState.CANCELLED, ledger.get(legacy.id)?.state)
        assertEquals(ToolTaskState.SUCCEEDED, ledger.get(done.id)?.state)
        assertEquals(0, ledger.cancelAllTasks()) // nothing left: second call is a no-op
    }

    // Approval -> session admission (D11/D13/D23, T07).

    private fun exactApprovalFixture(request: ActionRequest = tap()): Triple<ToolTaskLedger, ToolTaskAttempt, ActionApprovalRequest> {
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(request), "thread-1")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val dispatch = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        return Triple(ledger, dispatch.task, dispatch.approval)
    }

    @Test fun approvingAScreenTaskAdmitsTheSessionGrant() {
        val (_, task, approval) = exactApprovalFixture()
        val session = ScreenControlSession()
        val admission = ScreenApprovalAdmission(session)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(task, approval))
        assertEquals(task.groupId, session.holderGroupId)
    }

    @Test fun secondGroupQueuesBehindTheLeaseInsteadOfStealingIt() {
        val (_, firstTask, firstApproval) = exactApprovalFixture()
        val (_, secondTask, secondApproval) = exactApprovalFixture()
        val session = ScreenControlSession()
        val admission = ScreenApprovalAdmission(session)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(firstTask, firstApproval))
        val denied = admission.admitForApproval(secondTask, secondApproval)
        assertTrue(denied is AdmitResult.Denied)
        assertEquals(firstTask.groupId, session.holderGroupId)
        // The denied approval stays unconsumed: the task waits for its turn.
        assertFalse(secondApproval.consumed)
    }

    @Test fun changedTargetInvalidatesThePriorApproval() {
        val (ledger, task, approval) = exactApprovalFixture()
        // The user edits the target: the old approval goes stale.
        val revised = checkNotNull(ledger.revise(task.id, task.generation, tap(target = "n9")))
        // Re-read: revise consumes the old approval as STALE in the journal.
        val staleApproval = checkNotNull(ledger.journal().approvals.find { it.id == approval.id })
        assertTrue(staleApproval.consumed)
        val session = ScreenControlSession()
        val admission = ScreenApprovalAdmission(session)
        // The stale panel approval cannot admit the session...
        val stale = admission.admitForApproval(task, staleApproval)
        assertTrue(stale is AdmitResult.Denied)
        // ...but a fresh approval for the revised action can.
        val fresh = ledger.requestApproval(revised.id, revised.generation, "native", MobileToolCatalog.VERSION)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(fresh.task, fresh.approval))
    }

    @Test fun consumedApprovalCannotAdmitTwice() {
        val (_, task, approval) = exactApprovalFixture()
        val consumed = approval.copy(consumed = true, decision = ApprovalDecision.APPROVED)
        val admission = ScreenApprovalAdmission(ScreenControlSession())
        assertTrue(admission.admitForApproval(task, consumed) is AdmitResult.Denied)
    }

    @Test fun nonScreenTasksDoNotTouchTheSession() {
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1", ToolAuthority.EXACT_APPROVAL)
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val dispatch = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        val session = ScreenControlSession()
        val admission = ScreenApprovalAdmission(session)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(dispatch.task, dispatch.approval))
        assertFalse(session.isAdmitted)
    }

    @Test fun releaseIfOnlyReleasesTheHoldingGroup() {
        val session = ScreenControlSession()
        assertEquals(AdmitResult.Admitted, session.admit("group-a", userApproved = true))
        assertEquals("group-a", session.holderGroupId)
        assertFalse(session.releaseIf("group-b"))
        assertTrue(session.isAdmitted)
        assertTrue(session.releaseIf("group-a"))
        assertFalse(session.isAdmitted)
        assertNull(session.holderGroupId)
    }

    // Dispatch eligibility: screen tools claimable only under exact approval.

    @Test fun screenTaskClaimNeedsExactApproval() {
        val ledger = ToolTaskLedger()
        // Bare user-request authority can never dispatch a screen mutation...
        val group = ledger.admit(listOf(tap()), "thread-1")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        assertNull(ledger.claim(attempt.id, attempt.generation))
        // ...but the exact-approval path commits consumption and dispatch
        // eligibility together.
        val dispatch = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        val running = ledger.claim(dispatch.task.id, dispatch.task.generation, approval = dispatch.approval)
        assertNotNull(running)
        assertEquals(ToolTaskState.RUNNING, running?.state)
        assertTrue(checkNotNull(ledger.journal().approvals.find { it.id == dispatch.approval.id }).consumed)
    }

    @Test fun screenToolsStayOutOfRoutineGrants() {
        val ledger = ToolTaskLedger()
        assertThrows(IllegalArgumentException::class.java) {
            ledger.grant("routine-1", listOf(tap()), System.currentTimeMillis() + 60_000L)
        }
    }

    // Task-status projection (T04, T15).

    private fun projectionFixture(vararg states: ToolTaskState): Pair<ToolTaskLedger, String> {
        val ledger = ToolTaskLedger()
        val group = ledger.admit(
            states.map { ActionRequest("read_battery") }, "thread-1")
        states.forEachIndexed { index, state ->
            val id = group.attemptIds[index]
            val attempt = checkNotNull(ledger.get(id))
            when (state) {
                ToolTaskState.SUCCEEDED, ToolTaskState.FAILED -> {
                    val running = checkNotNull(ledger.claim(id, attempt.generation))
                    ledger.finish(running, when (state) {
                        ToolTaskState.SUCCEEDED -> ExecutionResult(true, "Battery is at 80%.")
                        else -> ExecutionResult(false, "Could not read battery.")
                    })
                }
                else -> ledger.transition(id, attempt.generation, state)
            }
        }
        return ledger to group.id
    }

    @Test fun projectionReportsWorkingWaitingAndFinished() {
        val projector = TaskProgressProjector()
        val (working, workingId) = projectionFixture(ToolTaskState.QUEUED, ToolTaskState.QUEUED)
        val workingProjection = checkNotNull(projector.project(working.journal(), workingId))
        assertEquals(TaskProjectionState.WORKING, workingProjection.state)
        assertEquals(0, workingProjection.completedSteps)
        assertEquals(2, workingProjection.totalSteps)
        assertFalse(workingProjection.isTerminal)

        val (finished, finishedId) = projectionFixture(ToolTaskState.SUCCEEDED, ToolTaskState.SUCCEEDED)
        val finishedProjection = checkNotNull(projector.project(finished.journal(), finishedId))
        assertEquals(TaskProjectionState.FINISHED, finishedProjection.state)
        assertEquals(2, finishedProjection.completedSteps)
        assertEquals(2, finishedProjection.stepReceipts.size)
        assertTrue(finishedProjection.isTerminal)
        assertTrue(finishedProjection.statusLine.contains("done"))
    }

    @Test fun projectionReportsCancellationAndFailure() {
        val projector = TaskProgressProjector()
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1")
        ledger.cancelGroup(group.id)
        val cancelled = checkNotNull(projector.project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.CANCELLED, cancelled.state)
        assertTrue(cancelled.isTerminal)

        val (failed, failedId) = projectionFixture(ToolTaskState.FAILED)
        val failedProjection = checkNotNull(projector.project(failed.journal(), failedId))
        assertEquals(TaskProjectionState.FAILED, failedProjection.state)
        assertTrue(failedProjection.isTerminal)
    }

    @Test fun projectionReportsWaitingApproval() {
        val projector = TaskProgressProjector()
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread-1", ToolAuthority.EXACT_APPROVAL)
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        val projection = checkNotNull(projector.project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.WAITING_APPROVAL, projection.state)
        assertFalse(projection.isTerminal)
    }

    @Test fun projectionKeepsOrderedReceipts() {
        val projector = TaskProgressProjector()
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(ActionRequest("read_battery"), ActionRequest("read_battery")), "thread-1")
        group.attemptIds.forEachIndexed { index, id ->
            val attempt = checkNotNull(ledger.get(id))
            val running = checkNotNull(ledger.claim(id, attempt.generation))
            ledger.finish(running, ExecutionResult(true, "step-$index done"))
        }
        val projection = checkNotNull(projector.project(ledger.journal(), group.id))
        assertEquals(listOf("step-0 done", "step-1 done"), projection.stepReceipts)
    }

    @Test fun unknownGroupProjectsToNull() {
        assertNull(TaskProgressProjector().project(ToolTaskLedger().journal(), "no-such-group"))
    }

    // Model-proposed screen mutations park for approval instead of dispatching (D23).

    private fun screenTapCall() =
        com.battlesbudz.jarvis.v2.ai.ToolCall("screen_tap", "{\"target\":\"n1\",\"token\":\"abcdef0123456789\"}")

    @Test fun validateBatchParksScreenProposalsForApproval() {
        val runner = ActionTurnRunner(MobileActionExecutor { error("no dispatch in validation") })
        val plan = ActionTurnPlan.parse("what's on my screen") as ActionTurnPlan.Ready
        val batch = runner.validateBatch(plan, emptyList(), listOf(screenTapCall()))
        assertTrue(batch is ActionTurnRunner.Batch.NeedsApproval)
        assertEquals(listOf("screen_tap"), (batch as ActionTurnRunner.Batch.NeedsApproval).proposed.map { it.name })
    }

    @Test fun validateBatchKeepsAcceptedCompanionsAlongsideProposals() {
        val runner = ActionTurnRunner(MobileActionExecutor { error("no dispatch in validation") })
        val plan = ActionTurnPlan.parse("what's on my screen") as ActionTurnPlan.Ready
        val batch = runner.validateBatch(plan, emptyList(), listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("screen_observe", "{}"), screenTapCall()))
        assertTrue(batch is ActionTurnRunner.Batch.NeedsApproval)
        val needs = batch as ActionTurnRunner.Batch.NeedsApproval
        assertEquals(listOf("screen_observe"), needs.accepted?.requests?.map { it.name })
        assertEquals(listOf("screen_tap"), needs.proposed.map { it.name })
    }

    @Test fun validateBatchStillRejectsUnmatchedNonScreenCalls() {
        val runner = ActionTurnRunner(MobileActionExecutor { error("no dispatch in validation") })
        val plan = ActionTurnPlan.parse("what's on my screen") as ActionTurnPlan.Ready
        val batch = runner.validateBatch(plan, emptyList(), listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}")))
        assertTrue(batch is ActionTurnRunner.Batch.Rejected)
    }

    @Test fun runNativeParksScreenProposalsInsteadOfDispatching() = kotlinx.coroutines.runBlocking {
        val runner = ActionTurnRunner(MobileActionExecutor { error("proposals must not reach the executor") })
        val plan = ActionTurnPlan.parse("what's on my screen") as ActionTurnPlan.Ready
        val parked = mutableListOf<ActionRequest>()
        val dispatched = mutableListOf<String>()
        val outcome = runner.runNative(
            plan,
            // One model pass proposing observe + tap: the observation dispatches
            // normally while the tap parks for approval.
            listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("screen_observe", "{}"), screenTapCall()),
            dispatch = { request -> dispatched += request.name; ExecutionResult(true, "Screen observed.") },
            nextCalls = { emptyList() },
            onNeedsApproval = { request ->
                parked += request
                ExecutionResult(false, "Waiting for your approval.")
            }
        )
        assertEquals(listOf("screen_observe"), dispatched)
        assertEquals(listOf("screen_tap"), parked.map { it.name })
        assertEquals(2, outcome.receipts.size)
        assertTrue(outcome.stopped)
        assertFalse(outcome.completed)
        assertTrue(outcome.message.contains("Waiting for your approval"))
    }

    @Test fun runNativeParksTapProposedAfterObservation() = kotlinx.coroutines.runBlocking {
        val runner = ActionTurnRunner(MobileActionExecutor { error("proposals must not reach the executor") })
        val plan = ActionTurnPlan.parse("what's on my screen and read my battery") as ActionTurnPlan.Ready
        val parked = mutableListOf<ActionRequest>()
        val dispatched = mutableListOf<String>()
        val outcome = runner.runNative(
            plan,
            listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("screen_observe", "{}")),
            dispatch = { request -> dispatched += request.name; ExecutionResult(true, "Screen observed.") },
            // A later model pass proposes the tap once it has the observation.
            nextCalls = { listOf(screenTapCall()) },
            onNeedsApproval = { request ->
                parked += request
                ExecutionResult(false, "Waiting for your approval.")
            }
        )
        assertEquals(listOf("screen_observe"), dispatched)
        assertEquals(listOf("screen_tap"), parked.map { it.name })
        assertTrue(outcome.stopped)
        assertFalse(outcome.completed)
    }

    @Test fun runNativeWithoutApprovalHandlerRejectsScreenProposals() = kotlinx.coroutines.runBlocking {
        val runner = ActionTurnRunner(MobileActionExecutor { error("no dispatch expected") })
        val plan = ActionTurnPlan.parse("what's on my screen") as ActionTurnPlan.Ready
        val outcome = runner.runNative(
            plan,
            listOf(screenTapCall()),
            dispatch = { ExecutionResult(true, "must not dispatch") },
            nextCalls = { emptyList() }
        )
        assertTrue(outcome.stopped)
        assertFalse(outcome.completed)
        assertTrue(outcome.receipts.isEmpty())
    }
}
