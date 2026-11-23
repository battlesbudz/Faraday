package com.battlesbudz.jarvis.v2.privilege

/** Small pure policy surface shared by the shell backend and deterministic JVM coverage. */
internal object ShellPrivilegePolicy {
    val allowedPermissionNames = setOf("display", "accessibility")
    const val accessibilityComponent = "com.battlesbudz.jarvis.v2/.actions.ScreenControlService"
    fun ownsDisplay(displayId: Int, ownedDisplayIds: Set<Int>) = displayId >= 0 && displayId in ownedDisplayIds
    fun mayUseClipboard(singleTaskLockHeld: Boolean) = singleTaskLockHeld
    fun acceptsPermission(name: String) = name in allowedPermissionNames
}
