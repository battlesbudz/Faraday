package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 1 (user reports): an unconfirmed step is terminal for the
 * journal but must never be projected as "done". The projection keeps the
 * honest per-step receipt and says what could not be confirmed, in chat
 * bubbles and notifications alike.
 */
class TaskProgressProjectionTest {

    private fun journalWithFinishedAttempt(result: ExecutionResult): Pair<ToolTaskLedger, ToolTaskGroup> {
        val ledger = ToolTaskLedger()
        val group = ledger.admit(
            listOf(ActionRequest("open_app", mapOf("app" to "Settings"))),
            "conversation-1", resumeAfterRestart = false
        )
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        // The production claim path (QUEUED -> RUNNING) owns the exactly-once
        // transition for grouped attempts; a raw transition() to RUNNING is
        // refused by design (double-claim hardening), so the test exercises
        // the same seam the dispatcher uses.
        val running = checkNotNull(ledger.claim(attempt.id, attempt.generation))
        ledger.finish(running, result)
        return ledger to group
    }

    @Test fun unconfirmedLaunchIsNotProjectedAsDone() {
        val receipt = verifiedLaunchReceipt(
            "Settings", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = false
        )
        val (ledger, group) = journalWithFinishedAttempt(receipt)
        val projection = checkNotNull(TaskProgressProjector().project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.FINISHED, projection.state)
        assertTrue("the projection stays terminal so the task closes out", projection.isTerminal)
        assertFalse("an unconfirmed step must never read as done: ${projection.statusLine}",
            projection.statusLine.contains("done"))
        assertTrue("the status line must name the unconfirmed step: ${projection.statusLine}",
            projection.statusLine.contains("could not be confirmed"))
        assertTrue("the honest per-step receipt is retained: ${projection.stepReceipts}",
            projection.stepReceipts.any { it.contains("could not be confirmed") })
    }

    @Test fun fullyConfirmedGroupStillProjectsDone() {
        val (ledger, group) = journalWithFinishedAttempt(ExecutionResult(true, "Opened Settings."))
        val projection = checkNotNull(TaskProgressProjector().project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.FINISHED, projection.state)
        assertEquals("open_app: done (1 of 1 steps).", projection.statusLine)
    }
}
