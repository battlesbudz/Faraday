package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompletedEndpointCueTest {
    @Test fun asyncCueUsesUngatedInputCursorEvenAfterLongIdleAndActivationTrim() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val asr = AsyncWhisperSession({ entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); "what is two plus two" }, {})
        try {
            asr.observeSpeech(false)
            asr.accept(ByteArray(32000 * 3), false)
            asr.observeSpeech(true)
            asr.accept(ByteArray(48000), true)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            asr.observeSpeech(false)
            asr.accept(ByteArray(3200), false)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (asr.completedEndpointCue == null && System.nanoTime() < deadline) Thread.yield()
            val cue = asr.completedEndpointCue!!
            assertEquals(72000L, cue.coveredAudioSamples)
            assertEquals("what is two plus two", cue.text)
            // No second matching decode was needed to deliver a complete endpoint cue.
            assertEquals("", asr.accept(ByteArray(3200), false))
            assertEquals(cue, asr.completedEndpointCue)
        } finally { release.countDown(); asr.close() }
    }

    @Test fun segmentedCueCannotAssignNewAudioCoverageToAnUnresolvedCommittedPrefix() {
        class Engine(val hardMs: Long, val recognized: String) : StreamingTranscriber {
            var samples = 0L
            override val segmentSoftLimitMs = 100L
            override val segmentHardLimitMs = hardMs
            override val completedEndpointCue get() = CompletedEndpointCue(samples, recognized, samples)
            override fun accept(pcm: ByteArray): String { samples += pcm.size / 2; return recognized }
            override fun finish() = recognized
            override fun close() {}
        }
        var loads = 0
        val asr = SegmentedTranscriber({ if (loads++ == 0) Engine(200L, "what is two plus two")
            else Engine(22000L, "actually make that five") })
        asr.observeSpeech(true)
        asr.accept(ByteArray(6400)) // Hard boundary, keeps 200 ms overlap for next stream.
        asr.accept(ByteArray(3200)) // Replay 3200 samples + 1600 newly captured samples.
        assertNull(asr.completedEndpointCue)
        asr.close()
    }
}
