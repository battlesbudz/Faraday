package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class RecognitionAudioEvidenceTest {
    @Test fun streamedInputIsCopiedInOrderAndBounded() {
        val evidence = RecognitionAudioEvidence(6)
        val first = byteArrayOf(1, 2)
        evidence.record(first, false); first[0] = 9
        evidence.record(byteArrayOf(3, 4, 5, 6, 7, 8), false)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), evidence.snapshot())
        assertTrue(evidence.truncated)
    }
    @Test fun overlappingWhisperWindowsAreNotConcatenatedAndRecoveryReplacesOldInput() {
        val evidence = RecognitionAudioEvidence(6)
        evidence.record(byteArrayOf(1, 2), true)
        evidence.record(byteArrayOf(1, 2, 3, 4), true)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), evidence.snapshot())
        assertEquals("latest_batch_window", evidence.mode)
        evidence.record(byteArrayOf(7, 8), true)
        assertArrayEquals(byteArrayOf(7, 8), evidence.snapshot())
        assertFalse(evidence.truncated)
    }
}
