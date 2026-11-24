package com.battlesbudz.jarvis.v2.runtime.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniPrerollGateTest {
    @Test fun startsExactlyWhenThresholdArrivesAndOnlyOnce() {
        val gate = OmniPrerollGate(prerollFrames = 10)

        assertFalse(gate.onChunkArrived(4))
        assertEquals(4, gate.arrivedFrames)
        assertFalse(gate.started)
        assertTrue(gate.onChunkArrived(6))
        assertEquals(10, gate.arrivedFrames)
        assertTrue(gate.started)
        assertFalse(gate.onChunkArrived(3))
        assertFalse(gate.onGenerationFinished())
    }

    @Test fun generationFinishesShortAnswerButNotAnEmptyTurn() {
        val gate = OmniPrerollGate(prerollFrames = 10)

        assertFalse(gate.onGenerationFinished())
        assertFalse(gate.started)
        gate.onChunkArrived(4)
        assertTrue(gate.onGenerationFinished())
        assertTrue(gate.started)
        assertFalse(gate.onGenerationFinished())
    }

    @Test fun resetClearsArrivalAndStartState() {
        val gate = OmniPrerollGate(prerollFrames = 2)
        assertTrue(gate.onChunkArrived(2))

        gate.reset()

        assertFalse(gate.started)
        assertEquals(0, gate.arrivedFrames)
        assertFalse(gate.onGenerationFinished())
    }

    @Test fun productionThresholdIsThreeCodecChunks() {
        assertEquals(3 * 23_040, OmniPrerollGate.PREROLL_FRAMES)
    }
}
