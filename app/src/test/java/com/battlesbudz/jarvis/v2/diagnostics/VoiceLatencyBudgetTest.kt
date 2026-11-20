package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.diagnostics.VoiceLatencyBudget.Stage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceLatencyBudgetTest {
    private fun complete(): VoiceLatencyBudget {
        var now = 10_000L
        var budget = VoiceLatencyBudget("turn")
        budget = budget.mark(Stage.SPEECH_END, now)
        now += 300; budget = budget.mark(Stage.FINAL_TRANSCRIPT, now)
        now += 250; budget = budget.mark(Stage.FIRST_TOKEN, now)
        now += 40; budget = budget.mark(Stage.FIRST_SPEAKABLE_TEXT, now)
        now += 120; budget = budget.mark(Stage.FIRST_PCM, now)
        now += 60; budget = budget.mark(Stage.FIRST_AUDIBLE, now)
        return budget
    }

    @Test fun recordsEveryStageOnceWithDeltas() {
        val budget = complete()
        assertNull(budget.deltaMs(Stage.SPEECH_END))
        assertEquals(300L, budget.deltaMs(Stage.FINAL_TRANSCRIPT))
        assertEquals(250L, budget.deltaMs(Stage.FIRST_TOKEN))
        assertEquals(40L, budget.deltaMs(Stage.FIRST_SPEAKABLE_TEXT))
        assertEquals(120L, budget.deltaMs(Stage.FIRST_PCM))
        assertEquals(60L, budget.deltaMs(Stage.FIRST_AUDIBLE))
        assertEquals(770L, budget.speechEndToFirstAudibleMs)
        assertEquals(6, budget.stageAtMs.size)
    }

    @Test fun laterObservationsOfTheSameStageAreIgnored() {
        val budget = complete()
        val again = budget.mark(Stage.FIRST_TOKEN, 99_999L).mark(Stage.FIRST_AUDIBLE, 99_999L)
        assertEquals(budget, again)
        assertEquals(770L, again.speechEndToFirstAudibleMs)
    }

    @Test fun missingStagesStayNullNeverZero() {
        val budget = VoiceLatencyBudget("partial").mark(Stage.SPEECH_END, 1_000L)
            .mark(Stage.FIRST_TOKEN, 1_400L).mark(Stage.FIRST_AUDIBLE, 0L)
        assertNull(budget.deltaMs(Stage.FINAL_TRANSCRIPT))
        assertNull(budget.deltaMs(Stage.FIRST_TOKEN))
        assertEquals(400L, budget.between(Stage.SPEECH_END, Stage.FIRST_TOKEN))
        assertNull(budget.at(Stage.FIRST_AUDIBLE))
        assertNull(budget.speechEndToFirstAudibleMs)
        val metrics = budget.metrics()
        assertTrue(metrics.containsKey("latency_budget_first_pcm_to_first_audible_ms"))
        assertTrue(metrics.values.all { it == null })
        val json = JSONObject(budget.json().toString())
        assertTrue(json.getJSONObject("stageDeltasMs").isNull("first_pcm"))
        assertEquals(400L, json.getJSONObject("offsetsFromSpeechEndMs").getLong("first_token"))
        assertTrue(json.isNull("speechEndToFirstAudibleMs"))
        assertTrue(budget.render().contains("final_transcript→first_token=—"))
    }

    @Test fun reversedStagesAreUnavailable() {
        val budget = VoiceLatencyBudget("reversed").mark(Stage.FIRST_PCM, 2_000L).mark(Stage.FIRST_AUDIBLE, 1_500L)
        assertNull(budget.deltaMs(Stage.FIRST_AUDIBLE))
    }

    @Test fun exportsMetricsJsonAndRenderedLine() {
        val budget = complete()
        val metrics = budget.metrics()
        assertEquals(300L, metrics["latency_budget_speech_end_to_final_transcript_ms"])
        assertEquals(60L, metrics["latency_budget_first_pcm_to_first_audible_ms"])
        assertEquals(770L, metrics["latency_budget_speech_end_to_first_audible_ms"])
        val json = JSONObject(budget.json().toString())
        assertEquals(710L, json.getJSONObject("offsetsFromSpeechEndMs").getLong("first_pcm"))
        assertEquals(40L, json.getJSONObject("stageDeltasMs").getLong("first_speakable_text"))
        assertEquals(770L, json.getLong("speechEndToFirstAudibleMs"))
        val line = budget.render()
        assertTrue(line.startsWith("Voice latency budget turn=turn"))
        assertTrue(line.contains("speech_end→final_transcript=300ms"))
        assertTrue(line.contains("total=770ms"))
        assertFalse(line.contains("—"))
    }
}
