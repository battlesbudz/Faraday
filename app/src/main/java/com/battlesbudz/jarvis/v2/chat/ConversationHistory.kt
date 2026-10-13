package com.battlesbudz.jarvis.v2.chat

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ConversationMessage(
    val id: String,
    val role: String,
    val text: String,
    val spoken: Boolean = false,
    val callId: String? = null,
    val contextText: String = text,
    val complete: Boolean = true,
    val attachment: ChatAttachment? = null,
    val actions: List<ActionReceipt> = emptyList(),
    /** Original final-input/checkpoint time; call updates retain it across late replacement. */
    val sourceTimestampMs: Long = System.currentTimeMillis(),
    /** Reply-owned timings survive streaming replacements, call sync and process restart. */
    val metrics: ReplyMetrics? = null,
    /** Native/call reply identity; display row IDs may instead use call/index. */
    val sourceReplyId: String? = null
)
data class ActionReceipt(val name: String, val message: String, val succeeded: Boolean)
data class ConversationThread(val id: String, val messages: List<ConversationMessage> = emptyList()) {
    val title: String get() = messages.firstOrNull { it.role == "You" }?.text?.take(60) ?: "New conversation"
}

/** One app-owned thread across text, calls, model switches and process recreation. */
class ConversationHistory(private val preferences: SharedPreferences) {
    private val threads = linkedMapOf<String, ConversationThread>()
    private val _current = MutableStateFlow(ConversationThread(UUID.randomUUID().toString()))
    val current = _current.asStateFlow()
    private var lastProgressSave = 0L
    init {
        runCatching {
            val saved = JSONArray(preferences.getString("threads", "[]"))
            for (i in 0 until saved.length()) {
                val t = saved.getJSONObject(i)
                val entries = t.getJSONArray("messages")
                val messages = (0 until entries.length()).map { n ->
                    val m = entries.getJSONObject(n)
                    ConversationMessage(m.getString("id"), m.getString("role"), m.getString("text"),
                        m.optBoolean("spoken"), m.optString("callId").takeIf { it.isNotBlank() },
                        m.optString("contextText", m.getString("text")), m.optBoolean("complete", true),
                        m.optJSONObject("attachment")?.let { a -> runCatching {
                            ChatAttachment(a.getString("uri"), AttachmentKind.valueOf(a.getString("kind")))
                        }.getOrNull() }, m.optJSONArray("actions")?.let { actions -> (0 until actions.length()).map { i ->
                            actions.getJSONObject(i).let { a -> ActionReceipt(a.getString("name"), a.getString("message"), a.getBoolean("succeeded")) }
                        } }.orEmpty(), m.optLong("sourceTimestampMs", System.currentTimeMillis()),
                        ReplyMetrics.read(m.optJSONObject("metrics")),
                        m.optString("sourceReplyId").takeUnless { it.isBlank() || it == "null" })
                }
                threads[t.getString("id")] = ConversationThread(t.getString("id"), messages)
            }
        }
        _current.value = threads[preferences.getString("active", null)] ?: threads.values.lastOrNull() ?: _current.value
        threads[_current.value.id] = _current.value
    }
    @Synchronized fun list(): List<ConversationThread> = threads.values.toList().asReversed()
    @Synchronized fun newConversation() {
        _current.value = ConversationThread(UUID.randomUUID().toString())
        threads[_current.value.id] = _current.value
        persist()
    }
    @Synchronized fun select(id: String) { threads[id]?.let { _current.value = it; persist() } }
    @Synchronized fun appendUser(text: String, attachment: ChatAttachment? = null): String {
        val id = UUID.randomUUID().toString()
        replace(_current.value.copy(messages = _current.value.messages + ConversationMessage(id, "You", text,
            spoken = attachment?.kind == AttachmentKind.AUDIO,
            contextText = text + (attachment?.let { "\n[${it.kind.name.lowercase()} attached to this message]" } ?: ""), attachment = attachment)))
        return id
    }
    @Synchronized fun updateReply(threadId: String, id: String, text: String, complete: Boolean,
        actions: List<ActionReceipt> = emptyList()) {
        val thread = threads[threadId] ?: return
        val prior = thread.messages.firstOrNull { it.id == id }
        val savedActions = if (actions.isEmpty()) prior?.actions.orEmpty() else actions
        val receiptText = savedActions.joinToString(" ") { it.message }
        val visible = if (receiptText.isBlank() || text.contains(receiptText)) text
            else listOf(text, receiptText).filter { it.isNotBlank() }.joinToString("\n")
        val message = ConversationMessage(id, "Jarvis", visible, complete = complete,
            contextText = if (complete) visible else receiptText, actions = savedActions,
            sourceTimestampMs = prior?.sourceTimestampMs ?: System.currentTimeMillis(), metrics = (prior?.metrics ?: ReplyMetrics.unavailable).withOutputText(visible))
        val index = thread.messages.indexOfFirst { it.id == id }
        val entries = thread.messages.toMutableList()
        if (index < 0) entries += message else entries[index] = message
        replace(thread.copy(messages = entries), complete)
    }
    @Synchronized fun updateReplyMetrics(threadId: String, id: String, durable: Boolean = true, transform: (ReplyMetrics) -> ReplyMetrics) {
        val thread = threads[threadId] ?: return
        val index = thread.messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val entries = thread.messages.toMutableList()
        entries[index] = entries[index].copy(metrics = transform(entries[index].metrics ?: ReplyMetrics.unavailable))
        replace(thread.copy(messages = entries), durable = durable)
    }

