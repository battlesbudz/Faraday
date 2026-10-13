package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class ToolTaskLedgerTest {
    @Test fun staleGenerationCannotPublishCompletion() {
        var now = 1L
        val ledger = ToolTaskLedger { now++ }
        val task = ledger.create(ActionRequest("read_battery"))
        val running = checkNotNull(ledger.transition(task.id, task.generation, ToolTaskState.RUNNING))
        assertNull(ledger.transition(task.id, task.generation, ToolTaskState.SUCCEEDED, "stale"))
        assertEquals(ToolTaskState.RUNNING, ledger.get(task.id)?.state)
        assertNotNull(ledger.transition(task.id, running.generation, ToolTaskState.SUCCEEDED, "85%"))
    }

    @Test fun terminalAttemptCannotReplay() {
        val ledger = ToolTaskLedger()
        val task = ledger.create(ActionRequest("read_battery"))
        val done = checkNotNull(ledger.transition(task.id, 0, ToolTaskState.SUCCEEDED, "done"))
        assertNull(ledger.transition(task.id, done.generation, ToolTaskState.RUNNING))
    }

    @Test fun unknownCompletionFinishIsJournaledAsUnknownOutcome() {
        // A no-exception launch submission without foreground evidence must
        // remain submitted/unverified in the journal: UNKNOWN_OUTCOME, never
        // SUCCEEDED.
        val ledger = ToolTaskLedger()
        val task = ledger.create(ActionRequest("open_app", mapOf("app" to "Settings")))
        val running = checkNotNull(ledger.transition(task.id, task.generation, ToolTaskState.RUNNING))
        val receipt = verifiedLaunchReceipt(
            "Settings", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = false
        )
        val finished = checkNotNull(ledger.finish(running, receipt))
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, finished.state)
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, finished.resultOutcome)
        assertTrue(
            "the journal must keep the honest receipt: ${finished.result}",
            finished.result!!.contains("could not be confirmed")
        )
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(task.id)?.state)
    }
}
