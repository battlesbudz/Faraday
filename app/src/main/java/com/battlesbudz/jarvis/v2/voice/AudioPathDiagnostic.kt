package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream

/** Opt-in microphone and recognition checks. Nothing is written to disk. */
object AudioPathDiagnostic {
    data class RecognitionResult(val microphonePcm: ByteArray, val decoderPcm: ByteArray, val transcript: String, val report: String)

    /** Fixed listening window through the actual capture/recognizer adapters; no Gemma or tools. */
    suspend fun recognize(input: AudioInput, createDetector: () -> SpeechDetector,
                          createTranscriber: (RecognitionAudioEvidence) -> StreamingTranscriber,
                          onReady: () -> Unit = {}): RecognitionResult = coroutineScope {
        val microphone = RecognitionAudioEvidence()
        val decoder = RecognitionAudioEvidence()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var audioMs = 0L
        var speechMs = 0L
        var rejectedMs = 0L
        fun event(text: String) = synchronized(events) { if (events.size < 240) events.add(text); Unit }
        val capture = AudioTurnCapture(input, this, createDetector = createDetector,
            createTranscriber = { createTranscriber(decoder) },
            trailingSilenceMs = 30_000, allowAudioOnlyTurns = true, log = ::event,
            onAcousticDecision = { pcm, raw, accepted, floor ->
                microphone.record(pcm, replace = false)
                val duration = pcm.size / 32L
                audioMs += duration
                if (accepted.isSpeech) speechMs += duration
                if (raw.isSpeech && !accepted.isSpeech) rejectedMs += duration
                val signal = Pcm16Signal.measure(pcm)
                event("audioMs=$audioMs rms=${signal.rms.toInt()} peak=${signal.peak} " +
                    "rawVad=${raw.probability} confirmed=${raw.isSpeech} admitted=${accepted.isSpeech} floorRms=${floor.toInt()}")
            })
        try {
            withTimeout(25_000) {
                capture.start(initialSilenceTimeoutMs = null)
                onReady()
                val deadline = launch { delay(8000); capture.finishNow() }
                try { capture.awaitTurnCompletion() } finally { deadline.cancel() }
            }
        } finally { capture.stop() }
        val mic = microphone.snapshot()
        val decoded = decoder.snapshot()
        RecognitionResult(mic, decoded, capture.finalTranscript,
            "scope=fixed_window_recognition_test endpoint=manual_after_8s gemma=false tools=false\n" +
            "microphoneMs=${mic.size / 32} decoderMs=${decoded.size / 32} decoderScope=${decoder.mode} " +
            "truncated=${microphone.truncated || decoder.truncated} admittedSpeechMs=$speechMs rejectedSpeechMs=$rejectedMs\n" +
            "transcript=${capture.finalTranscript}\nissue=${capture.recognitionIssue}\n" +
            synchronized(events) { events.joinToString("\n") })
    }

    suspend fun record(input: AudioInput, durationMs: Int = 8000): ByteArray {
        require(durationMs in 1000..10000)
        val limit = durationMs * 32
        val bytes = ByteArrayOutputStream(limit)
        try {
            withTimeout(durationMs + 5000L) {
                input.start()
                input.chunks().takeWhile { chunk ->
                    bytes.write(chunk, 0, minOf(chunk.size, limit - bytes.size()))
                    bytes.size() < limit
                }.collect()
            }
            check(bytes.size() == limit) { "Microphone ended before the recording finished." }
            return bytes.toByteArray()
        } finally { withContext(NonCancellable) { input.stop() } }
    }

    suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        require(pcm.isNotEmpty() && pcm.size <= 800000 && pcm.size % 2 == 0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AndroidFormat.Builder().setSampleRate(16000)
                .setChannelMask(AndroidFormat.CHANNEL_OUT_MONO).setEncoding(AndroidFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        try {
            check(track.state == AudioTrack.STATE_NO_STATIC_DATA || track.state == AudioTrack.STATE_INITIALIZED)
            check(track.write(pcm, 0, pcm.size) == pcm.size) { "Could not load the test recording for playback." }
            track.play()
            withTimeout(pcm.size / 32L + 3000) {
                while (track.playbackHeadPosition < pcm.size / 2) { ensureActive(); delay(25) }
            }
        } finally { runCatching { track.stop() }; track.release() }
    }
}
