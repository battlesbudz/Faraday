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
    val recognitionWork: AsrRecognitionWorkMetrics? = null,
    /** Which authority produced the accepted endpoint: "smart_turn", "guard" or "fallback". */
    val endpointAuthority: String? = null,
    /** Silence evidence the accepted endpoint actually consumed, in ms of classified quiet. */
    val silenceWaitedMs: Long? = null,
    /** The verdict the analyzer produced for the accepted pause
     * ("COMPLETE"/"CONTINUE"), or the raw state at the endpoint
     * ("PENDING"/"FALLBACK") when it produced none; null when no analyzer
     * observed this capture. Diagnostics only. */
    val smartTurnState: String? = null,
    /** Model probability behind [smartTurnState] when the analyzer reported one. */
    val smartTurnProbability: Float? = null,
    /** Every time this turn's endpoint was proposed and then deferred or invalidated,
     * by reason, in order. Presentation only; never read by endpoint arbitration. */
    val endpointDeferrals: List<String> = emptyList()
)
