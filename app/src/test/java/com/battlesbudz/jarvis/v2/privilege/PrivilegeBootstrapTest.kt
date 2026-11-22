package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.*
import org.junit.Test

class PrivilegeBootstrapTest {
    @Test fun pairingMovesThroughDiscoveryAndSecurePairingOrReportsPreciseBlock() {
        val states = PairingStateMachine()
        val coordinator = PairingCoordinator(states, { adbEndpoint("127.0.0.1", 37123) }, null)
        val code = "123456".toCharArray()
        assertTrue(coordinator.pair(code).contains("transport is unavailable"))
        assertEquals(PairingStateMachine.State.BLOCKED, states.snapshot().state)
        assertEquals("Secure on-device ADB pairing transport is unavailable.", states.snapshot().reason)
        assertTrue(code.all { it == '\u0000' })
    }

    @Test fun pairingStateMachineRejectsIllegalMovesAndRequiresBlockReason() {
        val state = PairingStateMachine()
        try { state.move(PairingStateMachine.State.CONNECTED); fail("idle cannot become connected") }
        catch (_: IllegalArgumentException) { }
        try { state.move(PairingStateMachine.State.BLOCKED); fail("blocked needs a reason") }
        catch (_: IllegalArgumentException) { }
        state.move(PairingStateMachine.State.DISCOVERING)
        state.move(PairingStateMachine.State.BLOCKED, "endpoint unavailable")
        assertEquals("endpoint unavailable", state.snapshot().reason)
    }

    @Test fun endpointDiscoveryRejectsMissingOrInvalidPorts() {
        assertNull(adbEndpoint("127.0.0.1", null))
        assertNull(adbEndpoint("127.0.0.1", 65536))
        assertEquals(37123, adbEndpoint("127.0.0.1", 37123)?.port)
    }

    @Test fun helperContractRoundTripsOnlyCharteredCallsAndPermissionNames() {
        val backend = RecordingBackend()
        val helper = PrivilegeHelperContract(backend)
        assertEquals(7, helper.createVirtualDisplay("A2", 100, 200, 320))
        assertTrue(helper.enableAccessibilityService("com.battlesbudz.jarvis.v2/.actions.ScreenControlService"))
        assertTrue(helper.grantPermission("display"))
        assertEquals("present", helper.getPrivilegeState())
        try { helper.grantPermission("android.permission.WRITE_SECURE_SETTINGS"); fail("unknown grant") }
        catch (_: IllegalArgumentException) { }
        try { helper.enableAccessibilityService("other/.Service"); fail("unknown component") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf("display"), backend.grants)
    }

    @Test fun watchdogKeepsValidHelperRequestsConsentedRecoveryAndBlocksRevokedPairing() {
        assertEquals(RecoveryDecision.KEEP_HELPER, HelperRecoveryPolicy.decide(true, true, false))
        assertEquals(RecoveryDecision.REQUEST_CONSENTED_PAIRING, HelperRecoveryPolicy.decide(false, true, true))
        assertEquals(RecoveryDecision.BLOCK_PRIVILEGED_OPERATION, HelperRecoveryPolicy.decide(false, false, true))
        assertEquals(RecoveryDecision.BLOCK_PRIVILEGED_OPERATION, HelperRecoveryPolicy.decide(false, true, false))
    }

    @Test fun liveWatchdogKeepsResponsiveHelperAndAttemptsRecoveryOnlyWithConsent() {
        var responsive = true
        var consented = false
        var recoveryCalls = 0
        val watchdog = PrivilegeWatchdogController(
            helperResponsive = { responsive },
            pairingValid = { true },
            recoveryConsentGranted = { consented },
            requestConsentedRecovery = { recoveryCalls++; responsive = true; true }
        )
        assertEquals("present", watchdog.refresh().report())
        assertEquals(0, recoveryCalls)

        responsive = false
        assertTrue(watchdog.refresh().report().contains("requires user consent"))
        assertEquals(0, recoveryCalls)

        consented = true
        assertEquals("present", watchdog.refresh().report())
        assertEquals(1, recoveryCalls)
    }

    @Test fun liveWatchdogReportsRevokedPairingWithoutInvokingRecovery() {
        var recoveryCalls = 0
        val watchdog = PrivilegeWatchdogController(
            helperResponsive = { false },
            pairingValid = { false },
            recoveryConsentGranted = { true },
            requestConsentedRecovery = { recoveryCalls++; true }
        )
        assertEquals(
            "blocked: wireless-debugging pairing is absent or revoked",
            watchdog.refresh().report()
        )
        assertEquals(0, recoveryCalls)
    }

    @Test fun absentHelperRefusesOperationWithoutInvokingIt() {
        var invoked = false
        val result = PrivilegedOperationGate { false }.run { invoked = true; "operated" }
        assertFalse(invoked)
        assertEquals("The app-control helper is unavailable. The task stopped.", result.exceptionOrNull()?.message)
    }

    private class RecordingBackend : PrivilegeHelperBackend {
        val grants = mutableListOf<String>()
        override fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int) = 7
        override fun releaseVirtualDisplay(displayId: Int) = Unit
        override fun enableAccessibilityService(component: String) = true
        override fun grantPermission(permissionName: String): Boolean { grants += permissionName; return true }
        override fun state() = "present"
    }
}
