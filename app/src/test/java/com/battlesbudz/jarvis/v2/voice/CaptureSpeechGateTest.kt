package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class CaptureSpeechGateTest {
    @Test fun newTurnRetainsRoomCalibrationWithoutMutingImmediateRealSpeech() {
        val profile = CaptureNoiseProfile()
        val first = CaptureSpeechGate(profile)
        repeat(3) { first.accept(SpeechDecision(false, .01f), 100.0, it * 100L) }
        val followup = CaptureSpeechGate(profile)
        assertFalse(followup.accept(SpeechDecision(true, .643f), 104.0, 20_000).isSpeech)
        assertTrue(followup.accept(SpeechDecision(true, .89f), 643.0, 20_100).isSpeech)
        followup.accept(SpeechDecision(false, .01f), 30.0, 20_200)
        assertTrue(followup.accept(SpeechDecision(true, .6f), 70.0, 20_300).isSpeech)
        assertEquals(30.0, profile.floorRms, .001)
    }
    @Test fun suppressedZeroInputCannotPassARecurrentVadTailOrRetainStaleProfile() {
        val profile = CaptureNoiseProfile().also { it.floorRms = 100.0 }
        val gate = CaptureSpeechGate(profile)
        gate.accept(SpeechDecision(false, .01f), 0.0, 0)
        assertEquals(0.0, profile.floorRms, .001)
        assertFalse(gate.accept(SpeechDecision(true, .99f), 0.0, 100).isSpeech)
        assertTrue(gate.accept(SpeechDecision(true, .9f), 12.0, 200).isSpeech)
    }
    private fun calibrated(rms: Double) = CaptureSpeechGate().also { gate ->
        repeat(3) { gate.accept(SpeechDecision(false, .01f), rms, it * 100L) }
    }
    @Test fun weakVadAtRoomFloorIsNotSpeech() {
        val gate = calibrated(29.0)
        assertEquals(SpeechDecision(false, 0f), gate.accept(SpeechDecision(true, .525f), 44.0, 300))
        assertEquals(29.0, gate.noiseFloorRms, .001)
    }
    @Test fun quietSpeechAboveFloorAndStrongWhisperArePreserved() {
        val gate = calibrated(29.0)
        val weak = SpeechDecision(false, .23f)
        assertEquals(weak, gate.accept(weak, 70.0, 300))
        val strong = SpeechDecision(true, .95f)
        assertEquals(strong, gate.accept(strong, 45.0, 400))
    }
    @Test fun singleLoudCalibrationFrameCannotSuppressOpeningSpeech() {
        val gate = CaptureSpeechGate()
        gate.accept(SpeechDecision(false, .01f), 1694.0, 0)
        val speech = SpeechDecision(true, .6f)
        assertEquals(speech, gate.accept(speech, 400.0, 100))
    }
    @Test fun louderSpeechCannotRaiseTheNoiseFloor() {
        val gate = calibrated(30.0)
        repeat(20) { gate.accept(SpeechDecision(true, .99f), 2000.0, 300L + it * 100) }
        assertEquals(30.0, gate.noiseFloorRms, .001)
        assertTrue(gate.accept(SpeechDecision(true, .6f), 70.0, 2400).isSpeech)
    }
    @Test fun calibratedRoomFloorSurvivesLongSpeechAndRejectsVadTailForEitherEngine() {
        val gate = calibrated(131.0)
        repeat(80) { gate.accept(SpeechDecision(true, .99f), 2000.0, 300L + it * 100) }
        assertEquals(131.0, gate.noiseFloorRms, .001)
        assertFalse(gate.accept(SpeechDecision(true, .806f), 132.0, 8400).isSpeech)
        assertFalse(gate.accept(SpeechDecision(true, .596f), 141.0, 8500).isSpeech)
        assertTrue(gate.accept(SpeechDecision(true, .99f), 2700.0, 8600).isSpeech)
    }
    @Test fun quieterRoomObservationImmediatelyLowersAnOldHighFloor() {
        val gate = calibrated(1694.0)
        gate.accept(SpeechDecision(false, .01f), 130.0, 5000)
        assertEquals(130.0, gate.noiseFloorRms, .001)
        assertTrue(gate.accept(SpeechDecision(true, .79f), 491.0, 5100).isSpeech)
    }
}
