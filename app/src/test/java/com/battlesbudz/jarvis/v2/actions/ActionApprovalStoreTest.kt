package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class ActionApprovalStoreTest {
    @Test fun changedActionInvalidatesOldApproval() {
        val store = ActionApprovalStore { 10L }
        val old = store.request("task", "step", "native", ActionRequest("set_volume", mapOf("level" to "20")), 1)
        val fresh = store.request("task", "step", "native", ActionRequest("set_volume", mapOf("level" to "30")), 1)
        assertEquals(ApprovalDecision.STALE, store.consume(old.id, old.fingerprint))
        assertEquals(ApprovalDecision.APPROVED, store.consume(fresh.id, fresh.fingerprint))
    }

    @Test fun fingerprintBindsNormalizedArguments() {
        val store = ActionApprovalStore()
        val request = store.request("task", "step", "native", ActionRequest("open_app", mapOf("app" to "Maps")), 1)
        assertEquals(ApprovalDecision.STALE, store.consume(request.id, "wrong"))
        assertEquals(ApprovalDecision.APPROVED, store.consume(request.id, request.fingerprint))
    }

    @Test fun spokenYesOnlyConsumesSingleActiveQuestion() {
        val store = ActionApprovalStore()
        store.request("a", "one", "native", ActionRequest("set_volume", mapOf("level" to "20")), 1)
        store.request("b", "two", "native", ActionRequest("open_app", mapOf("app" to "Maps")), 1)
        assertEquals(ApprovalDecision.AMBIGUOUS, store.spokenYes())
    }
}
