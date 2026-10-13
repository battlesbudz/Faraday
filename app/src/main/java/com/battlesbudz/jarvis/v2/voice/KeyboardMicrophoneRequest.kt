package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Only a microphone control in the selected keyboard can request preflight priority. */
object KeyboardMicrophoneRequest {
    private val labels = setOf("voice input", "voice typing", "dictation", "microphone",
        "start voice input", "start voice typing", "start dictation", "switch to voice input")
    private val ids = setOf("voice_input", "voice_typing", "voice_input_key", "key_voice",
        "voice_key", "microphone", "mic", "mic_button", "voice_input_button",
        "voice_typing_button", "voice_input_btn", "toolbar_voice_input", "key_pos_voice_input")

    fun matches(keyboardPackage: String?, eventPackage: String?, description: String?,
                viewId: String?, editable: Boolean): Boolean {
        if (keyboardPackage.isNullOrBlank() || eventPackage != keyboardPackage || editable) return false
        val label = description?.trim()?.lowercase(Locale.ROOT)
        val id = viewId?.takeIf { it.startsWith("$keyboardPackage:id/") }?.substringAfter(":id/")
        return label in labels || id in ids
    }
}
