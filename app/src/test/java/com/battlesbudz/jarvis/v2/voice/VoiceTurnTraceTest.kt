package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage
import org.junit.Assert.*
import org.junit.Test

class VoiceTurnTraceTest {
    @Test fun tracksIndependentOverlappingStagesAndKeepsFirstEvent() {
        var now = 1000L
        val trace = VoiceTurnTrace("turn") { now }
        now += 50; trace.mark(Stage.MICROPHONE_READY)
        now += 100; trace.mark(Stage.FIRST_REPLY_TEXT)
        now += 200; trace.mark(Stage.FIRST_REPLY_AUDIO)
        now += 300; trace.mark(Stage.FIRST_REPLY_TEXT)
        assertEquals(200L, trace.duration(Stage.FIRST_REPLY_TEXT, Stage.FIRST_REPLY_AUDIO))
        assertEquals(150L, trace.snapshot()["first_reply_text"])
        assertEquals(0L, trace.snapshot()["turn_started"])
    }

    @Test fun missingOrReversedStagesAreUnavailable() {
        val trace = VoiceTurnTrace("turn") { 1L }
        assertNull(trace.duration(Stage.INTERRUPTION_CONFIRMED, Stage.PLAYBACK_STOP_REQUESTED))
        assertFalse(trace.snapshot().containsKey("audio_fallback_started"))
        var now = 10L
        val other = VoiceTurnTrace("other") { now }
        other.mark(Stage.FIRST_REPLY_AUDIO)
        now += 5; other.mark(Stage.FIRST_REPLY_TEXT)
        assertNull(other.duration(Stage.FIRST_REPLY_TEXT, Stage.FIRST_REPLY_AUDIO))
    }

    @Test fun finishedTurnRejectsLateCallbacksAndSnapshotsDoNotChange() {
        var now = 100L
        val trace = VoiceTurnTrace("ended") { now }
        val before = trace.snapshot()
        now += 10; trace.mark(Stage.TURN_FINISHED)
        now += 100; trace.mark(Stage.FIRST_REPLY_AUDIO)
        assertEquals(mapOf("turn_started" to 0L), before)
        assertFalse(trace.snapshot().containsKey("first_reply_audio"))
        assertEquals(10L, trace.snapshot()["turn_finished"])
    }
}
