package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.chat.ConversationThread
import org.json.JSONArray
import org.json.JSONObject

/** Full scoped pipeline evidence plus text-free saved reply observations, including older replies. */
object ConversationMetricsExport {
    fun json(report: PipelineBenchmarkReport, thread: ConversationThread?): JSONObject {
        val result = report.toJson(includeText = false)
        if (thread == null) return result
        result.put("replyMetricsDefinitions", JSONObject()
            .put("ttftMs", "model submission to first raw text callback")
            .put("timeToFirstPlaybackMs", "last detected speech to playback-head proxy; acoustic onset unmeasured")
            .put("nativeAudioTiming", "validated native PCM admission to first reply playback-head proxy, including remaining capture; bounded clock-alignment interval; historical pre-roll is not backdated; actual graph completion counts are separate")
            .put("nativeAudioClockContract", "reviewed AOSP Android API30–36 implementation-family assumption plus bounded runtime calibration; installed/OEM binary not attested; acoustic onset unmeasured")
            .put("estimatedOutputTokens", "ceil(saved visible response UTF-16 characters / 4); not native generated tokens")
            .put("nativeOutputTokens", "see each native submission's exactOutputTokens; caption/repair/tool passes remain separate"))
        result.put("replyMetrics", JSONArray().apply {
            thread.messages.filter { it.role == "Jarvis" }.forEach { message ->
                val metrics = (message.metrics ?: ReplyMetrics.unavailable).withOutputText(message.text)
                put(JSONObject().put("replyId", message.sourceReplyId ?: message.id)
                    .put("conversationId", thread.id).put("callId", message.callId ?: JSONObject.NULL)
                    .put("complete", message.complete)
                    .put("ttftMs", duration(metrics.modelSubmittedAtMs, metrics.firstRawTokenAtMs) ?: JSONObject.NULL)
                    .put("timeToFirstPlaybackMs", duration(metrics.speechEndedAtMs, metrics.firstReplyPlaybackAtMs) ?: JSONObject.NULL)
                    .put("estimatedTokensPerSecond", metrics.estimatedTokensPerSecond ?: JSONObject.NULL)
                    .put("estimatedOutputTokens", metrics.estimatedOutputTokens ?: JSONObject.NULL)
                    .put("nativeAudioTiming", metrics.nativeAudioTiming?.json() ?: JSONObject.NULL))
            }
        })
        return result
    }

    private fun duration(start: Long?, end: Long?): Long? =
        if (start == null || end == null || end < start) null else end - start
}
