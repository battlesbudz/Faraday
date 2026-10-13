package com.battlesbudz.jarvis.v2.actions

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager

/**
 * M1e live Android capability check (T08).
 *
 * Consulted at admission and immediately before dispatch: a denied or
 * missing Android capability blocks dispatch on every adapter with a
 * truthful receipt. Intent-family tools (open_app/open_website/
 * open_settings/navigate) additionally gate background launches on a real
 * background activity-start exemption (selected assistant or "Display over
 * other apps"); with no exemption the executor reports the launch as blocked
 * instead of claiming an effect it cannot verify.
 */
fun interface ToolCapabilityProbe {
    /** Null when the capability is present; otherwise the honest user-facing reason it is missing. */
    fun missingReason(request: ActionRequest): String?
}

class AndroidToolCapabilityProbe(
    private val context: Context,
    private val screenAvailable: () -> Boolean = { ScreenControlService.bridge(context).isAvailable() }
) : ToolCapabilityProbe {
    override fun missingReason(request: ActionRequest): String? = when (request.name) {
        "read_battery" ->
            if (context.getSystemService(BatteryManager::class.java) == null)
                "Battery status is unavailable on this device." else null
        "set_volume", "media_control" ->
            if (context.getSystemService(AudioManager::class.java) == null)
                "Audio control is unavailable on this device." else null
        "screen_observe" ->
            if (!screenAvailable()) "Screen observation is not available. Enable Jarvis screen control " +
                "in Android Accessibility settings, then try again." else null
        "screen_tap", "screen_scroll", "screen_type" ->
            if (!screenAvailable()) "Screen control is not available. Enable Jarvis screen control " +
                "in Android Accessibility settings, then try again." else null
        else -> null
    }
}

/** Production lock gate backed by the real keyguard state (T09). */
fun androidLockGate(context: Context, privateNotificationAuthorized: () -> Boolean = { false }) = DeviceLockGate(
    isLocked = { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true },
    privateNotificationAuthorized = privateNotificationAuthorized)
