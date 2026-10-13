package com.battlesbudz.jarvis.v2.actions

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.graphics.PixelFormat
import android.os.Bundle
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * M1c screen-control accessibility service. Observes the active window as a
 * compact node list and performs tap/scroll/type only on nodes re-verified
 * against a fresh tree walk — a stale target ID can never dispatch.
 *
 * Touch-interaction events feed [ScreenControlSession]; while the user touches
 * the screen, dispatch pauses, and the next mutation re-observes first (T06).
 * The user enables this service in Android Accessibility settings (D09); until
 * then the bridge reports unavailable and every screen tool answers honestly.
 */
class ScreenControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ScreenControlService? = null

        /** Process-wide screen session: one grant per task group (D26). */
        val sharedSession = ScreenControlSession()

        fun bridge(context: android.content.Context): ScreenBridge = ServiceScreenBridge(context)

        internal fun connectedInstance(): ScreenControlService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = ComponentName(context, ScreenControlService::class.java).flattenToString()
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { it == expected }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // Jarvis's own windows (the stop overlay, approval UI) share the app
        // package: handle them explicitly first, so they can neither
        // invalidate the observation nor inherit its approvals, regardless of
        // what the generic rules below do. They still feed touch state like
        // any window, because the user touching our own overlay is still the
        // user touching the screen.
        if (event.packageName?.toString() == packageName) {
            when (type) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> sharedSession.noteTouchStart()
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> sharedSession.noteTouchEnd()
                // Own-UI window and content events are explicitly ignored:
                // the overlay is ours, never a dispatch target.
                else -> Unit
            }
            return
        }
        when (type) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> sharedSession.noteTouchStart()
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> sharedSession.noteTouchEnd()
            // A window from a different package becoming active invalidates
            // the observation: approvals bind to one window identity and never
            // cross windows. A null event package means the window cannot be
            // identified, which fails closed like any unestablished identity.
            // Content-change events deliberately do not invalidate here: they
            // are too noisy, and dispatch-time content-generation verification
            // already covers them.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (windowChangeInvalidatesObservation(event.packageName?.toString(), packageName))
                    sharedSession.invalidateObservation()
            else -> Unit
        }
    }

    override fun onInterrupt() { /* No reservation is tied to this service's lifetime. */ }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/**
 * Bounded, model-facing nodes only. A caller cannot infer that this list is
 * complete; production observation/dispatch use readScreenTree's completeness.
 * Root is owned by the caller. Raw context is hashed, never sent to the model.
 */
fun extractScreenNodes(root: AccessibilityNodeInfo, maxNodes: Int = 200): List<ScreenNode> =
    readScreenTree(root, maxNodes = maxNodes).nodes

/** Missing package/window metadata is unestablished, never a synthetic identity. */
private fun windowIdentityOf(root: AccessibilityNodeInfo): String {
    val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return ""
    val windowId = root.windowId.takeIf { it >= 0 } ?: return ""
    return "$packageName#$windowId"
}

private data class ScreenTreeSnapshot(
    val nodes: List<ScreenNode>,
    val contentFingerprint: String?,
    // The very node retained during the fingerprint scan, never a second lookup.
    val target: AccessibilityNodeInfo? = null
)

/**
 * Complete-safe generation of the exposed accessibility tree. Every node and
 * its full raw text/description/state participates, including non-actionable
 * containers. Prompt labels remain bounded independently. At most 200 total
 * nodes, depth 25 and 65,536 UTF-16 text units are examined. Overflow, missing
 * advertised children or a failed read yields no generation: partial trees
 * may be observed, but every mutation fails closed. No truncated prefix can
 * stand for unseen content. Accessibility does not offer an atomic transaction
 * with another app; this closes our separate-root/re-walk race, not platform
 * changes after Android accepts the action.
 */
