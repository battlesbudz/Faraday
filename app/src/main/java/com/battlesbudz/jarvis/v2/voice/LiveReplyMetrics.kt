package com.battlesbudz.jarvis.v2.voice


/** Live, turn-scoped UI metrics. Playback is confirmed by AudioTrack's head, never filler. */
internal data class LiveReplyMetrics(
    val turnId: String,
    val conversationId: String,
    val modelSubmittedAtMs: Long? = null,
    val speechEndedAtMs: Long? = null,
    val firstReplyTextAtMs: Long? = null,
    val firstReplyPlaybackAtMs: Long? = null,
    val estimatedTokensPerSecond: Double? = null,
    val nativeAudioTiming: com.battlesbudz.jarvis.v2.diagnostics.NativeAudioTimingMetrics? = null
) {
    fun submitted(atMs: Long) = copy(modelSubmittedAtMs = modelSubmittedAtMs ?: atMs)
    fun speechEnded(atMs: Long) = copy(speechEndedAtMs = speechEndedAtMs ?: atMs)
    fun firstText(atMs: Long) = copy(firstReplyTextAtMs = firstReplyTextAtMs ?: atMs)
    fun firstActualPlayback(atMs: Long) = copy(firstReplyPlaybackAtMs = firstReplyPlaybackAtMs ?: atMs)
    fun summary(): String = "TTFT ${duration(modelSubmittedAtMs, firstReplyTextAtMs)} · " +
        "TTF-SW ${duration(speechEndedAtMs, firstReplyPlaybackAtMs)} · " +
        (estimatedTokensPerSecond?.let { "~${String.format(java.util.Locale.US, "%.1f", it)} tok/s" } ?: "tok/s —") +
        (nativeAudioTiming?.let { " · ${it.summary()}" } ?: "")
    private fun duration(from: Long?, to: Long?): String = if (from == null || to == null || to < from) "—"
        else String.format(java.util.Locale.US, "%.2fs", (to - from) / 1000.0)
}
