package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn
import org.junit.Assert.*
import org.junit.Test

class ReplyCaptureBenchmarkResultTest {
    @Test fun pauseAndRecognitionFailuresTakePrecedenceOverPartialTextOrAudio() {
        val partial = CapturedVoiceTurn("partial words", byteArrayOf(1), audioIsComplete = false, recognitionIssue = "microphone_paused")
        val paused = ReplyCaptureBenchmarkResult.classify(partial, confirmed = true, reason = "floor_handoff")
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, paused.outcome)
        assertEquals("microphone_paused", paused.failureCode)
        val failed = ReplyCaptureBenchmarkResult.classify(partial.copy(recognitionIssue = "decoder_failure"), true, null)
        assertEquals(PipelineBenchmarkOutcome.ERROR, failed.outcome)
        assertEquals("recognition_issue", failed.failureCode)
        val incomplete = ReplyCaptureBenchmarkResult.classify(partial.copy(recognitionIssue = null), true, null)
        assertEquals(PipelineBenchmarkOutcome.REJECTED, incomplete.outcome)
        assertEquals("capture_audio_incomplete", incomplete.failureCode)
    }

    @Test fun completeAudioRemainsUsefulWithoutCaptionsAndQuietCaptureRemainsNoSpeech() {
        val quiet = CapturedVoiceTurn("", byteArrayOf())
        val audio = ReplyCaptureBenchmarkResult.classify(quiet.copy(wav = byteArrayOf(1)), false, null)
        assertEquals(PipelineBenchmarkOutcome.COMPLETE, audio.outcome)
        assertNull(audio.failureCode)
        val silence = ReplyCaptureBenchmarkResult.classify(quiet, false, "keyword_control_consumed")
        assertEquals(PipelineBenchmarkOutcome.NO_SPEECH, silence.outcome)
        assertNull(silence.failureCode)
        val confirmed = ReplyCaptureBenchmarkResult.classify(quiet, true, "correction_timeout")
        assertEquals(PipelineBenchmarkOutcome.REJECTED, confirmed.outcome)
        assertEquals("correction_timeout", confirmed.failureCode)
        assertEquals("confirmed_capture_no_final_request", ReplyCaptureBenchmarkResult.classify(quiet, true, null).failureCode)
    }
}
