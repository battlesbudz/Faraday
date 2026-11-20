package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject

/**
 * One voice turn's end-of-speech → first-audible budget on the monotonic millisecond clock.
 * Each stage keeps its first observation; missing stages and reversed deltas stay null.
 */
data class VoiceLatencyBudget(val turnId: String, val stageAtMs: Map<Stage, Long> = emptyMap()) {
    enum class Stage(val key: String) {
        SPEECH_END("speech_end"),
        FINAL_TRANSCRIPT("final_transcript"),
        FIRST_TOKEN("first_token"),
        FIRST_SPEAKABLE_TEXT("first_speakable_text"),
        FIRST_PCM("first_pcm"),
        FIRST_AUDIBLE("first_audible")
    }

    fun mark(stage: Stage, atMs: Long): VoiceLatencyBudget =
        if (atMs <= 0 || stage in stageAtMs) this else copy(stageAtMs = stageAtMs + (stage to atMs))

    fun at(stage: Stage): Long? = stageAtMs[stage]

    /** Delta from the previous pipeline stage; null when either side is unobserved. */
    fun deltaMs(stage: Stage): Long? {
        if (stage.ordinal == 0) return null
        return between(Stage.entries[stage.ordinal - 1], stage)
    }

    fun between(from: Stage, to: Stage): Long? {
        val start = stageAtMs[from] ?: return null
        val end = stageAtMs[to] ?: return null
        return (end - start).takeIf { it >= 0 }
    }

    val speechEndToFirstAudibleMs: Long? get() = between(Stage.SPEECH_END, Stage.FIRST_AUDIBLE)

    fun metrics(): Map<String, Long?> = linkedMapOf<String, Long?>().apply {
        Stage.entries.drop(1).forEach { stage ->
            put("latency_budget_${Stage.entries[stage.ordinal - 1].key}_to_${stage.key}_ms", deltaMs(stage))
        }
        put("latency_budget_speech_end_to_first_audible_ms", speechEndToFirstAudibleMs)
    }

    fun json(): JSONObject = JSONObject().put("turnId", turnId).also { json ->
        val offsets = JSONObject()
        val origin = stageAtMs[Stage.SPEECH_END]
        Stage.entries.forEach { stage ->
            offsets.put(stage.key, origin?.let { o -> stageAtMs[stage]?.let { it - o } } ?: JSONObject.NULL)
        }
        json.put("offsetsFromSpeechEndMs", offsets)
        val deltas = JSONObject()
        Stage.entries.drop(1).forEach { deltas.put(it.key, deltaMs(it) ?: JSONObject.NULL) }
        json.put("stageDeltasMs", deltas)
        json.put("speechEndToFirstAudibleMs", speechEndToFirstAudibleMs ?: JSONObject.NULL)
    }

    fun render(): String = buildString {
        append("Voice latency budget turn=$turnId")
        Stage.entries.drop(1).forEach { stage ->
            append(" ${Stage.entries[stage.ordinal - 1].key}→${stage.key}=${deltaMs(stage)?.let { "${it}ms" } ?: "—"}")
        }
        append(" total=${speechEndToFirstAudibleMs?.let { "${it}ms" } ?: "—"} clock=monotonic fillerExcluded=true")
    }
}
