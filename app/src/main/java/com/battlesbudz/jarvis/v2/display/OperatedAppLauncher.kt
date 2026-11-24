package com.battlesbudz.jarvis.v2.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.battlesbudz.jarvis.v2.actions.TaskLocation
import com.battlesbudz.jarvis.v2.actions.TaskLocationProvider
import com.battlesbudz.jarvis.v2.perception.OperatedTreeAccessibilityService

fun interface OperatedAppLauncher {
    fun launch(packageName: String, identity: OperatedDisplayIdentity): Boolean
}

class AndroidOperatedAppLauncher(private val context: Context) : OperatedAppLauncher {
    override fun launch(packageName: String, identity: OperatedDisplayIdentity): Boolean {
        return try {
            require(identity.displayId > 0)
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(identity.displayId)
            context.startActivity(intent, options.toBundle())
            true
        } catch (_: Exception) { false }
    }
}

/** Accessibility only reports focused-window packages; it cannot detect a vanished task elsewhere. */
class AccessibilityTaskLocationProvider(
    private val foregroundPackages: () -> Map<Int, String> = OperatedTreeAccessibilityService::displayForegroundPackages,
    private val ownDisplayId: () -> Int? = { null },
) : TaskLocationProvider {
    override fun location(packageName: String): TaskLocation {
        val packages = foregroundPackages()
        if (packages[0] == packageName) return TaskLocation.MAIN_DISPLAY
        val match = packages.entries.firstOrNull { it.value == packageName }
        if (match == null) {
            val own = ownDisplayId()
            return if (own != null && own > 0 && packages.containsKey(own)) TaskLocation.DEAD_VIRTUAL else TaskLocation.NONE
        }
        // DEAD_VIRTUAL is knowable only for our previous display, after the task vanished.
        return TaskLocation.LIVE_VIRTUAL
    }
}
