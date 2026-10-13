package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackClockTest {
    @Test fun repeatedPauseNotificationsDoNotResetTheSavedPlaybackTime() {
        var wall = 100L
        val clock = PlaybackClock { wall }
        clock.setPaused(true)
        wall = 30100
        clock.setPaused(true)
        assertEquals(100L, clock.nowMs())
        clock.setPaused(false)
        wall += 500
        assertEquals(600L, clock.nowMs())
        clock.setPaused(false)
        assertEquals(600L, clock.nowMs())
    }
    @Test fun minuteLongInterruptionDoesNotExpireBufferedSpeechDrain() = runBlocking {
        var wall = 0L
        var head = 0L
        var interrupted = false
        val clock = PlaybackClock { wall }
        val drained = PlaybackDrain.await(24000, 24000, 1f, { head }, { false }, clock::nowMs, {
            if (!interrupted) {
                clock.setPaused(true)
                wall += 60000
                assertEquals(0L, clock.nowMs())
                clock.setPaused(false)
                interrupted = true
            }
            wall += it
            head += 480
        })
        assertTrue(drained)
        assertEquals(24000L, head)
        assertTrue(wall >= 61000)
    }
}
