package com.battlesbudz.jarvis.v2.perception

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import com.battlesbudz.jarvis.v2.actions.LiveOperatedNode
import com.battlesbudz.jarvis.v2.actions.OperatedNodeBounds
import com.battlesbudz.jarvis.v2.display.OperatedDisplayIdentity

/** Read-only accessibility route. It never obtains a default-display root. */
class OperatedTreeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** API 33 supplies display-keyed windows; earlier APIs fail closed. */
    fun observe(identity: OperatedDisplayIdentity, readAtMs: Long = System.currentTimeMillis()): OperatedObservation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val byDisplay = windowsOnAllDisplays
        val windows = byDisplay[identity.displayId].orEmpty()
        val selected = windows.firstOrNull {
            belongsToOperatedDisplay(identity, it.displayId) && (it.isFocused || it.isActive)
        } ?: return null
        val root = try { selected.root } catch (_: RuntimeException) { null } ?: return null
        return try {
            val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val tree = readTree(root, identity.displayId, packageName) ?: return null
            createObservation(identity, tree, readAtMs)
        } finally {
            root.recycle()
        }
    }

    /** Live bounds paired with the same focused, display-bound tree contract as observe(). */
    fun liveNodes(identity: OperatedDisplayIdentity): List<LiveOperatedNode> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        val selected = windowsOnAllDisplays[identity.displayId].orEmpty().firstOrNull {
            belongsToOperatedDisplay(identity, it.displayId) && (it.isFocused || it.isActive)
        } ?: return emptyList()
        val root = try { selected.root } catch (_: RuntimeException) { null } ?: return emptyList()
        return try {
            val result = mutableListOf<LiveOperatedNode>()
            var visited = 0
            var complete = true
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (!complete) return
                if (visited >= MAX_NODES || depth > MAX_DEPTH) { complete = false; return }
                visited++
                val className = node.className?.toString().orEmpty()
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf(String::isNotEmpty)
                val flags = buildSet {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isEnabled) add("enabled")
                    if (node.isFocusable) add("focusable")
                    if (node.isFocused) add("focused")
                    if (node.isCheckable) add("checkable")
                    if (node.isChecked) add("checked")
                    if (node.isSelected) add("selected")
                    if (node.isScrollable) add("scrollable")
                    if (node.isPassword) add("password")
                }
                val isProgress = className.contains("ProgressBar", true) || className.contains("SeekBar", true) ||
                    className.contains("Slider", true)
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if ((text != null || node.viewIdResourceName != null || flags.isNotEmpty() || isProgress) &&
                    bounds.width() > 0 && bounds.height() > 0)
                    result += LiveOperatedNode(className, node.viewIdResourceName, text, flags,
                        OperatedNodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                for (index in 0 until node.childCount) {
                    if (!complete) break
                    if (visited >= MAX_NODES || depth >= MAX_DEPTH) { complete = false; break }
                    val child = try { node.getChild(index) } catch (_: RuntimeException) { null }
                    if (child == null) { complete = false; break }
                    try { walk(child, depth + 1) } finally { child.recycle() }
                }
            }
            walk(root, 0)
            if (complete) result else emptyList()
        } catch (_: RuntimeException) { emptyList() } finally { root.recycle() }
    }

    fun displayForegroundPackages(): Map<Int, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyMap()
        return buildMap {
            val displays = windowsOnAllDisplays
            for (index in 0 until displays.size()) {
                val displayId = displays.keyAt(index)
                val windows = displays.valueAt(index)
                val window = windows.firstOrNull { it.isFocused || it.isActive } ?: continue
                val root = try { window.root } catch (_: RuntimeException) { null } ?: continue
                try { root.packageName?.toString()?.takeIf(String::isNotBlank)?.let { put(displayId, it) } }
                finally { root.recycle() }
            }
        }
    }

    private fun readTree(root: AccessibilityNodeInfo, displayId: Int, packageName: String): TreeWindowCandidate? {
        val nodes = mutableListOf<ObservedNode>()
        var visited = 0
        var complete = true
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (!complete) return
            if (visited >= MAX_NODES || depth > MAX_DEPTH) { complete = false; return }
            visited++
            val className = node.className?.toString().orEmpty()
            val text = (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf(String::isNotEmpty)
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isEnabled) add("enabled")
                if (node.isFocusable) add("focusable")
                if (node.isFocused) add("focused")
                if (node.isCheckable) add("checkable")
                if (node.isChecked) add("checked")
                if (node.isSelected) add("selected")
                if (node.isScrollable) add("scrollable")
                if (node.isPassword) add("password")
            }
            val isProgress = className.contains("ProgressBar", ignoreCase = true) ||
                className.contains("SeekBar", ignoreCase = true) || className.contains("Slider", ignoreCase = true)
            if (text != null || node.viewIdResourceName != null || flags.isNotEmpty() || isProgress) {
                nodes += ObservedNode(className, node.viewIdResourceName, text, flags)
            }
            val count = node.childCount
            for (index in 0 until count) {
                if (!complete) break
                if (visited >= MAX_NODES || depth >= MAX_DEPTH) { complete = false; break }
                val child = try { node.getChild(index) } catch (_: RuntimeException) { null }
                if (child == null) { complete = false; break }
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        return try {
            walk(root, 0)
            if (complete) TreeWindowCandidate(displayId, packageName, nodes) else null
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {
        private const val MAX_NODES = 300
        private const val MAX_DEPTH = 32
        @Volatile private var instance: OperatedTreeAccessibilityService? = null

        /** The caller must provide A2's identity; there is deliberately no display-0 default. */
        fun observeCurrent(identity: OperatedDisplayIdentity, readAtMs: Long = System.currentTimeMillis()): OperatedObservation? =
            instance?.observe(identity, readAtMs)

        fun observeLiveNodes(identity: OperatedDisplayIdentity): List<LiveOperatedNode> =
            instance?.liveNodes(identity).orEmpty()

        fun displayForegroundPackages(): Map<Int, String> = instance?.displayForegroundPackages().orEmpty()
    }
}