    @Synchronized fun recordReplyAction(threadId: String, id: String, receipt: ActionReceipt) {
        val thread = threads[threadId] ?: return
        val index = thread.messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val entries = thread.messages.toMutableList()
        val prior = entries[index]
        val actions = prior.actions + receipt // the runner, not receipt values, decides replay deduplication.
        val visible = listOf(prior.text, receipt.message).filter { it.isNotBlank() }.joinToString("\n")
        entries[index] = prior.copy(text = visible, contextText = actions.joinToString(" ") { it.message }, actions = actions,
            metrics = (prior.metrics ?: ReplyMetrics.unavailable).withOutputText(visible))
        replace(thread.copy(messages = entries))
    }

    /** Call snapshots replace their own entries, including late playback receipts; never duplicate them. */
    @Synchronized fun syncCall(call: VoiceCallRecord) {
        val threadId = call.conversationId ?: return
        val thread = threads[threadId] ?: return
        val messages = call.transcript.mapIndexed { index, entry ->
            ConversationMessage("${call.id}:$index", entry.role, entry.text, entry.role == "You" && entry.origin == TranscriptOrigin.SPOKEN, call.id,
                entry.forConversation()?.text.orEmpty(), entry.complete, sourceTimestampMs = entry.timestampMs,
                metrics = entry.metrics, sourceReplyId = entry.replyId)
        }
        val first = thread.messages.indexOfFirst { it.callId == call.id }
        val entries = thread.messages.filterNot { it.callId == call.id }.toMutableList()
        entries.addAll(if (first < 0) entries.size else first.coerceAtMost(entries.size), messages)
        replace(thread.copy(messages = entries), call.endedAtMs != null || call.transcript.lastOrNull()?.complete == true)
    }
    /** Older calls have no thread ID; import once when explicitly opened for text continuation. */
    @Synchronized fun openCall(call: VoiceCallRecord) {
        val id = call.conversationId ?: "legacy:${call.id}"
        val existing = threads.containsKey(id)
        if (!existing) threads[id] = ConversationThread(id)
        _current.value = threads.getValue(id)
        // Existing linked threads already receive authoritative store snapshots. A stale
        // history-screen selection must not roll back a late playback receipt.
        if (!existing) syncCall(call.copy(conversationId = id))
        persist()
    }
    @Synchronized fun removeCall(callId: String) {
        threads.values.toList().forEach { thread ->
            if (thread.messages.any { it.callId == callId })
                replace(thread.copy(messages = thread.messages.filterNot { it.callId == callId }))
        }
    }
    @Synchronized fun context(excludingCall: String? = null): List<ChatEntry> =
        _current.value.messages.filter { (excludingCall == null || it.callId != excludingCall) && it.contextText.isNotBlank() }
            .takeLast(128).map { ChatEntry(it.role, it.contextText) }

