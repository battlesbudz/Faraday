package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Immutable per-reply measurements. Missing values are unknown, never zero latency. */
data class InferenceTiming(
    val stage: String, val firstTokenMs: Long?, val totalMs: Long?,
    val estimatedTokens: Int?, val estimatedTokensPerSecond: Double?,
    val prepared: Boolean = false
) {
    fun json() = JSONObject().put("stage", stage).put("firstTokenMs", firstTokenMs)
        .put("totalMs", totalMs).put("tokens", estimatedTokens).put("tokensPerSecond", estimatedTokensPerSecond)
        .put("prepared", prepared)
    companion object {
        fun from(stage: String, result: GenerationResult, prepared: Boolean = false) = InferenceTiming(
            stage, result.timeToFirstTokenMs.takeIf { it >= 0 }, result.totalGenerationTimeMs.takeIf { it >= 0 },
            result.outputTokens, result.decodeTokensPerSecond?.takeIf { it.isFinite() }, prepared)
        fun read(j: JSONObject) = InferenceTiming(j.optString("stage"), j.longOrNull("firstTokenMs"),
            j.longOrNull("totalMs"), if (j.has("tokens")) j.optInt("tokens") else null,
            if (j.has("tokensPerSecond")) j.optDouble("tokensPerSecond").takeIf { it.isFinite() } else null,
            j.optBoolean("prepared"))
    }
}

data class TurnLatency(
    val id: String,
    val totalMs: Long,
    val firstVisibleTextMs: Long?,
    val modelLoadMs: Long,
    val lookupMs: Long,
    val passes: List<InferenceTiming> = emptyList(),
    val voice: String? = null,
    val speechEndToReplyMs: Long? = null,
    val textToPcmMs: Long? = null,
    val textToPlaybackMs: Long? = null,
    val supplyGapMs: Long? = null
) {
    fun json() = JSONObject().put("id", id).put("totalMs", totalMs)
        .put("firstVisibleTextMs", firstVisibleTextMs).put("modelLoadMs", modelLoadMs).put("lookupMs", lookupMs)
        .put("passes", JSONArray().also { a -> passes.forEach { a.put(it.json()) } })
        .put("voice", voice).put("speechEndToReplyMs", speechEndToReplyMs).put("textToPcmMs", textToPcmMs)
        .put("textToPlaybackMs", textToPlaybackMs).put("supplyGapMs", supplyGapMs)

    fun summary(): String {
        val first = passes.firstOrNull()
        return if (first == null) "Reply ${seconds(totalMs)} · Model timing unavailable" else buildString {
            append("TTFT ${seconds(first.firstTokenMs)}")
            if (first.prepared) append(" (prepared)")
            first.estimatedTokensPerSecond?.let { append(" · ~${String.format(Locale.US, "%.1f", it)} tok/s") }
            if (passes.size == 1) first.estimatedTokens?.let { append(" · ~$it tokens") }
            else append(" · ${passes.size} model passes")
            append(" · Reply ${seconds(totalMs)}")
        }
    }
    fun details(): String = buildString {
        appendLine("Reply processing: ${seconds(totalMs)}")
        appendLine("First visible text: ${seconds(firstVisibleTextMs)} from reply request")
        appendLine("Model loading: ${seconds(modelLoadMs)}${if (modelLoadMs == 0L) " (reused / not loaded)" else ""}")
        appendLine("Wikipedia lookup: ${seconds(lookupMs)}")
        passes.forEach { p ->
            appendLine("${p.stage}: first token ${seconds(p.firstTokenMs)}, generation ${seconds(p.totalMs)}${if (p.prepared) " (prepared before this request)" else ""}")
        }
        val accountedMs = modelLoadMs + lookupMs + passes.filterNot { it.prepared }.sumOf { it.totalMs ?: 0 }
        appendLine("Other reply processing: ${seconds((totalMs - accountedMs).coerceAtLeast(0))} (context, tools, checks and scheduling)")
        voice?.let {
            appendLine("Voice: $it")
            appendLine("Last detected speech → first reply audio: ${seconds(speechEndToReplyMs)}")
            appendLine("First speech text → audio ready: ${seconds(textToPcmMs)}")
            appendLine("First speech text → playback: ${seconds(textToPlaybackMs)}")
            appendLine("Estimated audio supply gaps: ${seconds(supplyGapMs)}")
        }
        appendLine("TTFT starts at app generation entry, including lazy conversation preparation; it excludes model loading and speech synthesis. Token counts/speed marked ~ are estimates. Timings overlap; do not add them together. Voice timing excludes cached filler and uses the playback head as an audio proxy, not an acoustic microphone measurement.")
        append("Turn $id")
    }
    companion object {
        fun read(j: JSONObject?): TurnLatency? = j?.let { runCatching {
            val a = it.optJSONArray("passes") ?: JSONArray()
            TurnLatency(it.getString("id"), it.getLong("totalMs"), it.longOrNull("firstVisibleTextMs"),
                it.optLong("modelLoadMs"), it.optLong("lookupMs"),
                (0 until a.length()).map { i -> InferenceTiming.read(a.getJSONObject(i)) },
                it.optString("voice").takeIf(String::isNotBlank), it.longOrNull("speechEndToReplyMs"),
                it.longOrNull("textToPcmMs"), it.longOrNull("textToPlaybackMs"), it.longOrNull("supplyGapMs"))
        }.getOrNull() }
        fun seconds(ms: Long?): String = ms?.let { String.format(Locale.US, "%.2fs", it / 1000.0) } ?: "—"
    }
}
private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0 } else null
