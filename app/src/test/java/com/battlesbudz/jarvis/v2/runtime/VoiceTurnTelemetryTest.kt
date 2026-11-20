package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.TtsComparisonStore
import com.battlesbudz.jarvis.v2.voice.TtsEngine
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Endpoint evidence recorded by recordAsr, per route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceTurnTelemetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val suffix = UUID.randomUUID().toString()
    private fun preferences(name: String) = context.getSharedPreferences("$name-$suffix", Context.MODE_PRIVATE)

    private fun telemetry(captionAsrEnabled: Boolean, capture: PipelineBenchmarkCapture) =
        VoiceTurnTelemetry("turn-1", "conversation-1", AsrEngine.WHISPER, TtsEngine.PIPER_NORTHERN,
            captionAsrEnabled, capture, VoiceTurnTrace("turn-1"), null,
            DiagnosticRecorder(preferences("diagnostics")), AsrComparisonStore(preferences("asr")),
            TtsComparisonStore(preferences("tts")))

    private fun capture() = PipelineBenchmarkCapture("turn-1", "voice", 1_000L,
        PipelineBenchmarkProvenance("test", 1))

    private fun metrics(endpointReason: String, endpointDetectionMs: Long?, finalizationMs: Long,
                        targetSilenceMs: Long?, endpointCue: String?, endpointAuthority: String?) =
        AsrCaptureMetrics(modelLoadMs = 0, captureReadyMs = 0, audioMs = 4_200, decodeMs = 0,
            maxDecodeChunkMs = 0, firstPartialAfterSpeechMs = null, partialUpdates = 0,
            finalizationMs = finalizationMs, endpointReason = endpointReason,
            endpointDetectionMs = endpointDetectionMs, targetSilenceMs = targetSilenceMs,
            endpointCue = endpointCue, endpointAuthority = endpointAuthority)

    @Test fun directAudioTurnRecordsEndpointEvidenceWithoutRecognizer() {
        val capture = capture()
        telemetry(captionAsrEnabled = false, capture).recordAsr(
            metrics("trailing_silence", endpointDetectionMs = 640, finalizationMs = 25,
                targetSilenceMs = 650, endpointCue = "fixed", endpointAuthority = "fallback"),
            "", null)
        val turn = checkNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        val asr = turn.asr
        assertNotNull("direct-audio turn must carry an endpoint record", asr)
        assertEquals("none", asr!!.engine)
        assertEquals(665L, asr.endpointDelayMs)
        assertEquals(25L, asr.finalizationMs)
        assertEquals("trailing_silence", asr.endpointReason)
        assertEquals(650L, asr.targetSilenceMs)
        assertEquals("fixed", asr.endpointCue)
        assertEquals("fallback", asr.endpointAuthority)
        // No recognizer ran: its fields stay unmeasured, never zero-filled.
        assertNull(asr.loadMs)
        assertNull(asr.captureReadyMs)
        assertNull(asr.inputAudioMs)
        assertNull(asr.decodeWorkMs)
        assertNull(asr.firstPartialMs)
        assertNull(asr.maxDecodeChunkMs)
        assertNull(asr.maxBacklogMs)
        assertNull(asr.partialUpdates)
        assertNull(asr.transcriptCharacters)
        val exported = turn.json().getJSONObject("asr")
        assertEquals("fallback", exported.getString("endpointAuthority"))
        assertEquals(650L, exported.getLong("targetSilenceMs"))
        assertEquals(665L, exported.getLong("endpointDelayMs"))
    }

    @Test fun directAudioTurnRecordsMeasuredValuesNotConstants() {
        val capture = capture()
        telemetry(captionAsrEnabled = false, capture).recordAsr(
            metrics("audio_input_limit", endpointDetectionMs = 100, finalizationMs = 10,
                targetSilenceMs = 350, endpointCue = "smart_turn_complete", endpointAuthority = "smart_turn"),
            "", null)
        val asr = capture.finish(PipelineBenchmarkOutcome.COMPLETE)?.asr
        assertNotNull(asr)
        assertEquals("none", asr!!.engine)
        assertEquals("audio_input_limit", asr.endpointReason)
        assertEquals("smart_turn_complete", asr.endpointCue)
        assertEquals("smart_turn", asr.endpointAuthority)
        assertEquals(350L, asr.targetSilenceMs)
        assertEquals(110L, asr.endpointDelayMs)
    }

    @Test fun whisperRouteRecordIsUnchanged() {
        val capture = capture()
        telemetry(captionAsrEnabled = true, capture).recordAsr(
            AsrCaptureMetrics(modelLoadMs = 300, captureReadyMs = 450, audioMs = 5_000, decodeMs = 700,
                maxDecodeChunkMs = 90, firstPartialAfterSpeechMs = 800, partialUpdates = 6,
                finalizationMs = 120, endpointReason = "trailing_silence",
                endpointDetectionMs = 1_473, targetSilenceMs = 350,
                endpointCue = "smart_turn_complete", maxRecognitionBacklogMs = 40,
                endpointAuthority = "smart_turn"),
            "hello world", null)
        val asr = capture.finish(PipelineBenchmarkOutcome.COMPLETE)?.asr
        assertNotNull(asr)
        assertEquals(AsrEngine.WHISPER.id, asr!!.engine)
        assertEquals(300L, asr.loadMs)
        assertEquals(450L, asr.captureReadyMs)
        assertNull(asr.inputAudioMs)
        assertNull(asr.decodeWorkMs)
        assertEquals(120L, asr.finalizationMs)
        assertEquals(800L, asr.firstPartialMs)
        assertEquals(1_593L, asr.endpointDelayMs)
        assertEquals(90L, asr.maxDecodeChunkMs)
        assertEquals(40L, asr.maxBacklogMs)
        assertEquals(6, asr.partialUpdates)
        assertEquals(11, asr.transcriptCharacters)
        assertEquals("trailing_silence", asr.endpointReason)
        assertEquals(350L, asr.targetSilenceMs)
        assertEquals("smart_turn_complete", asr.endpointCue)
        assertEquals("smart_turn", asr.endpointAuthority)
    }
}