private fun readScreenTree(
    root: AccessibilityNodeInfo,
    targetId: String? = null,
    maxNodes: Int = 200
): ScreenTreeSnapshot {
    val nodes = mutableListOf<ScreenNode>()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val windowIdentity = windowIdentityOf(root)
    var complete = windowIdentity.isNotBlank()
    var visited = 0
    var textUnits = 0
    var target: AccessibilityNodeInfo? = null
    fun feedInt(value: Int) {
        for (shift in 24 downTo 0 step 8) digest.update((value ushr shift).toByte())
    }
    fun feedText(value: CharSequence?) {
        if (value == null) { feedInt(-1); return }
        if (value.length > 65_536 - textUnits) { complete = false; return }
        textUnits += value.length
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        feedInt(bytes.size)
        digest.update(bytes)
    }
    fun walk(node: AccessibilityNodeInfo, depth: Int) {
        if (!complete) return
        if (visited >= maxNodes || depth > 25) { complete = false; return }
        visited++
        feedInt(depth)
        val childCount = node.childCount
        feedInt(childCount)
        feedInt(node.windowId)
        feedText(node.packageName)
        feedText(node.className)
        feedText(node.viewIdResourceName)
        feedText(node.text)
        feedText(node.contentDescription)
        feedText(node.stateDescription)
        feedText(node.hintText)
        feedText(node.error)
        feedText(node.paneTitle)
        feedText(node.tooltipText)
        feedText(screenNodeBounds(node))
        for (flag in booleanArrayOf(node.isClickable, node.isEditable, node.isScrollable,
            node.isEnabled, node.isCheckable, node.isChecked, node.isSelected,
            node.isPassword, node.isVisibleToUser, node.isFocusable)) {
            feedInt(if (flag) 1 else 0)
        }
        if (!complete) return
        if (isScreenActionable(node)) {
            val id = "n${nodes.size}"
            nodes += toScreenNode(node, id, windowIdentity)
            if (id == targetId) target = node
        }
        for (i in 0 until childCount) {
            if (!complete) break
            // Enforce the bound before asking Android for one more node.
            if (visited >= maxNodes || depth >= 25) { complete = false; break }
            val child = node.getChild(i)
            if (child == null) { complete = false; break }
            try {
                walk(child, depth + 1)
            } finally {
                if (child !== target) child.recycle()
            }
        }
    }
    try {
        walk(root, 0)
    } catch (_: RuntimeException) {
        // A stale/replaced node or failed accessibility read is not evidence
        // that the tree is unchanged. Retained target is released by caller.
        complete = false
    }
    return ScreenTreeSnapshot(
        nodes,
        if (complete) digest.digest().joinToString("") { "%02x".format(it) } else null,
        target
    )
}

private fun isScreenActionable(node: AccessibilityNodeInfo): Boolean =
    node.isClickable || node.isEditable || node.isScrollable ||
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

private fun screenNodeLabel(node: AccessibilityNodeInfo): String =
    (node.text?.toString().takeIf { !it.isNullOrBlank() }
        ?: node.contentDescription?.toString().orEmpty()).trim().take(80)

private fun screenNodeRole(node: AccessibilityNodeInfo): String = when {
    node.isEditable -> "field"
    node.isScrollable -> "list"
    node.isClickable -> "button"
    !node.text.isNullOrBlank() -> "text"
    else -> "image"
}

private fun toScreenNode(node: AccessibilityNodeInfo, id: String, windowIdentity: String): ScreenNode {
    return ScreenNode(
        id = id,
        label = screenNodeLabel(node),
        role = screenNodeRole(node),
        bounds = screenNodeBounds(node),
        clickable = node.isClickable,
        editable = node.isEditable,
        scrollable = node.isScrollable,
        viewId = node.viewIdResourceName,
        windowIdentity = windowIdentity
    )
}

private fun screenNodeBounds(node: AccessibilityNodeInfo): String {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return "${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}"
}

private fun matchesSnapshot(live: AccessibilityNodeInfo, snapshot: ScreenNode): Boolean =
    snapshot.matchesLiveNode(
        screenNodeRole(live),
        screenNodeLabel(live),
        screenNodeBounds(live),
        live.viewIdResourceName
    )

