package com.battlesbudz.jarvis.v2.voice

import org.json.JSONArray
import org.json.JSONObject

internal fun SpeechDelivery.json() = JSONObject().put("version", 1).put("turnId", turnId)
    .put("state", state.name).put("playedFrames", playedFrames).put("revision", revision)
    .put("precision", "completed_segment_frames_partial_unknown")
    .put("spans", JSONArray().also { array -> spans.forEach { span ->
        array.put(JSONObject().put("index", span.index).put("text", span.text)
            .put("startFrame", span.startFrame).put("endFrame", span.endFrame)
            .put("sampleRate", span.sampleRate).put("sealed", span.sealed))
    } })

internal fun readSpeechDelivery(j: JSONObject?, replyId: String?): SpeechDelivery? {
    if (j == null && replyId == null) return null // Old records have unknown delivery precision.
    return runCatching {
        requireNotNull(j); require(j.getInt("version") == 1)
        val id = j.getString("turnId"); require(replyId == null || id == replyId)
        val array = j.getJSONArray("spans")
        var end = 0L
        val spans = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            DeliveredSpeechSpan(item.getInt("index"), item.getString("text"), item.getLong("startFrame"),
                item.getLong("endFrame"), item.getInt("sampleRate"), item.getBoolean("sealed")).also {
                require(it.index == i && it.startFrame == end && it.endFrame > end && it.sampleRate > 0)
                end = it.endFrame
            }
        }
        val played = j.getLong("playedFrames"); require(played in 0..end)
        SpeechDelivery(id, SpeechDeliveryState.valueOf(j.getString("state")), played, spans, j.getLong("revision"))
    }.getOrElse { SpeechDelivery(replyId.orEmpty(), SpeechDeliveryState.FAILED) }
}
