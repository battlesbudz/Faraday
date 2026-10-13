package com.battlesbudz.jarvis.v2.actions

/** Runtime-owned executor status; dialogue and memory never create this authority. */
data class PhoneActionStatus(val conversationId: String, val request: String, val message: String)

object PhoneActionStatusReply {
    private fun normalize(text: String) = text.trim().lowercase().trimEnd('.', '!', '?')
    private val followups = setOf("well", "did you do it", "did it work", "is it done", "are you done",
        "did you finish", "did you complete it", "did you complete the sequence", "what happened")

    /** Short status follow-ups use receipts before any model token can invent completion. */
    fun resolve(prompt: String, conversationId: String, history: List<Pair<String, String>>,
                status: PhoneActionStatus?): String? {
        if (status == null || status.conversationId != conversationId || normalize(prompt) !in followups) return null
        val users = history.filter { it.first == "You" }.map { it.second }
        // Some call surfaces have already appended the current user before invoking us.
        val priorUsers = if (users.lastOrNull()?.let(::normalize) == normalize(prompt)) users.dropLast(1) else users
        val lastUser = priorUsers.lastOrNull() ?: return null
        if (normalize(lastUser) != normalize(status.request) && normalize(lastUser) !in followups) return null
        return status.message.takeIf { it.isNotBlank() }
    }
}
