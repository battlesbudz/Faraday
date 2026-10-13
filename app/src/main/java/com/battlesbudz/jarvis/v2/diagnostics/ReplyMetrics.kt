package com.battlesbudz.jarvis.v2.diagnostics

import androidx.annotation.Keep
import org.json.JSONObject
import java.util.Locale

/** Durable, per-assistant-reply timings. Values are null until actually observed. */
@Keep
data class ReplyMetrics(
    val modelSubmittedAtMs: Long? = null,
    val firstRawTokenAtMs: Long? = null,
    val speechEndedAtMs: Long? = null,
    val firstReplyPlaybackAtMs: Long? = null,
    val estimatedTokensPerSecond: Double? = null,
    val estimatedOutputTokens: Int? = null,
    val nativeAudioTiming: NativeAudioTimingMetrics? = null
) {
    fun submitted(atMs: Long) = copy(modelSubmittedAtMs = modelSubmittedAtMs ?: atMs)
    fun firstRawToken(atMs: Long) = copy(firstRawTokenAtMs = firstRawTokenAtMs ?: atMs)
    fun speechEnded(atMs: Long) = copy(speechEndedAtMs = speechEndedAtMs ?: atMs)
    fun firstActualPlayback(atMs: Long) = copy(firstReplyPlaybackAtMs = firstReplyPlaybackAtMs ?: atMs)
    /** Visible saved response only; excludes hidden reasoning/control text and caption passes. */
    fun withOutputText(text: String) = copy(estimatedOutputTokens = ((text.length.toLong() + 3) / 4).toInt())
    fun summary(): String = "TTFT ${duration(modelSubmittedAtMs, firstRawTokenAtMs)} · " +
        "TTF-SW ${duration(speechEndedAtMs, firstReplyPlaybackAtMs)} · " +
        (estimatedTokensPerSecond?.takeIf { it.isFinite() }?.let { "~${String.format(Locale.US, "%.1f", it)} tok/s" } ?: "tok/s —") +
        (estimatedOutputTokens?.let { " · ~$it tokens total" } ?: " · tokens —") +
        (nativeAudioTiming?.let { " · ${it.summary()}" } ?: "")
    fun json(): JSONObject = JSONObject().put("modelSubmittedAtMs", modelSubmittedAtMs)
        .put("firstRawTokenAtMs", firstRawTokenAtMs).put("speechEndedAtMs", speechEndedAtMs)
        .put("firstReplyPlaybackAtMs", firstReplyPlaybackAtMs).put("estimatedTokensPerSecond", estimatedTokensPerSecond)
        .put("estimatedOutputTokens", estimatedOutputTokens)
        .put("nativeAudioTiming", nativeAudioTiming?.json() ?: JSONObject.NULL)
    companion object {
        fun read(json: JSONObject?): ReplyMetrics? = json?.let {
            ReplyMetrics(it.longOrNull("modelSubmittedAtMs"), it.longOrNull("firstRawTokenAtMs"),
                it.longOrNull("speechEndedAtMs"), it.longOrNull("firstReplyPlaybackAtMs"),
                it.takeIf { j -> j.has("estimatedTokensPerSecond") }?.optDouble("estimatedTokensPerSecond")?.takeIf(Double::isFinite),
                it.longOrNull("estimatedOutputTokens")?.takeIf { count -> count <= Int.MAX_VALUE }?.toInt(),
                NativeAudioTimingMetrics.read(it.optJSONObject("nativeAudioTiming")))
        }
        val unavailable = ReplyMetrics()
        private fun JSONObject.longOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0 } else null
    }
    private fun duration(from: Long?, to: Long?): String = if (from == null || to == null || to < from) "—"
        else String.format(Locale.US, "%.2fs", (to - from) / 1000.0)
}
