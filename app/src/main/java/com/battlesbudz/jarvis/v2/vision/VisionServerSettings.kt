package com.battlesbudz.jarvis.v2.vision

import android.content.Context

/**
 * Whether chat-photo vision goes to the Phone Inference app's localhost
 * server instead of the built-in in-process models. Default off: built-in
 * vision is the behavior unless the user turns the server on.
 *
 * SharedPreferences-backed, mirroring SmartTurnSettings. The token is the
 * X-Vision-Token shown by the Phone Inference app when its server is
 * started; it is stored verbatim and sent only in the X-Vision-Token
 * header to loopback, never logged, never put in a URL.
 */
internal object VisionServerSettings {
    private const val PREFS = "vision_server"
    private const val KEY_ENABLED = "vision_server_enabled"
    private const val KEY_TOKEN = "vision_server_token"

    data class Config(val enabled: Boolean, val token: String)

    fun snapshot(context: Context): Config = Config(enabled(context), token(context))

    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun token(context: Context): String = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""
    }.getOrDefault("")

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()
    }
}
