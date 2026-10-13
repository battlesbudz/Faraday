package com.battlesbudz.jarvis.v2.voice

/**
 * Bridges one audio turn to the local Jarvis model without owning the model
 * runtime itself. The callback is expected to emit model output chunks in
 * order. Chunks update session memory immediately; the store may coalesce partial
 * checkpoints, while completed replies are saved at the boundary.
 */
class VoiceTurnCoordinator(
    private val session: VoiceSessionController
) {
    suspend fun <T> processTurn(
        transcript: String?,
        replyId: String? = null,
        /** Serializes every persisted publication with the owning delivery fence. */
        publish: ((() -> Unit) -> Boolean) = { block -> block(); true },
        generate: suspend (onToken: (String) -> Unit) -> T
    ): T {
        if (session.state.value == VoiceSessionState.PASSIVE_LISTENING) {
            session.beginCall()
        }
        val callId = session.currentCallId()
        transcript?.takeIf { it.isNotBlank() }?.let {
            session.appendTranscript("You", it)
        }
        if (replyId != null && callId != null) session.beginReply(callId, replyId)
        session.setState(VoiceSessionState.PROCESSING)
        val response = StringBuilder()
        return try {
            val result = generate { token ->
                if (session.currentCallId() != callId) throw kotlinx.coroutines.CancellationException("The voice call ended.")
                if (!publish {
                        response.append(token)
                        session.setState(VoiceSessionState.SPEAKING)
                        if (replyId != null && callId != null) session.updateReplyText(callId, replyId, response.toString())
                        else session.appendTranscript("Jarvis", response.toString(), complete = false)
                    }) throw kotlinx.coroutines.CancellationException("Voice publication revoked.")
            }
            if (session.currentCallId() != callId) throw kotlinx.coroutines.CancellationException("The voice call ended.")
            if (response.isNotBlank()) {
                if (replyId == null) session.appendTranscript("Jarvis", response.toString(), complete = true)
            }
            result
        } catch (error: Throwable) {
            if (session.currentCallId() == callId) {
                if (response.isNotBlank()) {
                    publish {
                        if (replyId != null && callId != null) session.updateReplyText(callId, replyId, response.toString())
                        else session.appendTranscript("Jarvis", response.toString(), complete = false)
                    }
                }
                if (error is VoiceControlCancellation && error.control == VoiceControl.STOP_REPLY) {
                    session.setState(VoiceSessionState.ACTIVELY_LISTENING)
                } else if (replyId == null) session.interrupt()
            }
            throw error
        }
    }

    fun cancelActiveTurn() {
        if (session.state.value != VoiceSessionState.PASSIVE_LISTENING &&
            session.state.value != VoiceSessionState.ENDED
        ) {
            session.interrupt()
        }
    }
}
