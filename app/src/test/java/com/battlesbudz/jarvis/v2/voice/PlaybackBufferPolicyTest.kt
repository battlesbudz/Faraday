package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test fun paceCompensationIsBoundedAndFastAnswersStayNormal() {
        assertEquals(1f, PlaybackBufferPolicy.playbackSpeed(400, 2000), 0.001f)
        assertEquals(0.9f, PlaybackBufferPolicy.playbackSpeed(2000, 2000), 0.001f)
        assertEquals(0.90f, PlaybackBufferPolicy.playbackSpeed(136667, 102296), 0.001f)
        assertEquals(1f, PlaybackBufferPolicy.playbackSpeed(500, 0), 0.001f)
    }

    @Test fun fastSynthesisAddsNoStartupWait() {
        assertEquals(0L, PlaybackBufferPolicy.startupWaitMs(400, 2000))
    }
    @Test fun marginalSynthesisGetsSomeHeadroom() {
        assertEquals(1200L, PlaybackBufferPolicy.startupWaitMs(2000, 2000))
    }
    @Test fun slowSynthesisCannotCauseAnUnboundedStartupWait() {
        assertEquals(1200L, PlaybackBufferPolicy.startupWaitMs(19200, 11100))
        assertEquals(0L, PlaybackBufferPolicy.startupWaitMs(500, 0))
    }
    @Test fun tinyFirstSentenceStartsWithoutASecondPhraseDelay() {
        assertEquals(0L, PlaybackBufferPolicy.startupWaitMs(500, 800))
    }
    @Test fun nextSynthesisFitsAvailableAudioInsteadOfJumpingToLongChunk() {
        assertEquals(40, PlaybackBufferPolicy.nextChunkChars(800, 40, 700))
        assertEquals(75, PlaybackBufferPolicy.nextChunkChars(2000, 100, 2000))
        assertEquals(180, PlaybackBufferPolicy.nextChunkChars(10000, 100, 2000))
        assertEquals(40, PlaybackBufferPolicy.nextChunkChars(-100, 100, 2000))
        assertEquals(40, PlaybackBufferPolicy.nextChunkChars(500, 0, 0))
    }

}