internal class ServiceScreenBridge(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
    // Injectable acquisition seam exercises the production scan and mutation
    // path with deterministic root swaps. Every returned root is owned here.
    private val activeRoot: () -> AccessibilityNodeInfo? = {
        ScreenControlService.connectedInstance()?.rootInActiveWindow
    },
    private val available: () -> Boolean = { ScreenControlService.connectedInstance() != null }
) : ScreenBridge {
    private fun service(): ScreenControlService? = ScreenControlService.connectedInstance()

    override fun isAvailable(): Boolean = available()

    override fun observe(): ScreenObservation? {
        val root = activeRoot() ?: return null
        return try {
            val tree = readScreenTree(root)
            ScreenObservation(
                packageName = root.packageName?.toString().orEmpty(),
                nodes = tree.nodes,
                windowIdentity = windowIdentityOf(root),
                contentFingerprint = tree.contentFingerprint.orEmpty(),
                isComplete = tree.contentFingerprint != null
            )
        } finally {
            root.recycle()
        }
    }

    /** Live identity of the active window; null when it cannot be established. */
    override fun currentWindowIdentity(): String? {
        val root = activeRoot() ?: return null
        return try {
            windowIdentityOf(root).takeIf { it.isNotBlank() }
        } finally {
            root.recycle()
        }
    }

    /**
     * Content generation of the live active window: the fingerprint of the
     * exact node content currently on screen. Null when the service or the
     * active window is unavailable — dispatch fails closed in that case.
     */
    override fun currentContentFingerprint(): String? {
        val root = activeRoot() ?: return null
        return try {
            readScreenTree(root).contentFingerprint
        } finally {
            root.recycle()
        }
    }

    override fun tap(node: ScreenNode): Boolean = withLiveNode(node) { target ->
        if (!target.isClickable) return@withLiveNode false
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean =
        withLiveNode(node) { target ->
            if (!target.isScrollable) return@withLiveNode false
            val action = when (direction) {
                ScreenScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScreenScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            target.performAction(action)
        }

    override fun type(node: ScreenNode, text: String): Boolean = withLiveNode(node) { target ->
        if (!target.isEditable) return@withLiveNode false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Final generation fence: the expected generation comes from the session,
     * and the target comes from the very same root/scan that proves it. Nothing
     * (including focus) is mutated before this check. Legacy direct calls
     * without a generation fail closed rather than relying on a prior read.
     */
    private inline fun withLiveNode(node: ScreenNode, perform: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val expected = node.expectedContentFingerprint?.takeIf { it.isNotBlank() } ?: return false
        val root = activeRoot() ?: return false
        var target: AccessibilityNodeInfo? = null
        try {
            val liveIdentity = windowIdentityOf(root)
            if (node.windowIdentity.isBlank() || node.windowIdentity != liveIdentity) return false
            val tree = readScreenTree(root, targetId = node.id)
            target = tree.target
            if (tree.contentFingerprint == null || expected != tree.contentFingerprint) return false
            val verifiedTarget = target ?: return false
            if (!matchesSnapshot(verifiedTarget, node)) return false
            return perform(verifiedTarget)
        } finally {
            if (target != null && target !== root) target.recycle()
            root.recycle()
        }
    }

    override fun showStopOverlay(taskLabel: String): Boolean {
        val service = service() ?: return false
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(service)) return false
        return try {
            val windowManager = service.getSystemService(WindowManager::class.java) ?: return false
            val button = Button(service).apply {
                text = "Stop: ${taskLabel.take(24)}"
                setOnClickListener {
                    ScreenControlService.sharedSession.requestStop()
                    hideStopOverlay()
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                y = 320
            }
            windowManager.addView(button, params)
            overlayView = button to windowManager
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun hideStopOverlay() {
        val (view, windowManager) = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) { /* Already gone; the session state is what matters. */ }
    }

    companion object {
        @Volatile private var overlayView: Pair<android.view.View, WindowManager>? = null
    }
}
