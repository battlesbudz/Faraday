package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy

/** Immutable app policy only. Never put transcripts, history, memory or tool results here. */
internal object GemmaVoicePreface {
    const val POLICY = "gemma4_fixed_voice_system_v1_experimental"
    val instructions: String get() = VoiceResponsePolicy.instructions
    fun userText(prompt: String): String? {
        val prefix = instructions + "\n\n"
        if (!prompt.startsWith(prefix)) return null
        return prompt.substring(prefix.length).takeIf { it.isNotBlank() }
    }
}
