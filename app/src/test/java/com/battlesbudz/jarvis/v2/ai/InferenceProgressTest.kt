package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class InferenceProgressTest {
    @Test fun emitsThrottledRateBeforeGenerationCompletes() {
        var now = 1_000L
        val rate = LiveTokenRateEstimator { now }
        rate.rawToken(now)
        assertNull(rate.addRawChunk("abcd"))
        now += 120
        val first = rate.addRawChunk("abcd")
        assertNotNull("rate is available while the stream is still open", first)
        now += 140
        assertNull(rate.addRawChunk("abcd"))
        now += 120
        assertNotNull(rate.addRawChunk("abcd"))
    }
}
