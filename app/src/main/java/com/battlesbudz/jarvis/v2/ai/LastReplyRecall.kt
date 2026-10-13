package com.battlesbudz.jarvis.v2.ai

/** Exact repeat requests use the visible answer, including authoritative tool results. */
internal object LastReplyRecall {
    fun resolve(prompt: String, history: List<Pair<String, String>>): String? {
        val text = prompt.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        val request = text.removePrefix("sorry ").removeSuffix(" please")
        val matches = request in setOf("what did you say", "what did you just say", "repeat that", "say that again",
            "repeat your last reply", "repeat your last answer", "can you repeat that",
            "could you repeat that", "can you say that again", "can you say that one more time")
        if (!matches) return null
        return history.lastOrNull { (role, content) ->
            (role.equals("Jarvis", true) || role.equals("assistant", true)) && content.isNotBlank()
        }?.second
    }
}
