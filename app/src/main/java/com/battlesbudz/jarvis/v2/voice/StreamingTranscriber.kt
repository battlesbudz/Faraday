package com.battlesbudz.jarvis.v2.voice

/** One utterance; accept returns a replaceable hypothesis, finish seals dictation. */
interface StreamingTranscriber : AutoCloseable {
    /** Null means the backend has no measured recognizer-work instrumentation. */
    val recognitionWorkMetrics: AsrRecognitionWorkMetrics? get() = null
    /** Completed work uses this engine's ungated accept-input sample coordinates, never model-window size. */
    val completedEndpointCue: CompletedEndpointCue? get() = null
    val noTextSilenceMs: Long get() = 3000
    val segmentSoftLimitMs: Long get() = 15_000
    val segmentHardLimitMs: Long get() = 22_000
    fun observeSpeech(speech: Boolean) {}
    /** Optional final-only mode for a bounded clip. Call before accept; finish must flush all audio. */
    fun prepareForBoundedProbe(maxAudioMs: Long): String = "streaming"
    fun accept(pcm: ByteArray): String
    /** Feed all PCM, but permit engines to omit intermediate decoding while catching up. */
    fun accept(pcm: ByteArray, allowPartial: Boolean): String = accept(pcm)
    fun finish(): String
    /**
     * Display-only fast path. Atomically seal admission without NEW final decoding only
     * if all previous native work is already idle. False leaves the stream unchanged.
     * Never supplies final recognition evidence; the caller must still close before reuse.
     */
    fun retireIdleCaption(): Boolean = false
    /** Undo only an idle caption retirement when acoustic drain invalidates its endpoint.
     * Preserve the same original PCM/owner; a closed or ordinarily finalized stream cannot resume. */
    fun resumeRetiredCaption(): Boolean = false
    /** One independent full-clip pass after an empty streaming result; PCM stays in memory. */
    fun recover(pcm: ByteArray): String = ""
}
