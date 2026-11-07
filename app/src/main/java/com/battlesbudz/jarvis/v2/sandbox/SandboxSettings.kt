package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context

/**
 * Sandbox user settings (Phase 1 scope §9, §11). SharedPreferences-backed,
 * mirroring VisionServerSettings.
 *
 * Networking is the load-bearing one (decision D3): default OFF, flippable
 * only by the user in settings — never per-command and never by the model.
 * While off, runs are spawned with the engine's network gate engaged and
 * the guest has no DNS configuration; while on, the guest gets the default
 * resolv.conf and socket mediation is left to Android (the app holds the
 * INTERNET permission either way — the sandbox toggle is the control, not
 * the permission).
 */
internal object SandboxSettings {
    private const val PREFS = "faraday_sandbox"
    private const val KEY_NETWORK_ENABLED = "sandbox_network_enabled"

    fun networkEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NETWORK_ENABLED, false)
    }.getOrDefault(false)

    fun setNetworkEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NETWORK_ENABLED, enabled).apply()
    }
}
