package com.battlesbudz.jarvis.v2.voice

/** A final stop clause is control, never a new factual/story request. No substring matching. */
object VoiceStopRequest {
    fun matches(text: String): Boolean {
        // Quoted examples must not become controls. Negations remain part of the clause.
        if (text.any { it in "\"“”" }) return false
        val clause = text.trim().trimEnd('.', '!', '?').split(Regex("[.!?;\\n]+")).last().trim()
            .lowercase(java.util.Locale.ROOT).replace('’', '\'')
            .replace(Regex("[,]+"), " ").replace(Regex("\\s+"), " ")
            .removePrefix("hey jarvis ").removePrefix("jarvis ")
            .removePrefix("please ").removeSuffix(" please").removeSuffix(" jarvis").trim()
        return clause in setOf("stop", "stop talking", "stop speaking", "stop that", "stop the story",
            "cancel", "cancel that", "never mind", "nevermind", "that's enough", "wait", "hold on")
    }
}
