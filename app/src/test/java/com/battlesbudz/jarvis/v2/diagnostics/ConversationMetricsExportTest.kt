package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.chat.ConversationMessage
import com.battlesbudz.jarvis.v2.chat.ConversationThread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationMetricsExportTest {
    @Test fun nativeIntervalsAreExportedWithoutInventingLegacyTimingOrExposingText() {
        val timing = NativeAudioTimingMetrics(49_221, 19_200, 7, 77, true, 20_000,
            firstAudioToSpeechLowerMs = 4000.01, firstAudioToSpeechUpperMs = 4000.03,
            stepsBeforeEndpointLowerBound = 1)
        val thread = ConversationThread("thread", listOf(
            ConversationMessage("native", "Jarvis", "private answer", metrics = ReplyMetrics(nativeAudioTiming = timing)),
            ConversationMessage("legacy", "Jarvis", "private older answer")))
        val exported = ConversationMetricsExport.json(PipelineBenchmarkReport(emptyList(), 1), thread)
        val rows = exported.getJSONArray("replyMetrics")
        assertEquals(timing, NativeAudioTimingMetrics.read(rows.getJSONObject(0).getJSONObject("nativeAudioTiming")))
        assertTrue(rows.getJSONObject(1).isNull("nativeAudioTiming"))
        assertFalse(exported.toString().contains("private answer"))
        assertFalse(exported.toString().contains("private older answer"))
        assertFalse(exported.toString().contains("producerEpoch"))
        assertTrue(exported.getJSONObject("replyMetricsDefinitions").getString("nativeAudioClockContract")
            .contains("binary not attested"))
    }

    @Test fun replyTotalsRemainEstimatedAndMissingTimingDoesNotBecomeZero() {
        val metrics = ReplyMetrics().submitted(100).firstRawToken(220).withOutputText("12345")
        assertEquals(2, metrics.estimatedOutputTokens)
        assertEquals(metrics, ReplyMetrics.read(JSONObject(metrics.json().toString())))
        assertTrue(metrics.summary().contains("~2 tokens total"))
        assertNull(ReplyMetrics.read(JSONObject("{}"))!!.estimatedOutputTokens)
        assertNull(ReplyMetrics.read(JSONObject("{\"estimatedOutputTokens\":-1}"))!!.estimatedOutputTokens)
        val thread = ConversationThread("thread", listOf(
            ConversationMessage("user", "You", "private user text"),
            ConversationMessage("reply", "Jarvis", "12345", complete = false, metrics = metrics),
            ConversationMessage("legacy", "Jarvis", "private old response")))
        val report = ConversationMetricsExport.json(PipelineBenchmarkReport(emptyList(), 1), thread)
        val rows = report.getJSONArray("replyMetrics")
        assertEquals(2, rows.length())
        assertEquals(120L, rows.getJSONObject(0).getLong("ttftMs"))
        assertEquals(2, rows.getJSONObject(0).getInt("estimatedOutputTokens"))
        assertFalse(rows.getJSONObject(0).getBoolean("complete"))
        assertTrue(rows.getJSONObject(0).isNull("timeToFirstPlaybackMs"))
        assertTrue(rows.getJSONObject(1).isNull("ttftMs"))
        assertFalse(report.toString().contains("private user text"))
        assertFalse(report.toString().contains("private old response"))
        assertFalse(report.toString().contains("\"exactOutputTokens\":2"))
    }
}
