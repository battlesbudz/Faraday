package com.battlesbudz.jarvis.v2.voice

data class AsrCaptureMetrics(
    val modelLoadMs: Long,
    val captureReadyMs: Long,
    val audioMs: Long,
    /** Legacy field: capture-thread transcriber.accept wall time. It excludes
     * asynchronous Whisper decode work; use recognitionWork for recognizer RTF. */
    val decodeMs: Long,
    val maxDecodeChunkMs: Long,
    val firstPartialAfterSpeechMs: Long?,
    val partialUpdates: Int,
    val finalizationMs: Long,
    val endpointReason: String,
    val emptyCandidates: Int = 0,
    val endpointDetectionMs: Long? = null,
    val targetSilenceMs: Long? = null,
    val endpointCue: String? = null,
    val acoustic: CaptureAcousticMetrics? = null,
    val maxRecognitionBacklogMs: Long? = null,
    val maxRecognitionWorkMs: Long? = null,
    val finalDecodeMs: Long? = null,
    val recognitionWork: AsrRecognitionWorkMetrics? = null
)
