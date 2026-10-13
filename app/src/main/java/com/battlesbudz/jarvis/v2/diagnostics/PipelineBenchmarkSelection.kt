package com.battlesbudz.jarvis.v2.diagnostics

/** Scopes export without dropping failed/cancelled attempts. Legacy call-only records stay discoverable. */
object PipelineBenchmarkSelection {
    fun select(samples: List<PipelineBenchmarkTurn>, conversationId: String? = null,
               callId: String? = null, turnId: String? = null): List<PipelineBenchmarkTurn> = samples.filter {
        (conversationId == null || it.conversationId == conversationId || it.provenance.configuration["conversation_id"] == conversationId) &&
            (callId == null || it.callId == callId) &&
            (turnId == null || it.turnId == turnId || it.provenance.configuration["reply_id"] == turnId ||
                it.provenance.configuration["linked_reply_turn_id"] == turnId)
    }
}
