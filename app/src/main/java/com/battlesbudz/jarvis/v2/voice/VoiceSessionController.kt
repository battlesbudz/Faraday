package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Owns Voice Call lifecycle and durable checkpoints. It does not run models,
 * speak, or execute tools; those are injected through the surrounding runtime.
 */
class VoiceSessionController(
    private val store: VoiceCallStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var activeCall: VoiceCallRecord? = null
    private var resumedFromCallId: String? = null
    private var captionInputVersion = 0L
    private var farewellCommittedCall: String? = null

    @Synchronized internal fun currentInputRevision(callId: String): Long? =
        captionInputVersion.takeIf { activeCall?.id == callId && farewellCommittedCall != callId }

    /** Acoustic admission linearizes before cancellation is scheduled. Never acquires the queue lock. */
    @Synchronized internal fun revokeCaptionForInput(callId: String): Boolean {
        if (activeCall?.id != callId || farewellCommittedCall == callId) return false
        captionInputVersion++
        return true
    }

    @Synchronized internal fun finishInputIfCurrent(callId: String, revision: Long, text: String): Boolean {
        if (currentInputRevision(callId) != revision) return false
        farewellCommittedCall = callId
        appendTranscript("You", text)
        return true
    }

    /** Authority for one still-pending paired input; never a general history edit token. */
    internal class CaptionTicket internal constructor(val callId: String, val replyId: String,
        internal val inputVersion: Long, internal val expectedText: String)

    @Synchronized internal fun captionTicket(callId: String, replyId: String, expectedText: String): CaptionTicket? {
        val call = activeCall?.takeIf { it.id == callId && farewellCommittedCall != callId } ?: return null
        if (call.transcript.lastOrNull()?.replyId != replyId || call.transcript.lastOrNull()?.role != "Jarvis") return null
        val user = call.transcript.getOrNull(call.transcript.lastIndex - 1) ?: return null
        if (user.role != "You" || user.text != expectedText) return null
        return CaptionTicket(callId, replyId, captionInputVersion, expectedText)
    }

    internal class CaptionCommit internal constructor(val callId: String, val replyId: String,
        val text: String, val inputVersion: Long, val farewell: Boolean)

    /** Bounded state mutation only. The final authorization CAS and mutation share this call lock. */
    @Synchronized internal fun commitCaption(ticket: CaptionTicket, text: String,
        isCurrent: () -> Boolean, farewell: Boolean = false, authorize: () -> Boolean = { true }): CaptionCommit? {
        if (text.isBlank() || !isCurrent() || captionInputVersion != ticket.inputVersion ||
            captionTicket(ticket.callId, ticket.replyId, ticket.expectedText) == null) return null
        val call = requireActiveCall()
        val entries = call.transcript.toMutableList()
        val userIndex = entries.lastIndex - 1
        if (!authorize()) return null
        entries[userIndex] = entries[userIndex].copy(text = text, complete = true, generationComplete = true)
        captionInputVersion++
        activeCall = call.copy(transcript = entries)
        if (farewell) farewellCommittedCall = ticket.callId
        return CaptionCommit(ticket.callId, ticket.replyId, text, captionInputVersion, farewell)
    }

    /** Persist the latest record, never an old captured snapshot over a newer input/call. */
    @Synchronized internal fun checkpointCaption(commit: CaptionCommit) {
        if (activeCall?.id == commit.callId && captionInputVersion >= commit.inputVersion) checkpoint()
        // End already persisted this committed entry if the call finished meanwhile.
    }

    internal fun publishCaption(ticket: CaptionTicket, text: String,
        isCurrent: () -> Boolean, farewell: Boolean = false, onPublished: () -> Unit): Boolean {
        val committed = commitCaption(ticket, text, isCurrent, farewell) ?: return false
        checkpointCaption(committed)
        onPublished()
        return true
    }

    /**
     * Fired after a new call segment begins, outside the session lock. The
     * runtime uses it to start call-scoped video capture; unit tests leave
     * it null. Never throws into the call lifecycle — a failing observer is
     * reported, not propagated.
     */
    var onCallBegan: ((callId: String) -> Unit)? = null

    fun beginCall(conversationId: String? = null): VoiceCallRecord {
        val record = beginCallLocked(conversationId)
        notifyCallBegan(record.id)
        return record
    }

    @Synchronized private fun beginCallLocked(conversationId: String? = null): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        val now = nowMs()
        resumedFromCallId = null
        return VoiceCallRecord(UUID.randomUUID().toString(), now, conversationId = conversationId).also {
            activeCall = it
            _state.value = VoiceSessionState.ACTIVELY_LISTENING
            checkpoint()
        }
    }

    @Synchronized fun appendTranscript(role: String, text: String, complete: Boolean = true,
                         latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null,
                         origin: TranscriptOrigin = TranscriptOrigin.SPOKEN) {
        val call = requireActiveCall()
        if (role == "You") captionInputVersion++
        val entries = call.transcript.toMutableList()
        val previous = entries.lastOrNull()
        if (previous?.role == role && !previous.complete) {
            entries[entries.lastIndex] = previous.copy(text = text, complete = complete, generationComplete = complete, timestampMs = nowMs(), latency = latency ?: previous.latency)
        } else {
            entries += TranscriptEntry(role, text, nowMs(), complete, latency, origin = origin)
        }
        activeCall = call.copy(transcript = entries)
        if (complete) checkpoint() else store.saveProgress(requireActiveCall())
    }

    /** Replace only the user entry paired with this exact reply, never append a second user turn. */
    @Synchronized fun updateUserTranscriptForReply(callId: String, replyId: String, text: String, expectedText: String? = null): Boolean {
        if (text.isBlank() || activeCall?.id != callId) return false
        val call = requireActiveCall()
        val replyIndex = call.transcript.indexOfFirst { it.replyId == replyId && it.role == "Jarvis" }
        if (replyIndex <= 0 || call.transcript[replyIndex - 1].role != "You") return false
        if (expectedText != null && call.transcript[replyIndex - 1].text != expectedText) return false
        val entries = call.transcript.toMutableList()
        entries[replyIndex - 1] = entries[replyIndex - 1].copy(text = text, complete = true, generationComplete = true)
        captionInputVersion++
        activeCall = call.copy(transcript = entries)
        checkpoint()
        return true
    }

    /** Stable reply IDs separate generation, audio delivery and tool receipts. */
    @Synchronized fun beginReply(callId: String, replyId: String) {
        if (activeCall?.id != callId) return
        val call = requireActiveCall()
        check(call.transcript.none { it.replyId == replyId })
        captionInputVersion++
        activeCall = call.copy(transcript = call.transcript + TranscriptEntry("Jarvis", "", nowMs(),
            complete = false, replyId = replyId, delivery = SpeechDelivery(replyId), generationComplete = false))
        checkpoint()
    }
    @Synchronized fun updateReplyText(callId: String, replyId: String, text: String, finished: Boolean = false,
        latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null) {
        if (activeCall?.id != callId) return // Late generation cannot rewrite an ended/replaced call.
        changeReply(callId, replyId, durable = finished) { entry ->
            if (entry.generationComplete) entry else entry.copy(text = text, generationComplete = finished,
                complete = finished && entry.delivery?.state == SpeechDeliveryState.COMPLETED,
                latency = latency ?: entry.latency)
        }
    }
    /**
     * Only queue-terminal evidence may update an ended call. It targets an already saved reply
     * ID and never revives the call or permits a late ordinary generation to replace its text.
     */
    @Synchronized fun updateTerminalReplyTextForCall(callId: String, replyId: String, text: String) {
        changeReply(callId, replyId, durable = true) { entry ->
            entry.copy(text = text, generationComplete = true)
        }
    }

    /** Persists one non-revivable terminal input receipt against its original call. */
    @Synchronized fun recordTerminalInputForCall(callId: String, eventId: String, text: String) {
        val current = activeCall?.takeIf { it.id == callId } ?: store.list().firstOrNull { it.id == callId } ?: return
        if (current.transcript.any { it.replyId == "terminal-$eventId" }) return
        val updated = current.copy(transcript = current.transcript + TranscriptEntry("Jarvis", text, nowMs(),
            complete = true, replyId = "terminal-$eventId", generationComplete = true))
        if (activeCall?.id == callId) activeCall = updated
        store.save(updated)
    }

    @Synchronized fun updateDelivery(callId: String, delivery: SpeechDelivery) {
        changeReply(callId, delivery.turnId) { entry ->
            val previous = entry.delivery
            if (previous?.terminal == true || delivery.revision <= (previous?.revision ?: -1)) entry
            else entry.copy(delivery = delivery,
                complete = entry.generationComplete && delivery.state == SpeechDeliveryState.COMPLETED)
        }
    }
    @Synchronized fun recordReplyAction(callId: String, replyId: String, outcome: VoiceActionOutcome) {
        changeReply(callId, replyId) { entry -> entry.copy(actions = entry.actions + outcome) }
    }
    private fun changeReply(callId: String, replyId: String, durable: Boolean = true,
        transform: (TranscriptEntry) -> TranscriptEntry) {
        // Final playback/atomic tool receipts may arrive after End, but never target a different call.
        // A deleted call is not recreated.
        val live = activeCall?.id == callId
        val call = if (live) activeCall else store.list().firstOrNull { it.id == callId }
        if (call == null) return
        val index = call.transcript.indexOfFirst { it.replyId == replyId }
        if (index < 0) return
        val transformed = transform(call.transcript[index])
        val entry = if (transformed.role == "Jarvis") transformed.copy(metrics =
            (transformed.metrics ?: com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.unavailable).withOutputText(transformed.text)) else transformed
        if (entry == call.transcript[index]) return
        val entries = call.transcript.toMutableList().also { it[index] = entry }
        val updated = call.copy(transcript = entries)
        if (live) activeCall = updated
        if (durable || !live) store.save(updated) else store.saveProgress(updated)
    }

    /** Late native/audio events are tied to the original reply ID, even after another turn begins. */
    @Synchronized fun updateReplyMetrics(callId: String, replyId: String, durable: Boolean = true,
        transform: (com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics) -> com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics) {
        changeReply(callId, replyId, durable = durable) { entry ->
            entry.copy(metrics = transform(entry.metrics ?: com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.unavailable))
        }
    }

    /** Late speech metrics can update only the reply carrying this measurement ID. */
    @Synchronized fun updateReplyLatency(latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency) {
        val call = activeCall ?: return
        val index = call.transcript.indexOfFirst { it.role == "Jarvis" && it.latency?.id == latency.id }
        if (index < 0) return
        val entries = call.transcript.toMutableList()
        entries[index] = entries[index].copy(latency = latency)
        activeCall = call.copy(transcript = entries)
        checkpoint()
    }

    @Synchronized fun currentCallId(): String? = activeCall?.id

    @Synchronized fun currentTranscript(): List<TranscriptEntry> = activeCall?.transcript.orEmpty()

    /** Only this call, or the explicitly resumed transcript, supplies dialogue. */
    @Synchronized fun conversationContext(): List<TranscriptEntry> =
        currentTranscript().mapNotNull { it.forConversation() }.takeLast(8)

    @Synchronized fun contextProvenance(): String {
        val selected = conversationContext()
        return "callId=${activeCall?.id} callStartedAtMs=${activeCall?.startedAtMs} " +
            "historySource=${if (resumedFromCallId == null) "current_call" else "explicit_resume"} " +
            "resumedFromCallId=$resumedFromCallId automaticPriorCalls=false " +
            "storedEntries=${currentTranscript().size} selectedEntries=${selected.size} entryLimit=8 " +
            "oldestEntryAtMs=${selected.firstOrNull()?.timestampMs} newestEntryAtMs=${selected.lastOrNull()?.timestampMs} " +
            "perEntryPromptLimitUser=300 perEntryPromptLimitAssistant=450 contextBudgetChars=3000"
    }

    /** Starts a new linked session with the prior call's transcript as context. */
    fun resumeCall(call: VoiceCallRecord): VoiceCallRecord {
        val record = resumeCallLocked(call)
        notifyCallBegan(record.id)
        return record
    }

    private fun notifyCallBegan(callId: String) {
        try {
            onCallBegan?.invoke(callId)
        } catch (_: Exception) {
            // Capture startup must never break the call itself.
        }
    }

    @Synchronized private fun resumeCallLocked(call: VoiceCallRecord): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        resumedFromCallId = call.id
        val latest = store.list().firstOrNull { it.id == call.id } ?: call
        return VoiceCallRecord(
            id = UUID.randomUUID().toString(),
            startedAtMs = nowMs(),
            transcript = latest.transcript.map { entry ->
                entry.delivery?.takeIf { !it.terminal }?.let {
                    entry.copy(delivery = it.copy(state = SpeechDeliveryState.INTERRUPTED), complete = false)
                } ?: entry
            },
            taskStatus = latest.taskStatus,
            conversationId = null
        ).also {
            activeCall = it
            _state.value = VoiceSessionState.ACTIVELY_LISTENING
            checkpoint()
        }
    }

    /** The shared thread already holds the resumed call; start a fresh call segment. */
    @Synchronized fun linkConversation(conversationId: String) {
        captionInputVersion++
        activeCall = requireActiveCall().copy(conversationId = conversationId, transcript = emptyList())
        checkpoint()
    }

    /** An asynchronous model load may finish after Stop or after another call starts. */
    @Synchronized fun setStateIfCurrent(callId: String, state: VoiceSessionState): Boolean {
        if (activeCall?.id != callId) return false
        if (_state.value == state) return true
        _state.value = state
        checkpoint()
        return true
    }

    @Synchronized fun setState(state: VoiceSessionState) {
        requireActiveCall()
        if (_state.value == state) return
        _state.value = state
        checkpoint()
    }

    @Synchronized fun updateTask(status: VoiceTaskStatus) {
        updateTaskForCall(requireActiveCall().id, status)
    }

    /** Accepted worker callbacks may finish after End Call; update only their saved original call. */
    @Synchronized fun updateTaskForCall(callId: String, status: VoiceTaskStatus) {
        val live = activeCall?.id == callId
        val call = (if (live) activeCall else store.list().firstOrNull { it.id == callId }) ?: return
        val updated = call.copy(taskStatus = status)
        if (live) activeCall = updated
        store.save(updated)
    }

    @Synchronized fun interrupt(): VoiceCallRecord {
        val call = requireActiveCall()
        _state.value = VoiceSessionState.INTERRUPTED
        activeCall = call.copy(taskStatus = call.taskStatus?.let {
            if (it.state == VoiceTaskState.COMPLETED || it.state == VoiceTaskState.CANCELLED) it
            else it.copy(state = VoiceTaskState.INTERRUPTED)
        })
        return endInternal()
    }

    @Synchronized fun end(title: String? = null): VoiceCallRecord {
        requireActiveCall()
        return endInternal(title)
    }

    private fun endInternal(title: String? = null): VoiceCallRecord {
        val call = requireActiveCall()
        val ended = call.copy(endedAtMs = nowMs(), title = title ?: deriveTitle(call.transcript))
        store.save(ended)
        activeCall = null
        _state.value = VoiceSessionState.PASSIVE_LISTENING
        return ended
    }

    private fun checkpoint() {
        activeCall?.let(store::save)
    }

    /** Preserve the latest partial at Pause, external handoff, and turn cleanup. */
    @Synchronized fun flushCheckpoint() = checkpoint()

    private fun requireActiveCall(): VoiceCallRecord =
        requireNotNull(activeCall) { "No active Voice Call." }

    /** Keep the history list useful without asking the user to name a call. */
    private fun deriveTitle(entries: List<TranscriptEntry>): String? {
        val words = entries
            .filter { it.role == "You" }
            .flatMap { Regex("[A-Za-z]{4,}").findAll(it.text.lowercase()).map { match -> match.value }.toList() }
            .filterNot { it in setOf("that", "this", "what", "with", "could", "would", "please", "jarvis") }
        if (words.isEmpty()) return null
        return words.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?.replaceFirstChar { it.uppercase() }
    }
}
