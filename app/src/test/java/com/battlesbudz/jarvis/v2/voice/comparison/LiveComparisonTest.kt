package com.battlesbudz.jarvis.v2.voice.comparison

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class LiveComparisonTest {
    @Test fun timingUsesSpeechEndAndSeparatesSubmissionFromPlayback() {
        val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.WHISPER_NPU, "No yes", 1))
        t.mark("speech_end", 100); t.mark("answer_submit", 200); t.mark("answer_first_token", 240)
        t.mark("answer_first_token", 999); t.mark("answer_audio", 600)
        t.put("resolved_transcript", "No. Yes.")
        val r = JSONObject(t.report().substringBefore("\n\n"))
        assertEquals(40, r.getLong("answer_submit_to_first_token_ms"))
        assertEquals(140, r.getLong("speech_end_to_answer_first_token_ms"))
        assertEquals(500, r.getLong("speech_end_to_first_audible_answer_frame_ms"))
        assertEquals(0.0, r.getDouble("word_error_rate_percent"), 0.0)
        assertTrue(r.isNull("asr_submit_to_first_token_ms"))
    }
    @Test fun directAnswerNeverGetsFabricatedTranscriptionScore() {
        val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.GEMMA_DIRECT, "hello", 1))
        t.put("resolved_transcript", "hello")
        val r = JSONObject(t.report().substringBefore("\n\n"))
        assertTrue(r.isNull("word_error_rate_percent"))
        assertTrue(r.isNull("speech_end_to_first_audible_answer_frame_ms"))
    }
    @Test fun failedOrInterruptedPlaybackCannotCountAsCompleteTrial() {
        val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.WHISPER_NPU, "hello", 1))
        t.put("resolved_transcript", "hello")
        t.put("turn_completed", true); t.mark("answer_first_token"); t.mark("answer_audio")
        assertTrue(JSONObject(t.report().substringBefore("\n\n")).getBoolean("complete_trial"))
        t.put("generation_error", "failed after first token")
        assertFalse(JSONObject(t.report().substringBefore("\n\n")).getBoolean("complete_trial"))
    }
    @Test fun clarificationAfterFailedRecognitionIsNotASuccessOrAnAccuracyScore() {
        for (transcript in listOf("", "[Voice message — transcription unavailable]", "(buzzing)")) {
            val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.GEMMA_ASR, "hello", 1))
            t.put("resolved_transcript", transcript)
            t.put("turn_completed", true); t.mark("answer_first_token"); t.mark("answer_audio")
            val r = JSONObject(t.report().substringBefore("\n\n"))
            assertFalse(r.getBoolean("complete_trial"))
            assertFalse(r.getBoolean("recognition_valid"))
            assertTrue(r.isNull("word_error_rate_percent"))
        }
    }
    @Test fun recognitionFailureInvalidatesEvenANonemptyTranscript() {
        val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.GEMMA_ASR, "hello", 1))
        t.put("resolved_transcript", "hello"); t.put("recognition_issue", "audio_fallback_timeout")
        t.put("turn_completed", true); t.mark("answer_first_token"); t.mark("answer_audio")
        val r = JSONObject(t.report().substringBefore("\n\n"))
        assertFalse(r.getBoolean("complete_trial"))
        assertEquals("audio_fallback_timeout", r.getString("invalid_reason"))
        assertTrue(r.isNull("word_error_rate_percent"))
    }
    @Test fun archiveHasIndependentInputAndReport() {
        val t = LiveComparison.Trial("x", LiveComparison.Request(LiveComparison.Path.WHISPER, "hello", 2))
        t.wav = byteArrayOf(1, 2, 3)
        val out = ByteArrayOutputStream(); t.writeZip(out)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        assertArrayEquals(t.wav, entries["input.wav"])
        assertTrue(entries.getValue("report.txt").toString(Charsets.UTF_8).contains("WHISPER"))
    }
}
