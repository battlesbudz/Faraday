package com.battlesbudz.jarvis.v2.voice

import android.content.Context

/** Caption recognition is display-only in direct-audio mode, never request authorization. */
enum class VoiceInputMode(val id: String, val label: String) {
    TRANSCRIBED_TEXT("transcribed_text", "Speech recognition"),
    GEMMA_AUDIO("gemma_audio", "Gemma audio understanding");

    companion object {
        fun fromId(id: String?): VoiceInputMode = entries.firstOrNull { it.id == id } ?: TRANSCRIBED_TEXT
        fun selected(context: Context): VoiceInputMode = fromId(
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getString("input_mode", null))
        fun select(context: Context, mode: VoiceInputMode) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putString("input_mode", mode.id).apply()
        }
        /** A null engine means Off. Existing installs keep their Whisper on/off choice. */
        fun captionEngine(context: Context): AsrEngine? {
            val preferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
            val storedId = preferences.getString("caption_engine", null)
            val resolved = resolveCaptionEngine(storedId,
                preferences.getBoolean("gemma_whisper_captions", true))
            if (storedId == "moonshine_small") {
                // Retire only the selection, preserving existing downloads and history.
                preferences.edit().putString("caption_engine", resolved?.id ?: "off").apply()
            }
            return resolved
        }
        fun captionEngine(context: Context, engine: AsrEngine?) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit()
                .putString("caption_engine", engine?.id ?: "off")
                // Preserve the old setting for callers and app versions that only know on/off.
                .putBoolean("gemma_whisper_captions", engine != null).apply()
        }
        internal fun resolveCaptionEngine(storedId: String?, legacyEnabled: Boolean): AsrEngine? = when {
            // An older APK may have changed only this Boolean after a downgrade.
            !legacyEnabled -> null
            storedId == "off" -> null
            AsrEngine.entries.any { it.id == storedId } -> AsrEngine.entries.first { it.id == storedId }
            legacyEnabled -> AsrEngine.WHISPER
            else -> null
        }
        fun captions(context: Context): Boolean = captionEngine(context) != null
        fun captions(context: Context, enabled: Boolean) {
            captionEngine(context, if (enabled) captionEngine(context) ?: AsrEngine.WHISPER else null)
        }
    }
}
