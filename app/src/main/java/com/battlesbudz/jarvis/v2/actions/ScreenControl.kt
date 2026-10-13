package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M1c screen control: compact observation, verified targets, session-scoped
 * grants, user-touch pause with idle resume, and a floating Stop overlay.
 *
 * This file is JVM-pure (no android.* imports) so the whole state machine is
 * unit-testable. The Android half ([ScreenControlService]) implements
 * [ScreenBridge] against the accessibility APIs.
 *
 * Verified-target rule: every screen mutation (tap/scroll/type) must name a
 * target ID and observation token from the latest [ScreenObservation]. A token
 * rotates on every observation, so anything observed before the current screen
 * state is stale and can never dispatch. The approval additionally binds to
 * the observed content generation ([ScreenObservation.contentFingerprint]):
 * dispatch re-reads the live generation and fails closed when the window's
 * content changed since the observation, even if the target kept its
 * resource ID, walk position, role, label and bounds.
 */
data class ScreenNode(
    val id: String,
    val label: String,
    val role: String,
    val bounds: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /**
     * Stable target identity from AccessibilityNodeInfo.viewIdResourceName,
     * null when the platform provides none (common in WebViews). A null view
     * id NEVER binds: dispatch on such a node fails closed, because a null
     * id is not permission to revert to positional (role/label/bounds)
     * identity — a WebView can replace a control with another
     * same-label/same-bounds/no-resource-ID control in the same window.
     */
    val viewId: String? = null,
    /**
     * Identity of the observed active window ("package#windowId"). Blank
     * means unestablished: dispatch on such a node fails closed.
     */
    val windowIdentity: String = "",
    /**
     * Bound by ScreenControlSession.verifyTarget, never by model input. The
     * bridge must compare this with a complete scan of the exact root used
     * for dispatch. Null keeps legacy/unverified direct calls fail-closed.
     * This dispatch metadata is deliberately excluded from content hashing.
     */
    val expectedContentFingerprint: String? = null
)

data class ScreenObservation(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val observedAtMs: Long = System.currentTimeMillis(),
    /**
     * Identity of the observed active window ("package#windowId"). Every
     * dispatch must present this same identity or fail closed: an approval
     * for one window never dispatches in another.
     */
    val windowIdentity: String = "",
    /**
     * Content generation the approval binds to. The production bridge hashes
     * the complete exposed tree, including raw text omitted from compact
     * labels; the default is for complete synthetic node lists in JVM fixtures.
     * Dispatch must present the live generation from
     * the same window — a resource ID is a resource name, not a
     * content-generation identifier, so an approval for record A's "OK"
     * control can never dispatch after the window's content was replaced
     * with record B, even when the control keeps its resource ID, walk
     * position, role, label and bounds.
     */
    val contentFingerprint: String = contentFingerprintOf(nodes),
    /** Partial accessibility trees are readable, but never authorize mutations. */
    val isComplete: Boolean = true
) {
    /** Compact, model-readable snapshot. Bounded so receipts stay small. */
    fun compactText(token: String, maxNodes: Int = 64): String = buildString {
        appendLine("Screen: $packageName (${nodes.size} elements)")
        if (!isComplete) appendLine("Incomplete screen snapshot: mutations are unavailable.")
        nodes.take(maxNodes).forEach { node ->
            appendLine("[${node.id}] ${node.role} \"${node.label.take(60)}\" (${node.bounds})")
        }
        if (nodes.size > maxNodes) appendLine("... ${nodes.size - maxNodes} more elements omitted")
        append("observation token: $token")
    }
}

/**
 * Generation for complete synthetic ScreenNode lists (JVM/release fixtures).
 * Dispatch-only expectedContentFingerprint is deliberately excluded. Production
 * accessibility trees use their full raw-tree hash in ScreenControlService;
 * hashing compact/truncated model-facing nodes cannot prove completeness.
 */