    /**
     * Keeps visible history but fences pre-mutation prompt context by stable text IDs and original
     * call-entry timestamps. A late checkpoint of old call audio stays excluded, while a new final
     * utterance in that still-active call remains available.
     */
    @Synchronized fun markMemoryContextCutoff(durable: Boolean = false): Boolean {
        var succeeded = true
        val now = System.currentTimeMillis()
        threads.values.forEach { thread ->
            val directIds = thread.messages.filter { it.callId == null }.map { it.id }
            val calls = thread.messages.mapNotNull { it.callId }.distinct().associateWith { now }
            val value = org.json.JSONObject().put("messages", org.json.JSONArray(directIds))
                .put("calls", org.json.JSONObject(calls))
            val edit = preferences.edit().putString("memory_cutoff:${thread.id}", value.toString())
            if (durable) succeeded = edit.commit() && succeeded else edit.apply()
        }
        return succeeded
    }

    @Synchronized fun contextAfterMemoryCutoff(excludingCall: String? = null): List<ChatEntry> {
        val thread = _current.value
        val cutoff = runCatching { org.json.JSONObject(preferences.getString("memory_cutoff:${thread.id}", "{}")) }
            .getOrDefault(org.json.JSONObject())
        val directIds = cutoff.optJSONArray("messages")?.let { array ->
            (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
        }.orEmpty()
        val calls = cutoff.optJSONObject("calls")
        return thread.messages.filter { message ->
            val callCutoff = message.callId?.let { calls?.optLong(it, Long.MIN_VALUE) } ?: Long.MIN_VALUE
            (message.callId == null && message.id !in directIds) ||
                (message.callId != null && message.sourceTimestampMs > callCutoff)
        }.filter { (excludingCall == null || it.callId != excludingCall) && it.contextText.isNotBlank() }
            .takeLast(128).map { ChatEntry(it.role, it.contextText) }
    }

    private fun replace(thread: ConversationThread, durable: Boolean = true) {
        threads[thread.id] = thread
        if (_current.value.id == thread.id) _current.value = thread
        val now = System.currentTimeMillis()
        if (durable || now - lastProgressSave >= 500) { persist(); lastProgressSave = now }
    }
    private fun persist() {
        val array = JSONArray()
        threads.values.forEach { thread ->
            val messages = JSONArray()
            thread.messages.forEach { m -> messages.put(JSONObject().put("id", m.id).put("role", m.role)
                .put("text", m.text).put("spoken", m.spoken).put("callId", m.callId)
                .put("contextText", m.contextText).put("complete", m.complete).put("sourceTimestampMs", m.sourceTimestampMs)
                .put("metrics", m.metrics?.json()).put("sourceReplyId", m.sourceReplyId)
                .put("attachment", m.attachment?.let { JSONObject().put("uri", it.uri).put("kind", it.kind.name) })
                .put("actions", JSONArray().also { actions -> m.actions.forEach { action -> actions.put(JSONObject().put("name", action.name).put("message", action.message).put("succeeded", action.succeeded)) } })) }
            array.put(JSONObject().put("id", thread.id).put("messages", messages))
        }
        preferences.edit().putString("active", _current.value.id).putString("threads", array.toString()).apply()
    }
}

/** Observe persisted/coalesced snapshots, never activity callbacks or provisional ASR hypotheses. */
class ConversationVoiceCallStore(private val delegate: VoiceCallStore, private val history: ConversationHistory) : VoiceCallStore {
    override fun list() = delegate.list()
    override fun save(call: VoiceCallRecord) { delegate.save(call); history.syncCall(call) }
    override fun delete(callId: String) { delegate.delete(callId); history.removeCall(callId) }
}
