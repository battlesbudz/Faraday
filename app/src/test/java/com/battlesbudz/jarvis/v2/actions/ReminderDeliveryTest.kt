package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 1 (reminder delivery) regression test, routed through the real
 * production orchestration: create -> alarm -> claim -> engine dispatch via
 * the production [RoutineStepDispatcher] seam (routine-grant admission
 * followed by [JournaledActionPipeline.executeAttempt]) -> complete.
 *
 * The previous version of this test manually claimed the occurrence and
 * dispatched with pipeline.execute(request) — but production
 * dispatchWorkflowStep uses routine-grant admission followed by
 * executeAttempt, where the original double claim happened. That version
 * would pass even if the production pre-claim were reintroduced; this
 * version fails in that state because executeAttempt owns the claim and a
 * pre-claimed attempt is rejected before any effect runs.
 * [preClaimBeforeExecuteAttemptDispatchesNothing] pins that fail-closed
 * property directly.
 *
 * Boundary notes: the Android alarm itself is faked through the
 * coordinator's injected scheduler lambda (the designed seam). The
 * receiver-side claim/complete flow is the real [WorkflowLedger]. The
 * notification effect runs through a fake [MobileActionExecutor] whose
 * PostNotification success mirrors [AndroidMobileActionExecutor]'s
 * platform-boundary receipt ("Posted the reminder notification.").
 */
class ReminderDeliveryTest {

    /** Fake device effect: records notifications; the success receipt mirrors the Android executor's. */
    private class RecordingExecutor(val posted: MutableList<Pair<String, String>>) : MobileActionExecutor {
        override fun execute(action: MobileAction): ExecutionResult = when (action) {
            is MobileAction.PostNotification -> {
                posted += action.title to action.text
                // Mirrors AndroidMobileActionExecutor's PostNotification
                // success shape: the platform-boundary assertion.
                ExecutionResult(true, "Posted the reminder notification.")
            }
            // JUnit's fail() is Kotlin Unit, not Nothing: the when must
            // produce an ExecutionResult, so throw a typed assertion.
            else -> throw AssertionError("unexpected action dispatched: $action")
        }
    }

    @Test fun reminderDeliversExactlyOnceWithTerminalReceipt() {
        var nowMs = 1_700_000_000_000L
        val store = InMemoryToolTaskStore()
        val ledger = WorkflowLedger(store, now = { nowMs })
        val phoneActionLedger = ToolTaskLedger(store, now = { nowMs })
        val armed = mutableListOf<WorkflowOccurrence>()
        val scheduler: (WorkflowOccurrence) -> WorkflowAlarmScheduler.Scheduled = { occurrence ->
            armed += occurrence
            WorkflowAlarmScheduler.Scheduled(WorkflowScheduling.AlarmMode.EXACT, null)
        }
        val coordinator = ReminderCoordinator(ledger, scheduler, now = { nowMs })

        val message = "Take out the trash"
        val created = coordinator.createReminder(message, nowMs + 3_600_000)
        assertTrue("createReminder must succeed: ${created.message}", created.succeeded)

        val workflowId = ledger.list().single().id
        val occurrences = ledger.occurrencesFor(workflowId)
        assertEquals("exactly one scheduled occurrence", 1, occurrences.size)
        val occurrence = occurrences.single()
        assertEquals(WorkflowOccurrenceState.SCHEDULED, occurrence.state)
        assertEquals("the alarm is armed exactly once", 1, armed.size)
        assertEquals(occurrence.id, armed.single().id)

        // The alarm fires: claim atomically, then run the real definition.
        nowMs += 3_600_000
        val claimed = ledger.claimDueOccurrence(occurrence.id)
        assertNotNull("a due occurrence must be claimable", claimed)
        assertNull("a duplicate claim must not re-fire", ledger.claimDueOccurrence(occurrence.id))

        val posted = mutableListOf<Pair<String, String>>()
        val executor = RecordingExecutor(posted)
        // The production routine-dispatch seam: routine-grant admission
        // followed by executeAttempt — the same object
        // WorkflowCoordinator.dispatchWorkflowStep delegates to.
        val dispatcher = RoutineStepDispatcher(
            workflowLedger = ledger,
            phoneActionLedger = phoneActionLedger,
            executorFactory = { executor }
        )
        val definition = ledger.definitionFor(checkNotNull(claimed))!!
        val outcome = WorkflowEngine(now = { nowMs }).run(definition, dispatch = { request ->
            dispatcher.dispatch(checkNotNull(claimed), request)
        })
        assertTrue("the engine run must complete: $outcome",
            outcome is WorkflowRunOutcome.Completed && outcome.succeeded)
        assertEquals("exactly one notification", 1, posted.size)
        assertEquals("Reminder", posted.single().first)
        assertEquals(message, posted.single().second)
        // The dispatch went through the journaled pipeline: one admitted
        // attempt, terminal with its receipt.
        assertEquals(ToolTaskState.SUCCEEDED, phoneActionLedger.snapshot().single().state)

        // Terminal receipt: completing the occurrence is the receiver's
        // terminal step, and a redelivered alarm then claims nothing, so a
        // second engine run can never double-post.
        assertTrue(ledger.completeOccurrence(occurrence.id, true, "Posted: $message"))
        assertEquals(WorkflowOccurrenceState.SUCCEEDED, ledger.occurrence(occurrence.id)!!.state)
        assertNull("a redelivered alarm must claim nothing", ledger.claimDueOccurrence(occurrence.id))
        assertEquals("no second delivery", 1, posted.size)

        val receipt = ledger.receiptsFor(workflowId).single { it.kind == WorkflowReceiptKind.COMPLETED }
        assertTrue("the completion receipt names the reminder text: ${receipt.message}",
            receipt.message.contains(message))
    }

    @Test fun preClaimBeforeExecuteAttemptDispatchesNothing() {
        // The reintroduced finding-1 bug, simulated: an extra claim inserted
        // before executeAttempt. executeAttempt owns the claim and only
        // accepts QUEUED/READY/WAITING_APPROVAL, so the pre-claimed (RUNNING)
        // attempt is rejected and no effect runs. If production ever gains a
        // pre-claim, reminderDeliversExactlyOnceWithTerminalReceipt fails
        // because nothing posts.
        var nowMs = 1_700_000_000_000L
        val store = InMemoryToolTaskStore()
        val phoneActionLedger = ToolTaskLedger(store, now = { nowMs })
        val posted = mutableListOf<Pair<String, String>>()
        val pipeline = JournaledActionPipeline(phoneActionLedger, RecordingExecutor(posted))
        val request = ActionRequest(
            "post_notification",
            mapOf("title" to "Reminder", "text" to "Take out the trash")
        )
        val grant = phoneActionLedger.grant("routine-1", listOf(request), nowMs + 3_600_000)
        val group = phoneActionLedger.admit(
            listOf(request), "workflow:occ-1",
            authority = ToolAuthority.ROUTINE, grantId = grant.id
        )
        val attempt = checkNotNull(phoneActionLedger.get(group.attemptIds.single()))
        // The bug: an extra claim before executeAttempt.
        assertNotNull("the pre-claim itself succeeds", phoneActionLedger.claim(attempt.id, attempt.generation))
        val result = pipeline.executeAttempt(attempt)
        assertFalse("a pre-claimed attempt must not dispatch: $result", result.succeeded)
        assertTrue("no notification may post, was: $posted", posted.isEmpty())
    }
}
