package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingWizardFlowTest {
    @Test fun stepsStayInSetupOrder() {
        assertEquals(
            listOf(
                PairingWizardFlow.Step.DeveloperOptions,
                PairingWizardFlow.Step.UsbDebugging,
                PairingWizardFlow.Step.WirelessDebugging,
                PairingWizardFlow.Step.PairingCode,
                PairingWizardFlow.Step.Connect,
            ),
            PairingWizardFlow.steps,
        )
    }

    @Test fun satisfiedSwitchesAreDoneAndNeverCurrent() {
        val view = PairingWizardFlow.evaluate(input(
            developerOptions = true,
            usbDebugging = true,
            wirelessDebugging = true,
        ))
        listOf(
            PairingWizardFlow.Step.DeveloperOptions,
            PairingWizardFlow.Step.UsbDebugging,
            PairingWizardFlow.Step.WirelessDebugging,
        ).forEach { step ->
            assertEquals(PairingWizardFlow.Mark.Done, view.marks[step])
            assertTrue(view.currentStep != step)
        }
        assertEquals(PairingWizardFlow.Step.PairingCode, view.currentStep)
    }

    @Test fun nullSwitchReadingsDoNotBlockAndConfirmedFalseReadingAdvances() {
        val unknown = PairingWizardFlow.evaluate(input(developerOptions = null, usbDebugging = null, wirelessDebugging = null))
        assertEquals(PairingWizardFlow.Step.PairingCode, unknown.currentStep)
        assertEquals(PairingWizardFlow.Mark.Done, unknown.marks[PairingWizardFlow.Step.DeveloperOptions])

        val confirmed = PairingWizardFlow.evaluate(input(
            developerOptions = false,
            confirmed = setOf(PairingWizardFlow.Step.DeveloperOptions),
        ))
        assertEquals(PairingWizardFlow.Step.UsbDebugging, confirmed.currentStep)
        assertEquals(PairingWizardFlow.Mark.Done, confirmed.marks[PairingWizardFlow.Step.DeveloperOptions])
    }

    @Test fun helperPresentSucceedsEvenWhenSwitchesReadOff() {
        val view = PairingWizardFlow.evaluate(input(
            developerOptions = false,
            usbDebugging = false,
            wirelessDebugging = false,
            helperPresent = true,
        ))
        assertEquals(PairingWizardFlow.Phase.Success, view.phase)
        assertNull(view.currentStep)
    }

    @Test fun pairedButMissingHelperIsConnecting() {
        val view = PairingWizardFlow.evaluate(input(paired = true))
        assertEquals(PairingWizardFlow.Step.Connect, view.currentStep)
        assertEquals(PairingWizardFlow.Phase.Working, view.phase)
    }

    @Test fun accessibilitySelectsAutomaticCodeReading() {
        val automatic = PairingWizardFlow.evaluate(input(accessibilityRunning = true))
        val manual = PairingWizardFlow.evaluate(input(accessibilityRunning = false))
        assertTrue(automatic.autoRead)
        assertFalse(manual.autoRead)
    }

    @Test fun notificationNeedTracksReaderPermissionAndChannel() {
        assertTrue(PairingWizardFlow.evaluate(input(
            accessibilityRunning = false,
            notificationsAllowed = false,
        )).needsNotifications)
        assertFalse(PairingWizardFlow.evaluate(input(
            accessibilityRunning = true,
            notificationsAllowed = false,
        )).needsNotifications)
        assertTrue(PairingWizardFlow.evaluate(input(
            accessibilityRunning = false,
            notificationsAllowed = true,
            pairingChannelBlocked = true,
        )).needsNotifications)
    }

    @Test fun failuresPointOnlyToKnownCauses() {
        assertEquals(
            PairingWizardFlow.Step.PairingCode,
            PairingWizardFlow.evaluate(input(lastFailure = "Wireless-debugging pairing code was rejected.")).suspect,
        )
        assertEquals(
            PairingWizardFlow.Step.WirelessDebugging,
            PairingWizardFlow.evaluate(input(lastFailure = "wireless-debugging connect endpoint is unavailable")).suspect,
        )
        assertNull(PairingWizardFlow.evaluate(input(lastFailure = "The stored identity could not be read.")).suspect)
    }

    @Test fun wifiMissingAppearsOnlyAtWirelessStep() {
        val before = PairingWizardFlow.evaluate(input(wifiConnected = false, developerOptions = false))
        assertEquals(PairingWizardFlow.Step.DeveloperOptions, before.currentStep)
        assertFalse(before.wifiMissing)
        val wireless = PairingWizardFlow.evaluate(input(
            wifiConnected = false,
            developerOptions = true,
            usbDebugging = true,
            wirelessDebugging = false,
        ))
        assertEquals(PairingWizardFlow.Step.WirelessDebugging, wireless.currentStep)
        assertTrue(wireless.wifiMissing)
        val missingWifi = PairingWizardFlow.evaluate(input(
            wifiConnected = false,
            developerOptions = true,
            usbDebugging = true,
            wirelessDebugging = true,
        ))
        assertEquals(PairingWizardFlow.Step.PairingCode, missingWifi.currentStep)
        assertFalse(missingWifi.wifiMissing)
    }

    private fun input(
        developerOptions: Boolean? = true,
        usbDebugging: Boolean? = true,
        wirelessDebugging: Boolean? = true,
        wifiConnected: Boolean = true,
        accessibilityRunning: Boolean = false,
        notificationsAllowed: Boolean = true,
        pairingChannelBlocked: Boolean = false,
        helperPresent: Boolean = false,
        paired: Boolean = false,
        armed: Boolean = false,
        attemptInFlight: Boolean = false,
        dialogFound: Boolean = false,
        lastFailure: String? = null,
        confirmed: Set<PairingWizardFlow.Step> = emptySet(),
    ) = PairingWizardFlow.Input(
        developerOptions, usbDebugging, wirelessDebugging, wifiConnected,
        accessibilityRunning, notificationsAllowed, pairingChannelBlocked,
        helperPresent, paired, armed, attemptInFlight, dialogFound, lastFailure, confirmed,
    )
}