fun contentFingerprintOf(nodes: List<ScreenNode>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    fun feed(text: String) {
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    for (node in nodes) {
        feed(node.id)
        feed(node.role)
        feed(node.label)
        feed(node.bounds)
        feed(node.viewId ?: "")
        feed(node.windowIdentity)
        feed(if (node.clickable) "1" else "0")
        feed(if (node.editable) "1" else "0")
        feed(if (node.scrollable) "1" else "0")
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

/** Android-facing screen operations. JVM tests inject a fake; production uses the accessibility service. */
interface ScreenBridge {
    fun isAvailable(): Boolean
    fun observe(): ScreenObservation?

    /**
     * Live identity of the active window at dispatch time ("package#windowId").
     * Null when it cannot be established; dispatch fails closed in that case.
     */
    fun currentWindowIdentity(): String?

    /**
     * Content generation of the live active window at dispatch time: the
     * fingerprint of the exact node content currently on screen. Null when
     * it cannot be established; dispatch fails closed in that case, because
     * an approval binds to the observed generation and a dispatch that
     * cannot prove the content is unchanged must not proceed.
     */
    fun currentContentFingerprint(): String?
    /** Mutations must fail closed unless the node carries a verified generation. */
    fun tap(node: ScreenNode): Boolean
    fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean
    fun type(node: ScreenNode, text: String): Boolean
    fun showStopOverlay(taskLabel: String): Boolean
    fun hideStopOverlay()
}

/** Outcome of asking the session for a screen-control grant (D23/D26). */
sealed interface AdmitResult {
    data object Admitted : AdmitResult
    data object AlreadyAdmitted : AdmitResult
    data object NeedsApproval : AdmitResult
    data class Denied(val reason: String) : AdmitResult
}

/** Dispatch-time gate for screen mutations (T06). */
sealed interface DispatchGate {
    data object Allowed : DispatchGate
    data object NeedsAdmission : DispatchGate
    data object Paused : DispatchGate
    /** The user stopped touching the screen; the caller must re-observe before acting. No countdown. */
    data object ResumeReobserve : DispatchGate
    data object Stopped : DispatchGate
}

sealed interface TargetVerification {
    data class Verified(val node: ScreenNode) : TargetVerification
    data class Rejected(val reason: String) : TargetVerification
}

/**
 * Binds a verified snapshot node to the live node at dispatch time.
 *
 * The walk index ("n7") is positional: a tree that shifted between observation
 * and dispatch can resolve the same index to a different node. Role, label
 * AND bounds must all agree, or the dispatch fails closed and the caller
 * re-observes. A scroll or re-layout changes bounds, which is the safe
 * direction — a wrong tap is never taken.
 *
 * Safe-identity policy (finding 3, same-window hardening): the snapshot's
 * view id (AccessibilityNodeInfo viewIdResourceName) must be non-null and
 * equal to the live node's. A null view id fails closed — it is never
 * permission to revert to positional identity, because a WebView can replace
 * an old "OK" control with another same-label/same-bounds/no-resource-ID
 * control in the same window, preserving the walk index, and the old token,
 * window identity and positional matcher would all still pass. Targets
 * without a stable identity cannot be tapped, scrolled or typed until a
 * stable identity can be established.
 */
fun ScreenNode.matchesLiveNode(
    role: String,
    label: String,
    bounds: String,
    liveViewId: String? = null
): Boolean =
    this.role == role && this.label == label && this.bounds == bounds &&
        viewId != null && viewId == liveViewId

/**
 * Whether a window-state change event invalidates the observation.
 *
 * Window-state events carry their own window's package name. Jarvis's own
 * stop-overlay and approval windows share the app package and never become
 * the active window, so they must neither invalidate the observation nor
 * inherit its approvals — only a different package invalidates. A null
 * event package means the window cannot be identified, which fails closed
 * like any unestablished identity.
 *
 * Content-change events deliberately do not invalidate: they are too noisy,
 * and dispatch-time re-verification already covers them.
 */
fun windowChangeInvalidatesObservation(eventPackageName: String?, ownPackageName: String): Boolean =
    eventPackageName != ownPackageName

/**
 * Session-scoped screen-control grant. One grant per task group: admitting a
 * second group while one holds the lease is denied, and a later group never
 * silently inherits an expired session (D26). Manual touch pauses dispatch;
 * after [touchIdleMs] of no touch the next mutation must re-observe the screen
 * first, without any countdown (T06, D25).
 */
class ScreenControlSession(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val touchIdleMs: Long = 3000L
) {
    private var groupId: String? = null
    private var observation: ScreenObservation? = null
    private var token: String? = null
    private var touchActive = false
    private var pausedByTouch = false
    private var lastTouchMs = 0L
    private var stopRequested = false

    val isAdmitted: Boolean get() = groupId != null
    val currentToken: String? get() = token
    val isStopRequested: Boolean get() = stopRequested

    /**
     * M1d: which task group holds the lease, if any. Lets the scheduling layer
     * queue conflicting follow-ups behind the holder (T02) and release the
     * lease exactly when the holding group finishes (T04, D26).
     */
    val holderGroupId: String? get() = groupId

    fun admit(groupId: String, userApproved: Boolean): AdmitResult {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        val current = this.groupId
        if (current != null) {
            return if (current == groupId) AdmitResult.AlreadyAdmitted
            else AdmitResult.Denied("Screen control is already in use by another task.")
        }
        if (!userApproved) return AdmitResult.NeedsApproval
        this.groupId = groupId
        stopRequested = false
        return AdmitResult.Admitted
    }

    /** Records a fresh observation and rotates the token, invalidating all older targets. */
    fun recordObservation(observation: ScreenObservation): String {
        val newToken = UUID.randomUUID().toString().replace("-", "").take(16)
        this.observation = observation
        this.token = newToken
        return newToken
    }

    /**
     * Drops the current observation and token without touching the grant:
     * the group keeps the lease, touch state stays as-is, and the stop flag
     * stays as-is. The next mutation must re-observe before dispatching.
     */
    fun invalidateObservation() {
        observation = null
        token = null
    }

    /**
     * Verifies a mutation target against the latest observation. [requireNode]
     * returns a rejection reason when the node cannot take this action
     * (not clickable/editable/scrollable), or null when it can.
     *
     * [liveWindowIdentity] is the bridge's read of the active window at
     * dispatch time. When it is null, blank, or a different window than the
     * observation's, verification fails closed: an approval for app A's
     * window must never dispatch in app B's window.
     *
     * [liveContentFingerprint] is the bridge's read of the live window's
     * content generation at dispatch time. It must equal the observed
     * generation: an approval binds to the exact content it was granted
     * against, so a window whose content was replaced since the observation
     * — even when the target keeps its resource ID, walk position, role,
     * label and bounds — fails closed and requires a fresh observation. A
     * null live generation fails closed like any unestablished identity.
     */
    fun verifyTarget(
        targetId: String,
        token: String,
        liveWindowIdentity: String?,
        liveContentFingerprint: String?,
        requireNode: (ScreenNode) -> String?
    ): TargetVerification {
        val observed = observation
        if (observed == null || token != this.token) {
            return TargetVerification.Rejected(
                "That screen observation is stale — the screen changed. " +
                    "Call screen_observe again for fresh targets."
            )
        }
        if (observed.windowIdentity.isBlank() || liveWindowIdentity == null ||
            liveWindowIdentity != observed.windowIdentity
        ) {
            return TargetVerification.Rejected(
                "That screen observation is from a different window — the screen " +
                    "changed or another window is now active. " +
                    "Call screen_observe again for fresh targets."
            )
        }
        if (!observed.isComplete || observed.contentFingerprint.isBlank()) {
            return TargetVerification.Rejected(
                "This screen could not be read completely, so I cannot safely control it. " +
                    "Open a simpler screen and call screen_observe again."
            )
        }
        if (liveContentFingerprint == null || liveContentFingerprint != observed.contentFingerprint) {
            return TargetVerification.Rejected(
                "That screen observation is stale — the screen content changed " +
                    "since it was observed. Call screen_observe again for fresh targets."
            )
        }
        val node = observed.nodes.firstOrNull { it.id == targetId }
            ?: return TargetVerification.Rejected(
                "Target $targetId is not on the current screen. " +
                    "Call screen_observe again for fresh targets."
            )
        requireNode(node)?.let { return TargetVerification.Rejected(it) }
        return TargetVerification.Verified(
            node.copy(expectedContentFingerprint = observed.contentFingerprint)
        )
    }

    fun noteTouchStart() {
        touchActive = true
    }

    fun noteTouchEnd() {
        if (touchActive) {
            touchActive = false
            pausedByTouch = true
            lastTouchMs = clock()
        }
    }

    fun dispatchGate(): DispatchGate {
        if (stopRequested) return DispatchGate.Stopped
        if (groupId == null) return DispatchGate.NeedsAdmission
        if (touchActive) return DispatchGate.Paused
        if (pausedByTouch) {
            if (clock() - lastTouchMs >= touchIdleMs) {
                pausedByTouch = false
                return DispatchGate.ResumeReobserve
            }
            return DispatchGate.Paused
        }
        return DispatchGate.Allowed
    }

    fun requestStop() {
        stopRequested = true
    }

    /** Releases the lease; a later group must admit again (D26). */
    fun release() {
        groupId = null
        observation = null
        token = null
        touchActive = false
        pausedByTouch = false
        stopRequested = false
    }

    /**
     * M1d: releases the lease only when [groupId] is the current holder.
     * A finished task group releases control (T04); a group that never held
     * the lease — or lost it — cannot release another group's grant.
     */
    fun releaseIf(groupId: String): Boolean {
        if (this.groupId != groupId) return false
        release()
        return true
    }
}
