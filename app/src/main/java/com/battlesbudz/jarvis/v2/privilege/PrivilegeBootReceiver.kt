package com.battlesbudz.jarvis.v2.privilege

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Boot restoration reports the precise blocked state until a secure helper session is re-established. */
class PrivilegeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val reason = if (intent.action == Intent.ACTION_BOOT_COMPLETED)
            "helper session is not active after reboot; wireless-debugging recovery is required"
        else "helper session must be reattached after app replacement"
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(STATE_KEY, "blocked: $reason").apply()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESTORE_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PrivilegeHealthWorker>().build())
    }

    companion object {
        private const val PREFERENCES = "app_control_privilege"
        private const val STATE_KEY = "state"
        private const val RESTORE_WORK = "privilege-helper-restore"
    }
}
