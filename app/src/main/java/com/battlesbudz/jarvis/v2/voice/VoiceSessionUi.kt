package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class VoicePhase(val label: String) {
    IDLE("Ready"), PREPARING("Preparing"), WAKE("Hey Jarvis"), WAKING("Waking up"),
    LISTENING("Listening"), THINKING("Thinking"), SPEAKING("Speaking"), PAUSED("Mic paused")
}
enum class VoiceControl { STOP_REPLY, END_CONVERSATION, PAUSE, RESUME }
class VoiceControlCancellation(val control: VoiceControl) : kotlinx.coroutines.CancellationException("Voice control: $control")

/** A failed call remains explainable after its microphone/service and overlay have stopped. */
internal data class VoiceCallFailure(
    val conversationId: String,
    val message: String,
    val id: String = java.util.UUID.randomUUID().toString()
)

/** Runtime feedback survives navigation away from the voice screen. */
object VoiceSessionUi {
    val phase = MutableStateFlow(VoicePhase.IDLE)
    val status = MutableStateFlow("")
    val liveTranscript = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val armed = MutableStateFlow(false)
    /**
     * True from the first arm() until the voice session is truly stopped.
     * Unlike [armed], this survives the farewell -> "Waiting for Hey Jarvis"
     * phase, where the armed flag can drop while the session (wake listener,
     * microphone, foreground service) is fully alive. The End-call button
     * gates on this so it stays visible for the whole session lifetime.
     */
    val sessionAlive = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    internal val failure = MutableStateFlow<VoiceCallFailure?>(null)
    internal fun reportFailure(conversationId: String, message: String) {
        failure.value = VoiceCallFailure(conversationId, message)
        report(message)
    }
    internal fun dismissFailure(expected: VoiceCallFailure) {
        failure.compareAndSet(expected, null)
    }
    /** Retrying another conversation must not erase an unseen failure in this one. */
    internal fun clearFailure(conversationId: String) {
        failure.update { it?.takeUnless { error -> error.conversationId == conversationId } }
    }
    internal val liveReplyMetrics = MutableStateFlow<LiveReplyMetrics?>(null)
    internal fun beginLiveMetrics(metrics: LiveReplyMetrics) { liveReplyMetrics.value = metrics }
    fun beginLiveMetrics(turnId: String, conversationId: String, submittedAtMs: Long? = null, firstTokenAtMs: Long? = null) {
        liveReplyMetrics.value = LiveReplyMetrics(turnId, conversationId, submittedAtMs, firstReplyTextAtMs = firstTokenAtMs)
    }
    fun updateLiveTokenRate(turnId: String, conversationId: String, tokensPerSecond: Double) {
        updateLiveMetrics(turnId, conversationId) { it.copy(estimatedTokensPerSecond = tokensPerSecond) }
    }
    internal fun updateLiveMetrics(turnId: String, conversationId: String, transform: (LiveReplyMetrics) -> LiveReplyMetrics) {
        liveReplyMetrics.update { current ->
            if (current?.turnId == turnId && current.conversationId == conversationId) transform(current) else current
        }
    }
    val controls = kotlinx.coroutines.channels.Channel<VoiceControl>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun report(message: String) {
        status.value = message
        phase.value = when {
            message.startsWith("Jarvis session stopped") || message.contains("turn failed", true) -> VoicePhase.IDLE
            message.startsWith("Paused") -> VoicePhase.PAUSED
            message.startsWith("Waiting") -> VoicePhase.WAKE
            message.startsWith("Hey Jarvis detected") -> VoicePhase.WAKING
            message.startsWith("Voice Call is listening") -> VoicePhase.LISTENING
            message.contains("speaking", true) -> VoicePhase.SPEAKING
            message.startsWith("Processing") -> VoicePhase.THINKING
            else -> VoicePhase.PREPARING
        }
        if (phase.value == VoicePhase.IDLE) liveTranscript.value = ""
        if (phase.value != VoicePhase.LISTENING && phase.value != VoicePhase.WAKE) level.value = 0f
    }
}
