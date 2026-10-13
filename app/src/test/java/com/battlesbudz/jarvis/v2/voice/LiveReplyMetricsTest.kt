package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class LiveReplyMetricsTest {
    @Test fun missingEventsAreShownAsUnknownAndTurnsDoNotShareMeasurements() {
        assertTrue(LiveReplyMetrics("a", "conversation-a").summary().contains("TTFT — · TTF-SW —"))
        val first = LiveReplyMetrics("a", "conversation-a").submitted(100).speechEnded(50).firstText(180).firstActualPlayback(300)
        assertTrue(first.summary().contains("TTFT 0.08s · TTF-SW 0.25s"))
        assertTrue(LiveReplyMetrics("b", "conversation-b").summary().contains("TTFT —"))
    }
}
