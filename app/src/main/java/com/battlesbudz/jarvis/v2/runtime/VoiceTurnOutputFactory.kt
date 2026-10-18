package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.SpeechAudioTrace
import com.battlesbudz.jarvis.v2.voice.SpeechDelivery
import com.battlesbudz.jarvis.v2.voice.SpeechDeliveryLedger
import com.battlesbudz.jarvis.v2.voice.TtsEngine
import com.battlesbudz.jarvis.v2.voice.TtsSessionMetrics
import com.battlesbudz.jarvis.v2.voice.VoiceCallResources
import com.battlesbudz.jarvis.v2.voice.VoiceModelSession
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import java.io.File

/** Assembles normal-call TTS, echo references and evidence; the turn owner releases output. */
internal class VoiceTurnOutputFactory(
    private val context: Context,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val onPlayback: (VoicePlaybackFrame) -> Unit
) {
    fun create(asrTurnId: String, ttsDirectory: File, ttsEngine: TtsEngine,
               models: VoiceModelSession, callResources: VoiceCallResources,
               comparison: LiveComparison.Trial?, onDelivery: (SpeechDelivery) -> Unit,
               onMetrics: (TtsSessionMetrics) -> Unit,
               onTiming: (String, Long) -> Unit = { _, _ -> }): PiperVoiceOutput {
        return PiperVoiceOutput(ttsDirectory.path, engine = ttsEngine,
            modelSession = models,
            deliveryLedger = SpeechDeliveryLedger(asrTurnId) { delivery ->
                onDelivery(delivery)
                diagnosticRecorder.recordImportant("Voice delivery turn=$asrTurnId state=${delivery.state} " +
                    "completedChars=${delivery.deliveredText.length} partialSpan=${delivery.partialSpanIndex} " +
                    "playedFrames=${delivery.playedFrames} precision=segment_frames")
            },
            onEchoReference = callResources::rememberPlayback,
            onPlaybackEnded = { callResources.playbackEnded(System.nanoTime() / 1_000_000) },
            maxQueuedPassages = 8,
            audioTrace = SpeechAudioTrace(
                java.io.File(context.cacheDir, "latest-jarvis-speech.wav"), asrTurnId,
                log = { diagnosticRecorder.recordImportant(it) }),
            onPlayback = onPlayback,
            onMetrics = onMetrics,
            onTiming = onTiming,
            log = {
                comparison?.log("tts $it")
                if (it.startsWith("tts_session_finished"))
                    diagnosticRecorder.recordTurnEvidence(asrTurnId, if (it.startsWith("tts_session")) "supply" else "pcm", it)
                if (it.startsWith("audio_underrun") ||
                    it.startsWith("audio_supply_gap")) diagnosticRecorder.recordSummary("Voice TTS turn=$asrTurnId: $it")
                if (it.startsWith("audio_underrun") || it.startsWith("audio_supply_gap") ||
                    it.startsWith("audio_startup_buffer")) diagnosticRecorder.recordImportant("Voice TTS: $it")
                else diagnosticRecorder.record("Voice TTS: $it")
            })
    }
}
