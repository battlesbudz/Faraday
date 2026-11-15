package com.battlesbudz.jarvis.v2.runtime.turn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnPreparationTest {
    /**
     * The build-1624 first-turn defect: a call starts with the video
     * toggle OFF (onCallBegan resets it), the user turns it on from the
     * call screen, and the first question must still get a vision-capable
     * engine — capability follows the model, the attach decision follows
     * the live toggle at finalization.
     */
    @Test fun firstCallTurnIsVisionCapableEvenThoughTheCallStartsWithVideoOff() {
        assertTrue(snapshotVisionCapability(
            supportsVision = true, comparisonTrial = false, videoToggleOn = false))
    }

    @Test fun callTurnWithVideoOnIsVisionCapable() {
        assertTrue(snapshotVisionCapability(
            supportsVision = true, comparisonTrial = false, videoToggleOn = true))
    }

    @Test fun textOnlyModelIsNeverVisionCapable() {
        assertFalse(snapshotVisionCapability(
            supportsVision = false, comparisonTrial = false, videoToggleOn = false))
        assertFalse(snapshotVisionCapability(
            supportsVision = false, comparisonTrial = false, videoToggleOn = true))
    }

    @Test fun comparisonTrialsKeepTheToggleSample() {
        assertFalse(snapshotVisionCapability(
            supportsVision = true, comparisonTrial = true, videoToggleOn = false))
        assertTrue(snapshotVisionCapability(
            supportsVision = true, comparisonTrial = true, videoToggleOn = true))
    }
}
