package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInterruptionPolicyTest {
    @Test fun idlePhoneNeverReservesTheMicrophone() {
        val policy = MicrophoneInterruptionPolicy()
        repeat(120) { assertFalse(policy.update(false, it * 500L, ownReleased = false)) }
    }
    @Test fun longExternalRecordingCannotExpireTheHold() {
        val policy = MicrophoneInterruptionPolicy()
        assertTrue(policy.update(true, 0))
        assertTrue(policy.update(true, 120000))
        assertTrue(policy.update(false, 120500))
        assertTrue(policy.update(false, 123499))
        assertFalse(policy.update(false, 123500))
    }
    @Test fun failedSixtyMillisecondAttemptDoesNotCauseImmediateReacquisition() {
        val policy = MicrophoneInterruptionPolicy()
        assertTrue(policy.update(true, 0, ownReleased = false))
        assertTrue(policy.update(false, 60, ownReleased = false))
        assertTrue(policy.update(false, 153, ownReleased = true))
        assertTrue(policy.update(false, 1560)) // The old build reopened here.
        assertTrue(policy.update(false, 3152))
        assertFalse(policy.update(false, 3153))
    }
    @Test fun teardownTimeDoesNotCountAsFreeMicrophoneTime() {
        val policy = MicrophoneInterruptionPolicy()
        policy.update(true, 0)
        assertTrue(policy.update(false, 10000, ownReleased = false))
        assertTrue(policy.update(false, 12000, ownReleased = true))
        assertTrue(policy.update(false, 14999))
        assertFalse(policy.update(false, 15000))
    }
    @Test fun repeatedRequestsAndRecorderGapsRestartTheQuietInterval() {
        val policy = MicrophoneInterruptionPolicy()
        policy.update(true, 0)
        assertTrue(policy.update(false, 1000))
        assertTrue(policy.update(true, 3500))
        assertTrue(policy.update(false, 4000))
        assertTrue(policy.update(false, 6999))
        assertFalse(policy.update(false, 7000))
    }
    @Test fun unusedMicrophoneButtonRequestExpiresWithoutAWindowChange() {
        val policy = MicrophoneInterruptionPolicy()
        policy.update(true, 0)
        assertTrue(policy.update(false, 2000))
        assertTrue(policy.update(false, 4000))
        assertFalse(policy.update(false, 6000))
    }
}
