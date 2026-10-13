package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * The deterministic text router must not produce browse actions while the
 * M4 browser runtime is unwired: "browse to X" stays ordinary speech, and
 * only returns as a browse_open step once the gate opens after an Android
 * journey proves the real path.
 */
class ActionTurnPlanBrowseGateTest {
    @Test fun browseToIsNotAnActionWhileUnwired() {
        MobileToolCatalog.BrowserRuntimeGate.wired = false
        val plan = ActionTurnPlan.parse("browse to example.com")
        assertTrue("An unwired browse request must not plan an action, got $plan",
            plan is ActionTurnPlan.NotAction)
    }

    @Test fun readThisPageIsNotAnActionWhileUnwired() {
        MobileToolCatalog.BrowserRuntimeGate.wired = false
        val plan = ActionTurnPlan.parse("read this page")
        assertTrue("An unwired page-read request must not plan an action, got $plan",
            plan is ActionTurnPlan.NotAction)
    }

    @Test fun browseToPlansBrowseOpenOnceWired() {
        val gate = MobileToolCatalog.BrowserRuntimeGate
        gate.wired = true
        try {
            val plan = ActionTurnPlan.parse("browse to example.com")
            val ready = plan as? ActionTurnPlan.Ready
                ?: throw AssertionError("A wired browse request must plan, got $plan")
            assertEquals(1, ready.steps.size)
            assertEquals("browse_open", ready.steps.single().request.name)
            assertEquals("example.com", ready.steps.single().request.arguments["url"])
        } finally {
            gate.wired = false
        }
    }

    @Test fun readThisPagePlansBrowseReadOnceWired() {
        val gate = MobileToolCatalog.BrowserRuntimeGate
        gate.wired = true
        try {
            val plan = ActionTurnPlan.parse("read this page")
            val ready = plan as? ActionTurnPlan.Ready
                ?: throw AssertionError("A wired page-read request must plan, got $plan")
            assertEquals("browse_read", ready.steps.single().request.name)
        } finally {
            gate.wired = false
        }
    }

    @Test fun unrelatedActionsUnaffectedByGate() {
        MobileToolCatalog.BrowserRuntimeGate.wired = false
        val plan = ActionTurnPlan.parse("open youtube.com")
        val ready = plan as? ActionTurnPlan.Ready
            ?: throw AssertionError("open_website must still plan while browse is gated, got $plan")
        assertEquals("open_website", ready.steps.single().request.name)
    }
}
