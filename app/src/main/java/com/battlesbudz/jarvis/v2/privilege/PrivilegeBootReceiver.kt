package com.battlesbudz.jarvis.v2.privilege

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot restoration reports the precise blocked state until a secure helper session is re-established. */
class PrivilegeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(STATE_KEY, "blocked: helper session is not active after reboot; wireless-debugging recovery is required")
            .apply()
    }

    companion object {
        private const val PREFERENCES = "app_control_privilege"
        private const val STATE_KEY = "state"
    }
}
