package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class ActionDispatchGateTest {
    @Test fun dispatchRequiresExactFreshApproval() {
        val approvals = ActionApprovalStore()
        val ledger = ToolTaskLedger()
        val gate = ActionDispatchGate(approvals, ledger)
        val task = ledger.create(ActionRequest("set_volume", mapOf("level" to "25")))
        val pending = gate.prepare(task)
        val forged = pending.copy(approval = pending.approval.copy(fingerprint = "bad"))
        assertNull(gate.authorize(forged))
        assertEquals(ToolTaskState.WAITING_APPROVAL, ledger.get(task.id)?.state)
    }

    @Test fun successfulApprovalTransitionsRunningThenTerminalOnce() {
        val approvals = ActionApprovalStore()
        val ledger = ToolTaskLedger()
        val gate = ActionDispatchGate(approvals, ledger)
        val task = ledger.create(ActionRequest("read_battery"))
        val pending = gate.prepare(task)
        val running = checkNotNull(gate.authorize(pending))
        assertEquals(ToolTaskState.RUNNING, running.state)
        val done = checkNotNull(gate.complete(running, ExecutionResult(true, "Battery 80%")))
        assertEquals(ToolTaskState.SUCCEEDED, done.state)
        assertNull(gate.complete(running, ExecutionResult(true, "replayed")))
    }

    @Test fun unknownCompletionIsTerminalAndNotRetried() {
        val approvals = ActionApprovalStore()
        val ledger = ToolTaskLedger()
        val gate = ActionDispatchGate(approvals, ledger)
        val task = ledger.create(ActionRequest("open_app", mapOf("app" to "Maps")))
        val running = checkNotNull(gate.authorize(gate.prepare(task)))
        val ended = checkNotNull(gate.complete(running,
            ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION, "unknown")))
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ended.state)
        assertNull(ledger.transition(ended.id, ended.generation, ToolTaskState.RUNNING))
    }
}
