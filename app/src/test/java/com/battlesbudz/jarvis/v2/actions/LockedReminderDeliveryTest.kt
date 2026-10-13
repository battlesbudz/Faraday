package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class LockedReminderDeliveryTest {
    private val notification = ActionRequest("post_notification", mapOf("title" to "Reminder", "text" to "Private reminder"))

    @Test fun onlyLiveAuthorizedNotificationMayPassLockedGate() {
        var authorized = true
        val gate = DeviceLockGate({ true }, privateNotificationAuthorized = { authorized })
        assertEquals(LockVerdict.ALLOWED, gate.check(notification))
        for (request in listOf(ActionRequest("set_volume", mapOf("level" to "25")), ActionRequest("open_app", mapOf("app" to "Maps")),
            ActionRequest("screen_type", mapOf("target" to "field", "text" to "sensitive")), ActionRequest("create_reminder", mapOf("message" to "secret", "at_ms" to "3000")))) {
            assertEquals(LockVerdict.NEEDS_UNLOCK, gate.check(request))
        }
        authorized = false
        assertEquals(LockVerdict.NEEDS_UNLOCK, gate.check(notification))
        assertEquals(LockVerdict.NEEDS_UNLOCK, DeviceLockGate({ true }).check(notification))
        assertEquals(LockVerdict.ALLOWED, DeviceLockGate({ false }).check(notification))
    }

    @Test fun storedSourceDenialStillBlocksAuthorizedDueNotification() {
        val store = InMemoryToolTaskStore()
        val ledger = ToolTaskLedger(store) { 1000L }
        ledger.recordSourceDenial("reminders")
        val grant = ledger.grant("workflow:private-reminder", listOf(notification), 5000L)
        val group = ledger.admit(listOf(notification), "workflow:due", ToolAuthority.ROUTINE, grant.id, resumeAfterRestart = false)
        var effects = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor { effects++; ExecutionResult(true, "posted") },
            sourceAccess = ToolSourceAccess(ledger), lockGate = DeviceLockGate({ true }, privateNotificationAuthorized = { true }))
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION,
            pipeline.executeAttempt(checkNotNull(ledger.get(group.attemptIds.single()))).outcome)
        assertEquals(0, effects)
    }

    @Test fun grantedDueNotificationRunsOnceWhileOtherLockedActionsStayBlocked() {
        val ledger = ToolTaskLedger(InMemoryToolTaskStore()) { 1000L }
        val grant = ledger.grant("workflow:private-reminder", listOf(notification), 5000L)
        val group = ledger.admit(listOf(notification), "workflow:due", ToolAuthority.ROUTINE, grant.id, resumeAfterRestart = false)
        var effects = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor { effects++; ExecutionResult(true, "posted") },
            sourceAccess = ToolSourceAccess(ledger), lockGate = DeviceLockGate({ true }, privateNotificationAuthorized = { true }))
        assertTrue(pipeline.executeAttempt(checkNotNull(ledger.get(group.attemptIds.single()))).succeeded)
        assertFalse(pipeline.executeAttempt(checkNotNull(ledger.get(group.attemptIds.single()))).succeeded)
        assertEquals(1, effects)
        assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, pipeline.execute(ActionRequest("set_volume", mapOf("level" to "10"))).outcome)
        assertEquals(1, effects)
    }
}
