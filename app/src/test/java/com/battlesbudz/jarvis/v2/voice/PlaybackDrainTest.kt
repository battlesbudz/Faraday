package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackDrainTest {
    @Test fun shortRepliesCanReachStartThreshold() {
        for (rate in listOf(22050, 24000)) {
            for (ms in listOf(20, 584, 1637, 1815, 2204)) {
                val frames = rate * ms / 1000
                assertTrue(PlaybackDrain.startThreshold(rate, frames) in 1..frames)
                assertTrue(PlaybackDrain.startThreshold(rate, frames) <= rate / 10)
            }
        }
    }
    @Test fun queuedButUnplayedAudioTimesOutInsteadOfReportingCompletion() = runBlocking {
        var now = 0L
        assertFalse(PlaybackDrain.await(40000, 24000, 1f, { 0 }, { false }, { now }, { now += it }))
        assertTrue(now in 3600..3700)
    }
    @Test fun completionRequiresTheLastSpeechFrame() = runBlocking {
        var now = 0L
        var head = 0L
        assertTrue(PlaybackDrain.await(24000, 24000, .9f, { head }, { false }, { now }, {
            now += it; head += 480
        }))
        assertEquals(24000L, head)
    }
    @Test fun cancellationDoesNotCountAsCompleted() = runBlocking {
        assertFalse(PlaybackDrain.await(24000, 24000, 1f, { 0 }, { true }))
    }
}
