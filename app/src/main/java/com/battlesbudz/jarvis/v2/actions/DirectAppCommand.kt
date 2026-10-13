package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard

/** Only explicit requests; questions, quoted discussion and history never authorize this shortcut. */
object DirectAppCommand {
    private val command = Regex(
        """(?i)^(?:hey\s+)?(?:jarvis[,!]?\s+)?(?:(?:please|can you|could you|would you|will you)\s+)*(?:open|launch|start)\s+(?:the\s+)?([a-z0-9][a-z0-9 .&'_-]*?)(?:\s+app)?(?:\s+(?:for me|please|right now))*[.!?]*$"""
    )
    fun parse(text: String): ActionRequest? {
        val app = command.matchEntire(text.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (app.lowercase() in setOf("it", "that", "this")) return null
        val args = mapOf("app" to app)
        if (!FinalVoiceToolGuard.allows(text, "open_app", args)) return null
        return ActionRequest("open_app", args)
    }
}
