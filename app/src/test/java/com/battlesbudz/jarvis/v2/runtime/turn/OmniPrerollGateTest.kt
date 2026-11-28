package com.battlesbudz.jarvis.v2.runtime.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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

    @Test fun productionThresholdIsTwoCodecChunks() {
        assertEquals(2 * 23_040, OmniPrerollGate.PREROLL_FRAMES)
    }

    @Test fun playbackPolicyStartsAtTwoChunksAndFinishesShortAnswers() {
        val policy = OmniPlaybackBufferPolicy()
        assertEquals(false, policy.arrived(23_040))
        assertEquals(true, policy.arrived(23_040))
        val short = OmniPlaybackBufferPolicy()
        assertEquals(false, short.arrived(10))
        assertEquals(true, short.finished())
    }

    @Test fun playbackPolicyRebuffersAtFourArrivalsAndResets() {
        val policy = OmniPlaybackBufferPolicy()
        policy.arrived(46_080)
        policy.underrun()
        assertEquals(true, policy.rebuffering)
        repeat(3) { assertEquals(false, policy.arrived(23_040)) }
        assertEquals(true, policy.arrived(23_040))
        assertEquals(false, policy.rebuffering)
        policy.underrun()
        policy.stop()
        assertEquals(false, policy.arrived(92_160))
        policy.reset()
        assertEquals(false, policy.rebuffering)
        assertEquals(0, policy.arrivedFrames)
    }

    @Test fun boundedPcmHandoffBackpressuresWithoutNormalDrops() = runBlocking {
        val sink = OmniPcmQueueSink()
        val queue = Channel<FloatArray>(capacity = 1)
        sink.activeQueue = queue
        val first = async(Dispatchers.Default) { sink.offer(floatArrayOf(1f)) }
        assertEquals(true, first.await())
        val second = async(Dispatchers.Default) { sink.offer(floatArrayOf(2f)) }
        delay(30)
        assertEquals(false, second.isCompleted)
        queue.receive()
        assertEquals(true, second.await())
        assertEquals(0, sink.droppedChunks)
        queue.close()
    }
}
