package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PairingScreenTest {
    @Test fun readNormalizesCodesAndRejectsAmbiguousValues() {
        assertEquals("123456", PairingScreen.read(listOf("123456")).code)
        assertEquals("123456", PairingScreen.read(listOf("123 456")).code)
        assertNull(PairingScreen.read(listOf("pairing dialog")).code)
        assertNull(PairingScreen.read(listOf("123456", "654321")).code)
    }

    @Test fun readFindsAnUnambiguousPortInTheSameWindow() {
        assertEquals(37123, PairingScreen.read(listOf("192.168.1.5:37123")).port)
        assertNull(PairingScreen.read(listOf("192.168.1.5:37123", "192.168.1.5:37124")).port)
        assertNull(PairingScreen.read(listOf("256.1.1.1:37123")).port)
        assertEquals(PairingScreenReading("123456", 37123), PairingScreen.read(listOf("123456", "192.168.1.5:37123")))
    }

    @Test fun attemptRequiresAnUnusedCodePortAndWaitingState() {
        val reading = PairingScreenReading("123456", 37123)
        assertNull(PairingScreen.attempt(reading, true, true, setOf("123456")))
        assertNull(PairingScreen.attempt(reading, true, false, emptySet()))
        assertNull(PairingScreen.attempt(PairingScreenReading("123456", null), false, true, emptySet()))
        assertNotNull(PairingScreen.attempt(reading, false, true, emptySet()))
        assertEquals(PairingAttempt("123456", 37123), PairingScreen.attempt(reading, false, true, emptySet()))
    }
}
