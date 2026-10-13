package com.battlesbudz.jarvis.v2.voice

enum class VoiceSessionState {
    PASSIVE_LISTENING,
    ACTIVELY_LISTENING,
    PROCESSING,
    EXECUTING_ACTION,
    SPEAKING,
    WAITING_FOR_CONFIRMATION,
    INTERRUPTED,
    ENDED
}

enum class VoiceTaskState { COMPLETED, FAILED, CANCELLED, INTERRUPTED, WAITING_FOR_USER }
enum class TranscriptOrigin { SPOKEN, TYPED }

data class VoiceTaskStatus(
    val state: VoiceTaskState,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList()
)

data class VoiceCallRecord(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val title: String? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val taskStatus: VoiceTaskStatus? = null,
    val conversationId: String? = null
)

data class TranscriptEntry(
    val role: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val complete: Boolean = true,
    val latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null,
    val replyId: String? = null,
    val delivery: SpeechDelivery? = null,
    val generationComplete: Boolean = complete,
    val actions: List<VoiceActionOutcome> = emptyList(),
    val origin: TranscriptOrigin = TranscriptOrigin.SPOKEN,
    val metrics: com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics? = null
)

data class VoiceActionOutcome(val name: String, val message: String, val succeeded: Boolean)

/** Legacy completed entries retain unknown delivery precision; legacy partial replies are excluded. */
fun TranscriptEntry.forConversation(): TranscriptEntry? {
    if (role != "Jarvis") return this
    val speech = delivery?.deliveredText ?: if (replyId == null && complete) text else ""
    val note = delivery?.takeIf { it.state != SpeechDeliveryState.COMPLETED }?.let {
        if (it.partialSpanIndex != null) "[Reply interrupted within a speech segment; partial wording is unknown.]"
        else if (speech.isNotBlank()) "[Reply ended before the remaining generated text was spoken.]" else ""
    }.orEmpty()
    val outcomes = actions.joinToString("\n") { "[Tool outcome: ${it.name}; succeeded=${it.succeeded}; ${it.message}]" }
    val context = listOf(speech, note, outcomes).filter { it.isNotBlank() }.joinToString("\n")
    return context.takeIf { it.isNotBlank() }?.let { copy(text = it) }
}
