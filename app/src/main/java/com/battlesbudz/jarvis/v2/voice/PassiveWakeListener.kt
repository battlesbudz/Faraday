package com.battlesbudz.jarvis.v2.voice

import java.io.File
import java.nio.ByteBuffer

/** Dedicated microWakeWord inference. No ASR, transcript, WAV or Gemma input before a match. */
class PassiveWakeListener(
    directory: File,
    private val log: (String) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onLevel: (Int, Float) -> Unit = { _, _ -> }
) : AutoCloseable {
    private val detector = MicroWakeWord(
        modelBuffer = File(directory, "hey_jarvis.tflite").readBytes().let {
            ByteBuffer.allocateDirect(it.size).apply { put(it); rewind() }
        },
        featureStepSizeMs = 10, probabilityCutoff = 0.97f, slidingWindowSize = 5
    )
    suspend fun awaitWake(input: AudioInput) {
        require(input.sampleRateHz == 16000 && input.channelCount == 1) { "microWakeWord needs 16 kHz mono PCM" }
        var frames = 0L
        var windowFrames = 0
        var squares = 0.0
        var peak = 0
        var maxScore = 0f
        var readyReported = false
        log("wake_capture_started engine=microWakeWord model=hey_jarvis_v2 sampleRate=16000 threshold=0.97 window=5")
        WakeWordGate.await(input) { pcm ->
            require(pcm.size % 2 == 0) { "Incomplete PCM16 sample" }
            val samples = ShortArray(pcm.size / 2) { i ->
                ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
            }
            for (sample in samples) {
                squares += sample.toDouble() * sample
                peak = maxOf(peak, kotlin.math.abs(sample.toInt()))
            }
            frames += samples.size
            windowFrames += samples.size
            val found = detector.processAudio(samples)
            maxScore = maxOf(maxScore, detector.probability)
            if (!readyReported && detector.ready) {
                readyReported = true
                log("wake_detector_ready elapsedMs=${frames * 1000 / 16000}")
                onReady()
            }
            if (windowFrames >= 16000 || found) {
                val rms = kotlin.math.sqrt(squares / windowFrames.coerceAtLeast(1)).toInt()
                log("wake_capture_level elapsedMs=${frames * 1000 / 16000} rmsPcm16=$rms peakPcm16=$peak maxScore=$maxScore ready=$readyReported ${detector.diagnostics}")
                onLevel(rms, maxScore)
                squares = 0.0; peak = 0; windowFrames = 0; maxScore = 0f
            }
            if (found) log("wake_keyword_matched engine=microWakeWord keyword=Hey_Jarvis score=${detector.probability}")
            found
        }
    }
    override fun close() = detector.close()
}
