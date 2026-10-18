package com.battlesbudz.jarvis.v2.voice.smartturn

import android.content.Context
import java.io.File

/** New endpoint preference: old experimental shadow opt-out is not an endpoint opt-out. */
internal object SmartTurnSettings {
    private const val KEY = "smart_turn_endpoint_enabled"
    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
    fun store(context: Context) = SmartTurnModelStore(File(context.filesDir, "voice-models/smart-turn-v3.2"))
}
