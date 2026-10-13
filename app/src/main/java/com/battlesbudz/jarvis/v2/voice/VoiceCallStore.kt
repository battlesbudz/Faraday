package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

interface VoiceCallStore {
    fun list(): List<VoiceCallRecord>
    fun save(call: VoiceCallRecord)
    /** Transient text may be coalesced; accepted turns and lifecycle boundaries use save(). */
    fun saveProgress(call: VoiceCallRecord) = save(call)
    fun delete(callId: String)
}
/** Local app-private storage. No export, upload, or raw-audio persistence. */
class SharedPreferencesVoiceCallStore(
    private val preferences: SharedPreferences,
    private val key: String = "voice_calls"
) : VoiceCallStore {
    override fun list(): List<VoiceCallRecord> = decode(preferences.getString(key, null).orEmpty())

    @Synchronized
    override fun save(call: VoiceCallRecord) {
        val updated = list().filterNot { it.id == call.id } + call
        preferences.edit().putString(key, encode(updated)).apply()
    }

    @Synchronized
    override fun delete(callId: String) {
        preferences.edit().putString(key, encode(list().filterNot { it.id == callId })).apply()
    }

    companion object {
        internal fun encode(calls: List<VoiceCallRecord>): String = JSONArray().also { array ->
            calls.forEach { call ->
                array.put(JSONObject().apply {
                    put("id", call.id)
                    put("startedAtMs", call.startedAtMs)
                    call.conversationId?.let { put("conversationId", it) }
                    call.endedAtMs?.let { put("endedAtMs", it) }
                    call.title?.let { put("title", it) }
                    put("transcript", JSONArray().also { entries ->
                        call.transcript.forEach { entry ->
                            entries.put(JSONObject().apply {
                                put("role", entry.role)
                                put("text", entry.text)
                                put("timestampMs", entry.timestampMs)
                                put("complete", entry.complete)
                                entry.latency?.let { put("latency", it.json()) }
                                entry.metrics?.let { put("metrics", it.json()) }
                                entry.replyId?.let { put("replyId", it) }
                                entry.delivery?.let { put("delivery", it.json()) }
                                put("generationComplete", entry.generationComplete)
                                put("origin", entry.origin.name)
                                if (entry.actions.isNotEmpty()) put("actions", JSONArray().also { actions ->
                                    entry.actions.forEach { action -> actions.put(JSONObject().put("name", action.name)
                                        .put("message", action.message).put("succeeded", action.succeeded)) }
                                })
                            })
                        }
                    })
                    call.taskStatus?.let { task ->
                        put("taskStatus", JSONObject().apply {
                            put("state", task.state.name)
                            put("completedSteps", JSONArray(task.completedSteps))
                            put("pendingSteps", JSONArray(task.pendingSteps))
                        })
                    }
                })
            }
        }.toString()

        internal fun decode(value: String): List<VoiceCallRecord> {
            if (value.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val transcript = item.optJSONArray("transcript")?.let { entries ->
                        (0 until entries.length()).mapNotNull { entryIndex ->
                            entries.optJSONObject(entryIndex)?.let { entry ->
                                TranscriptEntry(
                                    role = entry.optString("role"),
                                    text = entry.optString("text"),
                                    timestampMs = entry.optLong("timestampMs"),
                                    complete = entry.optBoolean("complete", true),
                                    latency = com.battlesbudz.jarvis.v2.diagnostics.TurnLatency.read(entry.optJSONObject("latency")),
                                    metrics = com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.read(entry.optJSONObject("metrics")),
                                    replyId = entry.optString("replyId").takeIf { it.isNotBlank() },
                                    delivery = readSpeechDelivery(entry.optJSONObject("delivery"), entry.optString("replyId").takeIf { it.isNotBlank() }),
                                    generationComplete = entry.optBoolean("generationComplete", entry.optBoolean("complete", true)),
                                    actions = entry.optJSONArray("actions")?.let { actions -> (0 until actions.length()).mapNotNull { i ->
                                        actions.optJSONObject(i)?.let { VoiceActionOutcome(it.optString("name"), it.optString("message"), it.optBoolean("succeeded")) }
                                    } }.orEmpty(),
                                    origin = runCatching { TranscriptOrigin.valueOf(entry.optString("origin", TranscriptOrigin.SPOKEN.name)) }
                                        .getOrDefault(TranscriptOrigin.SPOKEN)
                                )
                            }
                        }
                    }.orEmpty()
                    val taskObject = item.optJSONObject("taskStatus")
                    val task = taskObject?.let {
                        runCatching {
                            VoiceTaskStatus(
                                state = VoiceTaskState.valueOf(it.optString("state")),
                                completedSteps = it.optJSONArray("completedSteps").toStringList(),
                                pendingSteps = it.optJSONArray("pendingSteps").toStringList()
                            )
                        }.getOrNull()
                    }
                    VoiceCallRecord(
                        id = id,
                        startedAtMs = item.optLong("startedAtMs"),
                        endedAtMs = item.optLong("endedAtMs").takeIf { item.has("endedAtMs") },
                        title = item.optString("title").takeIf { it.isNotBlank() },
                        transcript = transcript,
                        taskStatus = task,
                        conversationId = item.optString("conversationId").takeIf { it.isNotBlank() }
                    )
                }
            }.getOrDefault(emptyList())
        }

        private fun JSONArray?.toStringList(): List<String> = if (this == null) {
            emptyList()
        } else {
            (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }
}
