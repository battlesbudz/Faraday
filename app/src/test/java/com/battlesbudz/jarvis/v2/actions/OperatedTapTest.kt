package com.battlesbudz.jarvis.v2.actions

import android.os.Bundle
import com.battlesbudz.jarvis.v2.display.OperatedDisplayIdentity
import com.battlesbudz.jarvis.v2.perception.ObservedNode
import com.battlesbudz.jarvis.v2.perception.OperatedObservation
import org.junit.Assert.*
import org.junit.Test

class OperatedTapTest {
    private val display = OperatedDisplayIdentity(12, "task-1", "YouTube")
    private fun observation(hash: String = "screen-a", nodes: List<ObservedNode> = facts) =
        OperatedObservation(display, "com.google.android.youtube", hash, "fp", 1, nodes)
    private val facts = listOf(ObservedNode("android.widget.Button", "youtube:id/play", "Play", listOf("clickable")))
    private val key = OperatedNodeKey.ViewId("youtube:id/play")
    private fun node(bounds: OperatedNodeBounds = OperatedNodeBounds(0, 0, 20, 20), flags: Set<String> = setOf("clickable")) =
        LiveOperatedNode("android.widget.Button", "youtube:id/play", "Play", flags, bounds)

    private fun execute(
        source: OperatedObservation = observation(), live: OperatedObservation = source,
        liveDisplay: OperatedDisplayIdentity = display, target: OperatedNodeKey = key,
        payment: Boolean = false, cancelled: Boolean = false,
        liveNode: LiveOperatedNode? = node(), after: OperatedObservation? = observation("screen-b"),
        onInject: (Bundle) -> Boolean = { true },
    ): OperatedTapReceipt {
        val service = OperatedTapExecutor(
            OperatedLiveNodeReader { liveNode },
            OperatedInputInjector { _, event -> onInject(event) },
            OperatedObservationReader { after },
        )
        return service.execute(OperatedTapRequest(source, live, liveDisplay, target, payment, cancelled))
    }

    @Test fun movedLiveNodeUsesCurrentCenter() {
        var x = -1f; var y = -1f; var type: String? = null
        val receipt = execute(liveNode = node(OperatedNodeBounds(40, 60, 100, 120)), onInject = {
            x = it.getFloat("x"); y = it.getFloat("y"); type = it.getString("type"); true
        })
        assertTrue(receipt.succeeded)
        assertEquals("tapped", receipt.reason)
        assertEquals(70f, x); assertEquals(90f, y); assertEquals("tap", type)
        assertEquals(true, receipt.screenChanged)
    }

    @Test fun tapCoordinatesAreStoredAsFloats() {
        var xIsFloat = false; var yIsFloat = false
        execute(onInject = {
            xIsFloat = it.get("x") is Float
            yIsFloat = it.get("y") is Float
            true
        })
        assertTrue(xIsFloat)
        assertTrue(yIsFloat)
    }

    @Test fun missingTargetFailsWithoutInjection() {
        var calls = 0
        val result = execute(liveNode = null, onInject = { calls++; true })
        assertFalse(result.succeeded); assertEquals("target_missing", result.reason); assertEquals(0, calls)
    }

    @Test fun staleIdentityFailsWithoutInjection() {
        var calls = 0
        val result = execute(live = observation("screen-new"), onInject = { calls++; true })
        assertEquals("stale_observation", result.reason); assertEquals(0, calls)
    }

    @Test fun displayMismatchFailsWithoutInjection() {
        var calls = 0
        val result = execute(liveDisplay = OperatedDisplayIdentity(13, "task-1", "YouTube"), onInject = { calls++; true })
        assertEquals("display_mismatch", result.reason); assertEquals(0, calls)
    }

    @Test fun cancellationLeavesNamedReceiptAndDoesNotInject() {
        var calls = 0
        val result = execute(cancelled = true, onInject = { calls++; true })
        assertEquals("cancelled", result.reason); assertEquals(0, calls)
    }

    @Test fun helperFailureIsVisible() {
        val result = execute(onInject = { false })
        assertFalse(result.succeeded); assertEquals("helper_injection_failed", result.reason)
    }

    @Test fun passwordAndPaymentTargetsAreRefused() {
        var calls = 0
        val password = execute(liveNode = node(flags = setOf("password", "editable")), onInject = { calls++; true })
        val payment = execute(payment = true, onInject = { calls++; true })
        assertEquals("password_target_refused", password.reason)
        assertEquals("payment_marker_refused", payment.reason)
        assertEquals(0, calls)
    }

    @Test fun a7ReceivesTheObservationFactsUnmodified() {
        val request = ActionRequest("screen_tap", mapOf("target" to "n1"))
        val envelope = OperatedActionAdmission(request, observation())
        var received: List<ObservedNode>? = null
        admitOperatedAction(envelope, OperatedActionPolicyGate { actualRequest, facts ->
            assertSame(request, actualRequest); received = facts
            ActionControlPolicy.disposition(actualRequest)
        })
        assertEquals(facts, received)
        assertSame(envelope.facts, received)
    }

    @Test fun launchRuleCoversAllTaskLocations() {
        assertEquals(LaunchDecision.PROCEED, launchDecision(TaskLocation.NONE))
        assertEquals(LaunchDecision.BLOCKED_LIVE_TASK, launchDecision(TaskLocation.LIVE_VIRTUAL))
        assertEquals(LaunchDecision.END_DEAD_TASK_THEN_LAUNCH, launchDecision(TaskLocation.DEAD_VIRTUAL))
        assertEquals(LaunchDecision.BLOCKED_APP_OPEN_ON_MAIN_SCREEN, launchDecision(TaskLocation.MAIN_DISPLAY))
    }
}
