package com.battlesbudz.jarvis.v2.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.assistantRejectedLaunchReceipt
import com.battlesbudz.jarvis.v2.actions.assistantSubmittedLaunchReceipt

/** Android binds this only for the assistant selected by the user in system settings. */
class JarvisInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); active = this }
    override fun onShutdown() { if (active === this) active = null; super.onShutdown() }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    companion object {
        @Volatile private var active: JarvisInteractionService? = null
        fun isSelected(context: Context): Boolean =
            isActiveService(context, ComponentName(context, JarvisInteractionService::class.java))
        fun isReady(context: Context): Boolean = active != null && isSelected(context)
        fun launch(context: Context, intent: Intent, label: String): ExecutionResult? {
            val service = active ?: return null
            if (!isSelected(context)) return null
            return runCatching {
                // User's explicit app command reaches this on the main thread, after validation.
                // The binding cannot observe the destination, so a handed-off
                // request is submitted/unverified — never a verified launch.
                service.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assistantSubmittedLaunchReceipt(label)
            }.getOrElse {
                assistantRejectedLaunchReceipt(label, it.message ?: "Android rejected the launch.")
            }
        }
    }
}
