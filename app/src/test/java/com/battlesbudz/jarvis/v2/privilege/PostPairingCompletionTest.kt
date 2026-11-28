package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPairingCompletionTest {
    @Test fun enablesOnlyForPresentConsentedHelperWithAccessibilityOff() {
        assertTrue(PostPairingCompletion.shouldAttemptEnable(true, true, false))
        assertFalse(PostPairingCompletion.shouldAttemptEnable(false, true, false))
        assertFalse(PostPairingCompletion.shouldAttemptEnable(true, false, false))
        assertFalse(PostPairingCompletion.shouldAttemptEnable(true, true, true))
    }
}
