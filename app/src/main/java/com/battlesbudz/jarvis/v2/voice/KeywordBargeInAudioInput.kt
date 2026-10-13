package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

interface InterruptionKeywordDetector : AutoCloseable {
    val ready: Boolean
    val lastHitEvidence: String get() = "unavailable"
    val diagnosticSummary: String get() = "unavailable"
    fun accept(pcm: ByteArray): String?
}

/** No ASR work before a keyword. Keep the same microphone for the following request. */
class KeywordBargeInAudioInput(
    private val input: AudioInput,
    private val createDetector: () -> InterruptionKeywordDetector,
    private val onConfirmed: (String) -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val allowKeyword: (String) -> Boolean = { true }
) : AudioInput {
    override val sampleRateHz get() = input.sampleRateHz
    override val channelCount get() = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        var delivered = false
        var readyReported = false
        val startedAt = nowMs()
        val detector = createDetector()
        try { primeInterruptionKeywords(detector, input.priorAudioForKeywords, log) }
        catch (error: Throwable) { detector.close(); throw error }
        val loadMs = (nowMs() - startedAt).coerceAtLeast(0)
        var inputBytes = 0L
        var maxWorkMs = 0L
        var maxBacklogMs = 0L
        log("barge_keyword_loading_finished loadMs=$loadMs ready=${detector.ready} naturalSpeechReady=false")
        try {
            input.chunks().collect { pcm ->
                if (delivered) { emit(pcm); return@collect }
                val workAt = nowMs()
                val keyword = detector.accept(pcm)
                inputBytes += pcm.size
                maxWorkMs = maxOf(maxWorkMs, (nowMs() - workAt).coerceAtLeast(0))
                maxBacklogMs = maxOf(maxBacklogMs, input.bufferedAudioMs)
                if (detector.ready && !readyReported) {
                    readyReported = true
                    log("barge_keyword_ready keywords=Hey_Jarvis,stop asrLoaded=false " +
                        "readyMs=${(nowMs() - startedAt).coerceAtLeast(0)} inputMs=${inputBytes * 1000 / (sampleRateHz * channelCount * 2)} " +
                        "loadMs=$loadMs maxWorkMs=$maxWorkMs maxBacklogMs=$maxBacklogMs naturalSpeechReady=false")
                }
                if (keyword != null && !allowKeyword(keyword)) {
                    log("barge_stop_rejected reason=verification_unavailable playback_uninterrupted=true fallback=Hey_Jarvis")
                } else if (keyword != null) {
                    delivered = true
                    // Stop playback before lazily loading ASR, preserving subsequent chunks.
                    onConfirmed(keyword)
                    log("barge_keyword_confirmed keyword=$keyword")
                    // Consume the trigger itself: it must not become a new "stop listening"
                    // command or carry the reply's echo into the correction transcript.
                    // The user can speak the new request after playback stops.
                }
            }
        } finally {
            try { detector.close() } finally {
                log("barge_keyword_summary ready=$readyReported confirmed=$delivered " +
                    "inputMs=${inputBytes * 1000 / (sampleRateHz * channelCount * 2)} " +
                    "loadMs=$loadMs maxWorkMs=$maxWorkMs maxBacklogMs=$maxBacklogMs")
            }
        }
    }
}
