package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M1c screen control slice: screen_observe, screen_tap, screen_scroll, screen_type.
 * Covers catalog/schema parity, strict decode, validator, tolerant decode,
 * text-parser routing, and the session state machine (grant, verified targets,
 * touch pause/idle resume, stop, release). FinalVoiceToolGuard stays untouched:
 * screen tools remain voice-denied by design.
 */
class M1cScreenControlTest {
    private val validator = MobileActionValidator()

    private fun strict(name: String, args: Map<String, String>) =
        MobileToolCatalog.decodeStrict(name, JSONObject(args as Map<*, *>))

    private val liveIdentity = "com.example.app#1"

    private fun observation(vararg nodes: ScreenNode) =
        ScreenObservation(packageName = "com.example.app", nodes = nodes.toList(), windowIdentity = liveIdentity)

    private fun button(id: String = "n0", label: String = "Search") =
        ScreenNode(id, label, "button", "10,20-100,80", clickable = true)

    private fun field(id: String = "n1", label: String = "Name") =
        ScreenNode(id, label, "field", "10,100-400,160", editable = true)

    private fun list(id: String = "n2", label: String = "Results") =
        ScreenNode(id, label, "list", "0,200-1080,1800", scrollable = true)

    // Catalog

    @Test fun catalogDeclaresAllFourScreenTools() {
        val names = MobileToolCatalog.all().map { it.name }
        assertTrue(names.contains("screen_observe"))
        assertTrue(names.contains("screen_tap"))
        assertTrue(names.contains("screen_scroll"))
        assertTrue(names.contains("screen_type"))
    }

    @Test fun strictDecodeEnforcesTargetTokenAndDirectionShapes() {
        val token = "abcdef1234567890"
        assertNotNull(strict("screen_observe", emptyMap()))
        assertNotNull(strict("screen_tap", mapOf("target" to "n3", "token" to token)))
        assertNotNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "down", "token" to token)))
        assertNotNull(strict("screen_type", mapOf("target" to "n3", "text" to "hi", "token" to token)))
        // Bad shapes never decode.
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to "stale")))
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to token, "extra" to "1")))
        assertNull(strict("screen_tap", mapOf("target" to "button3", "token" to token)))
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to token.uppercase())))
        assertNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "left", "token" to token)))
        assertNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "DOWN", "token" to token)))
        assertNull(strict("screen_type", mapOf("target" to "n3", "text" to "", "token" to token)))
        assertNull(strict("screen_observe", mapOf("unused" to "1")))
    }

    // Validator

    @Test fun validatesScreenActions() {
        val token = "abcdef1234567890"
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenObserve),
            validator.validate(ActionRequest("screen_observe"))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenTap("n3", token)),
            validator.validate(ActionRequest("screen_tap", mapOf("target" to "n3", "token" to token)))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenScroll("n3", ScreenScrollDirection.DOWN, token)),
            validator.validate(
                ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "down", "token" to token))
            )
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenScroll("n3", ScreenScrollDirection.UP, token)),
            validator.validate(
                ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "up", "token" to token))
            )
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenType("n3", "hello", token)),
            validator.validate(
                ActionRequest("screen_type", mapOf("target" to "n3", "text" to "hello", "token" to token))
            )
        )
    }

    @Test fun rejectsMalformedScreenTargets() {
        val token = "abcdef1234567890"
        for (request in listOf(
            ActionRequest("screen_tap", mapOf("target" to "n3", "token" to "short")),
            ActionRequest("screen_tap", mapOf("target" to "3", "token" to token)),
            ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "sideways", "token" to token)),
            ActionRequest("screen_type", mapOf("target" to "n3", "text" to "  ", "token" to token)),
            ActionRequest("screen_type", mapOf("target" to "n3", "text" to "x".repeat(201), "token" to token)),
            ActionRequest("screen_tap", mapOf("target" to "n3"))
        )) {
            // Note: extra keys are rejected by the strict decoder (see
            // strictDecodeEnforcesTargetTokenAndDirectionShapes), not the
            // validator — the validator checks value shapes, matching the
            // existing tools' convention.
            val result = validator.validate(request)
            assertTrue("${request.name} ${request.arguments} must be rejected", result is ActionValidation.Rejected)
        }
        // Exactly 200 characters is the bound, not a rejection.
        assertTrue(
            validator.validate(
                ActionRequest("screen_type", mapOf("target" to "n3", "text" to "x".repeat(200), "token" to token))
            ) is ActionValidation.Valid
        )
    }

    // Tolerant decoder

    @Test fun tolerantDecodeMapsScreenArgs() {
        assertEquals(
            ActionRequest("screen_observe"),
            NativeActionDecoder.decode(ToolCall("screen_observe", "{}"))
        )
        assertEquals(
            ActionRequest("screen_tap", mapOf("target" to "n3", "token" to "abcdef1234567890")),
            NativeActionDecoder.decode(ToolCall("screen_tap", """{"target":"n3","token":"abcdef1234567890"}"""))
        )
        assertEquals(
            ActionRequest(
                "screen_scroll",
                mapOf("target" to "n3", "direction" to "up", "token" to "abcdef1234567890")
            ),
            NativeActionDecoder.decode(
                ToolCall("screen_scroll", """{"args":{"target":"n3","direction":"up","token":"abcdef1234567890"}}""")
            )
        )
        assertEquals(
            ActionRequest(
                "screen_type",
                mapOf("target" to "n3", "text" to "hello", "token" to "abcdef1234567890")
            ),
            NativeActionDecoder.decode(
                ToolCall("screen_type", """{"target":"n3","text":"hello","token":"abcdef1234567890"}""")
            )
        )
    }

    // Text parser routing

    private fun ready(text: String): ActionTurnPlan.Ready {
        val plan = ActionTurnPlan.parse(text)
        assertTrue("'$text' must parse as an action plan, was $plan", plan is ActionTurnPlan.Ready)
        return plan as ActionTurnPlan.Ready
    }

    @Test fun screenObservationFormsRouteToScreenObserve() {
        for (text in listOf(
            "what's on my screen",
            "what is on the screen",
            "look at my screen",
            "read the screen",
            "describe my screen",
            "show me the screen"
        )) {
            assertEquals(
                ActionRequest("screen_observe"),
                ready(text).steps.single().request
            )
        }
    }

    @Test fun screenMutationsAreNotTextRouted() {
        // Targets must come from a fresh screen_observe result, which text cannot supply.
        for (text in listOf("tap the search button", "scroll down", "type hello into the name field")) {
            val plan = ActionTurnPlan.parse(text)
            assertTrue("'$text' must not become an action plan, was $plan", plan !is ActionTurnPlan.Ready)
        }
    }

    // Compact snapshot

    @Test fun compactSnapshotIsBoundedAndNamesToken() {
        val nodes = (0 until 80).map { button(id = "n$it", label = "Button number $it with a very long label that keeps going past sixty characters") }
        val text = observation(*nodes.toTypedArray()).compactText("abcdef1234567890")
        assertTrue(text.contains("Screen: com.example.app (80 elements)"))
        assertTrue(text.contains("[n0] button \"Button number 0 with a very long label that keeps going past\" (10,20-100,80)"))
        assertTrue(text.contains("... 16 more elements omitted"))
        assertTrue(text.contains("observation token: abcdef1234567890"))
        assertFalse(text.contains("[n64]"))
    }

    // Session: grants

    @Test fun admissionRequiresApprovalAndIsOneGroupAtATime() {
        val session = ScreenControlSession()
        assertEquals(AdmitResult.NeedsApproval, session.admit("group-1", userApproved = false))
        assertFalse(session.isAdmitted)
        assertEquals(AdmitResult.Admitted, session.admit("group-1", userApproved = true))
        assertTrue(session.isAdmitted)
        assertEquals(AdmitResult.AlreadyAdmitted, session.admit("group-1", userApproved = true))
        val denied = session.admit("group-2", userApproved = true)
        assertTrue("a second group must not inherit the lease: $denied", denied is AdmitResult.Denied)
        assertTrue(session.isAdmitted)
        session.release()
        assertFalse(session.isAdmitted)
        // After release a later group admits fresh; nothing is silently inherited.
        assertEquals(AdmitResult.Admitted, session.admit("group-2", userApproved = true))
        assertEquals(DispatchGate.Allowed, session.dispatchGate())
    }

    @Test fun unadmittedMutationsHitTheAdmissionGate() {
        val session = ScreenControlSession()
        assertEquals(DispatchGate.NeedsAdmission, session.dispatchGate())
    }

    // Session: verified targets

    @Test fun observationRotatesTokenAndVerifiesTargets() {
        val session = ScreenControlSession()
        session.admit("group-1", userApproved = true)
        val obs1 = observation(button(), field(), list())
        val obs2 = observation(button())
        val token1 = session.recordObservation(obs1)
        val token2 = session.recordObservation(obs2)
        assertNotEquals(token1, token2)
        assertTrue(token1.matches(Regex("^[0-9a-f]{16}$")))
        // Old token is stale even for a target that still exists.
        val stale = session.verifyTarget("n0", token1, liveIdentity, obs1.contentFingerprint) { null }
        assertTrue("stale token must be rejected: $stale", stale is TargetVerification.Rejected)
        // Current token verifies; kind requirements are enforced.
        val verified = session.verifyTarget("n0", token2, liveIdentity, obs2.contentFingerprint) { node ->
            if (!node.clickable) "not tappable" else null
        }
        assertTrue(verified is TargetVerification.Verified)
        assertEquals("Search", (verified as TargetVerification.Verified).node.label)
        val wrongKind = session.verifyTarget("n0", token2, liveIdentity, obs2.contentFingerprint) { node ->
            if (!node.editable) "not editable" else null
        }
        assertTrue(wrongKind is TargetVerification.Rejected)
        val missing = session.verifyTarget("n9", token2, liveIdentity, obs2.contentFingerprint) { null }
        assertTrue(missing is TargetVerification.Rejected)
    }

    // Target binding (finding 3, same-window hardening): a dispatch binds a
    // snapshot to the live node only when a stable target identity agrees.
    // A null view id is never permission to revert to positional
    // (role/label/bounds) identity: a WebView can replace an "OK" control
    // with another same-label/same-bounds/no-resource-ID control in the same
    // window, preserving the walk index, and the old token, window identity
    // and positional matcher would all still pass. Fail closed instead.
    @Test fun liveNodeBindingRequiresStableIdentity() {
        // A snapshot that carries a view id binds only to the same live view
        // id; role, label AND bounds must also agree.
        val withId = button().copy(viewId = "com.example.app:id/search")
        assertTrue("same view id binds",
            withId.matchesLiveNode("button", "Search", "10,20-100,80", "com.example.app:id/search"))
        assertFalse("same label at a different position must not bind",
            withId.matchesLiveNode("button", "Search", "10,300-100,360", "com.example.app:id/search"))
        assertFalse("same position with a different label must not bind",
            withId.matchesLiveNode("button", "Send", "10,20-100,80", "com.example.app:id/search"))
        assertFalse("same label with a different role must not bind",
            withId.matchesLiveNode("text", "Search", "10,20-100,80", "com.example.app:id/search"))
        assertFalse("different view id must not bind",
            withId.matchesLiveNode("button", "Search", "10,20-100,80", "com.example.app:id/other"))
        assertFalse("missing live view id must not bind a snapshot that has one",
            withId.matchesLiveNode("button", "Search", "10,20-100,80", null))
        // Snapshots without a view id (common in WebViews) fail closed: a
        // null view id is not permission to revert to positional identity.
        val noId = button()
        assertFalse("snapshot without a view id must not bind on role/label/bounds alone",
            noId.matchesLiveNode("button", "Search", "10,20-100,80", null))
        assertFalse("snapshot without a view id must not bind even when the live node has one",
            noId.matchesLiveNode("button", "Search", "10,20-100,80", "com.example.app:id/search"))
    }

    // Session: touch pause and idle resume (T06)

    @Test fun manualTouchPausesDispatchAndIdleResumeRequiresReobserve() {
        var now = 1_000L
        val session = ScreenControlSession(clock = { now }, touchIdleMs = 3_000L)
        session.admit("group-1", userApproved = true)
        val obs = observation(button())
        val token = session.recordObservation(obs)
        assertEquals(DispatchGate.Allowed, session.dispatchGate())

        session.noteTouchStart()
        assertEquals(DispatchGate.Paused, session.dispatchGate())
        session.noteTouchEnd()
        // Still within the idle interval: paused, no countdown shown to the user.
        now += 1_000L
        assertEquals(DispatchGate.Paused, session.dispatchGate())
        // Idle interval elapsed: resume by re-observing, without a countdown.
        now += 3_000L
        assertEquals(DispatchGate.ResumeReobserve, session.dispatchGate())
        // The gate fires once; the caller re-observes, which rotates the token
        // and makes the pre-touch token stale by design.
        assertEquals(DispatchGate.Allowed, session.dispatchGate())
        val freshObs = observation(button())
        val fresh = session.recordObservation(freshObs)
        assertNotEquals(token, fresh)
        val stale = session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null }
        assertTrue("pre-touch token must be stale after resume re-observe: $stale", stale is TargetVerification.Rejected)
        assertTrue(session.verifyTarget("n0", fresh, liveIdentity, freshObs.contentFingerprint) { null } is TargetVerification.Verified)
    }

    // Session: stop and release

    @Test fun stopRequestBlocksDispatchAndReleaseClearsEverything() {
        val session = ScreenControlSession()
        session.admit("group-1", userApproved = true)
        val obs = observation(button())
        val token = session.recordObservation(obs)
        session.requestStop()
        assertTrue(session.isStopRequested)
        assertEquals(DispatchGate.Stopped, session.dispatchGate())
        session.release()
        assertFalse(session.isStopRequested)
        assertEquals(DispatchGate.NeedsAdmission, session.dispatchGate())
        assertTrue(
            session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null } is TargetVerification.Rejected
        )
    }

    // Window identity (finding F3): an approval binds to one window; a
    // window switch after approval dispatches nothing.

    /** Fake bridge modeling two windows with production-faithful live re-verification. */
    private class TwoWindowFakeBridge : ScreenBridge {
        data class Window(val identity: String, val nodes: List<ScreenNode>)

        private val windows = mutableMapOf<String, Window>()
        var activeWindowId: String? = null
        val effects = mutableListOf<Pair<ScreenNode, String>>()

        fun addWindow(window: Window) {
            windows[window.identity] = window
        }

        fun setNodes(identity: String, nodes: List<ScreenNode>) {
            windows[identity] = Window(identity, nodes)
        }

        override fun isAvailable(): Boolean = true

        override fun observe(): ScreenObservation? {
            val window = activeWindowId?.let { windows[it] } ?: return null
            return ScreenObservation(
                packageName = window.identity.substringBefore("#"),
                nodes = window.nodes,
                windowIdentity = window.identity
            )
        }

        override fun currentWindowIdentity(): String? = activeWindowId

        override fun currentContentFingerprint(): String? {
            val activeIdentity = activeWindowId ?: return null
            return contentFingerprintOf(windows[activeIdentity]?.nodes.orEmpty())
        }

        override fun tap(node: ScreenNode): Boolean {
            // Production-faithful live re-verification: the node's window
            // identity must be established and still be the active window,
            // and the live node must still bind the snapshot's stable
            // identity (see ScreenNode.matchesLiveNode).
            val liveIdentity = liveVerifiedIdentity(node) ?: return false
            effects += node.copy(expectedContentFingerprint = null) to liveIdentity
            return true
        }

        override fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean = false

        override fun type(node: ScreenNode, text: String): Boolean {
            val liveIdentity = liveVerifiedIdentity(node) ?: return false
            val live = windows[liveIdentity]?.nodes?.firstOrNull { it.id == node.id } ?: return false
            if (!live.editable) return false
            effects += node.copy(expectedContentFingerprint = null) to liveIdentity
            return true
        }

        /**
         * The active window identity when the snapshot still binds its live
         * node, or null when dispatch must fail closed. Mirrors
         * ServiceScreenBridge.withLiveNode: window identity, walk-index
         * lookup, then the stable-identity re-verification.
         */
        private fun liveVerifiedIdentity(node: ScreenNode): String? {
            val activeIdentity = activeWindowId ?: return null
            if (node.windowIdentity.isBlank() || node.windowIdentity != activeIdentity) return null
            val liveNodes = windows[activeIdentity]?.nodes ?: return null
            if (node.expectedContentFingerprint == null ||
                node.expectedContentFingerprint != contentFingerprintOf(liveNodes)) return null
            val live = liveNodes.firstOrNull { it.id == node.id } ?: return null
            if (!node.matchesLiveNode(live.role, live.label, live.bounds, live.viewId)) return null
            return activeIdentity
        }
        override fun showStopOverlay(taskLabel: String): Boolean = false
        override fun hideStopOverlay() {}
    }

    private sealed interface TapOutcome {
        data class Rejected(val reason: String) : TapOutcome
        data class BridgeFailed(val message: String) : TapOutcome
        data class Dispatched(val node: ScreenNode) : TapOutcome
    }

    /**
     * Test double of dispatchScreenMutation's dispatch order: dispatch gate,
     * then verifyTarget with the bridge's live window identity, then the
     * bridge tap on verification.
     */
    private fun dispatchTap(
        session: ScreenControlSession,
        bridge: ScreenBridge,
        targetId: String,
        token: String
    ): TapOutcome {
        if (session.dispatchGate() != DispatchGate.Allowed) {
            return TapOutcome.Rejected("dispatch gate blocked the mutation")
        }
        val node = when (val verified = session.verifyTarget(
            targetId, token, bridge.currentWindowIdentity(), bridge.currentContentFingerprint()
        ) { node ->
            if (!node.clickable) "Target ${node.id} (\"${node.label}\") is not tappable." else null
        }) {
            is TargetVerification.Verified -> verified.node
            is TargetVerification.Rejected -> return TapOutcome.Rejected(verified.reason)
        }
        return if (bridge.tap(node)) TapOutcome.Dispatched(node)
        else TapOutcome.BridgeFailed("the screen may have changed")
    }

    private fun admittedSession(): ScreenControlSession {
        val session = ScreenControlSession()
        assertEquals(AdmitResult.Admitted, session.admit("group-f3", userApproved = true))
        return session
    }

    private fun okButton(identity: String, viewId: String, id: String = "n4", label: String = "OK") =
        ScreenNode(
            id = id,
            label = label,
            role = "button",
            bounds = "100,400-300,460",
            clickable = true,
            viewId = viewId,
            windowIdentity = identity
        )

    @Test fun windowSwitchAfterApprovalDispatchesNothingOnB() {
        val windowA = "com.app.a#1"
        val windowB = "com.app.b#7"
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(windowA, listOf(okButton(windowA, "com.app.a:id/ok"))))
        // Same walk position, same role/label/bounds, different app, different view id.
        bridge.addWindow(TwoWindowFakeBridge.Window(windowB, listOf(okButton(windowB, "com.app.b:id/ok"))))
        val session = admittedSession()
        bridge.activeWindowId = windowA
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // The window switches after approval.
        bridge.activeWindowId = windowB
        val outcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("dispatch after a window switch must be rejected, was: $outcome",
            outcome is TapOutcome.Rejected)
        assertTrue("the reason must name the window change, was: $outcome",
            (outcome as TapOutcome.Rejected).reason.contains("different window"))
        assertTrue("no effect may land on either window, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    @Test fun replacementTargetInSameWindowIsStale() {
        val windowA = "com.app.a#1"
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(windowA, listOf(okButton(windowA, "com.app.a:id/ok_v1"))))
        val session = admittedSession()
        bridge.activeWindowId = windowA
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // Same window identity, but the target was replaced: same role,
        // label and bounds, different view id. The content generation changed
        // with the replacement, so the session fails the dispatch closed on
        // the generation binding before the bridge is consulted — the
        // approval binds to the observed generation, not the resource ID.
        bridge.setNodes(windowA, listOf(okButton(windowA, "com.app.a:id/ok_v2")))
        val outcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("replaced view in the same window must not dispatch, was: $outcome",
            outcome is TapOutcome.Rejected)
        assertTrue("the reason must name the content change, was: $outcome",
            (outcome as TapOutcome.Rejected).reason.contains("content changed"))
        assertTrue("no effect may be recorded, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    /**
     * Test double of a screen_type dispatch through the same gate ->
     * verifyTarget -> bridge order as dispatchTap.
     */
    private fun dispatchType(
        session: ScreenControlSession,
        bridge: ScreenBridge,
        targetId: String,
        token: String,
        text: String
    ): TapOutcome {
        if (session.dispatchGate() != DispatchGate.Allowed) {
            return TapOutcome.Rejected("dispatch gate blocked the mutation")
        }
        val node = when (val verified = session.verifyTarget(
            targetId, token, bridge.currentWindowIdentity(), bridge.currentContentFingerprint()
        ) { node ->
            if (!node.editable) "Target ${node.id} (\"${node.label}\") is not an editable field." else null
        }) {
            is TargetVerification.Verified -> verified.node
            is TargetVerification.Rejected -> return TapOutcome.Rejected(verified.reason)
        }
        return if (bridge.type(node, text)) TapOutcome.Dispatched(node)
        else TapOutcome.BridgeFailed("the screen may have changed")
    }

    // Finding 3, same-window hardening: a WebView can replace a control with
    // another same-label/same-bounds/no-resource-ID control in the same
    // window, preserving the walk index. The old token, window identity and
    // positional matcher all still pass — the safe-identity policy must fail
    // the dispatch with zero stale tap/type effects.

    @Test fun webViewDomReplacementInSameWindowDispatchesNothing() {
        val window = "com.app.web#3"
        // A WebView control carries no resource ID (viewId = null).
        fun webNode(id: String = "n4", editable: Boolean = false) = ScreenNode(
            id = id, label = "OK", role = if (editable) "field" else "button",
            bounds = "100,400-300,460", clickable = !editable, editable = editable,
            viewId = null, windowIdentity = window
        )
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(window, listOf(webNode())))
        val session = admittedSession()
        bridge.activeWindowId = window
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // The WebView replaces the DOM node: same walk index, same window,
        // same role/label/bounds, still no resource ID — but a different
        // control underneath.
        bridge.setNodes(window, listOf(webNode()))
        val tapOutcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("DOM-replaced no-ID target must not dispatch a tap, was: $tapOutcome",
            tapOutcome is TapOutcome.BridgeFailed)
        assertTrue("zero stale tap effects, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    @Test fun webViewDomReplacementInSameWindowTypesNothing() {
        val window = "com.app.web#3"
        fun webField(id: String = "n4") = ScreenNode(
            id = id, label = "OK", role = "field",
            bounds = "100,400-300,460", editable = true,
            viewId = null, windowIdentity = window
        )
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(window, listOf(webField())))
        val session = admittedSession()
        bridge.activeWindowId = window
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // Same-window DOM replacement of the no-ID field.
        bridge.setNodes(window, listOf(webField()))
        val typeOutcome = dispatchType(session, bridge, "n4", token, "hello")
        assertTrue("DOM-replaced no-ID field must not dispatch a type, was: $typeOutcome",
            typeOutcome is TapOutcome.BridgeFailed)
        assertTrue("zero stale type effects, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    // Finding 3 residual (same-window content replacement): an "OK" control
    // observed for record A, then the displayed record/action context is
    // asynchronously replaced with B while the control keeps its resource
    // ID, walk position, role, label and bounds. Content-change events are
    // ignored, so the old token, window identity and positional matcher all
    // still pass — only the content generation changed. The approval binds
    // to the observed generation, so dispatch must fail closed with zero
    // stale effects, for tap and for type.

    /** One record screen: a context text node plus the OK control acting on it. */
    private fun recordScreen(contextLabel: String, window: String, viewId: String, editable: Boolean = false) =
        listOf(
            ScreenNode(
                "n0", contextLabel, "text", "10,20-500,80",
                windowIdentity = window
            ),
            ScreenNode(
                "n4", "OK", if (editable) "field" else "button", "100,400-300,460",
                clickable = !editable, editable = editable,
                viewId = viewId, windowIdentity = window
            )
        )

    @Test fun sameWindowContentReplacementTapDispatchesNothing() {
        val window = "com.app.records#1"
        val viewId = "com.app.records:id/ok"
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(window, recordScreen("Record A", window, viewId)))
        val session = admittedSession()
        bridge.activeWindowId = window
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // The record context is replaced asynchronously: the OK control
        // keeps its resource ID, walk position, role, label and bounds.
        bridge.setNodes(window, recordScreen("Record B", window, viewId))
        val outcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("content-replaced target must not dispatch a tap, was: $outcome",
            outcome is TapOutcome.Rejected)
        assertTrue("the reason must name the content change, was: $outcome",
            (outcome as TapOutcome.Rejected).reason.contains("content changed"))
        assertTrue("zero stale tap effects, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    @Test fun sameWindowContentReplacementTypeDispatchesNothing() {
        val window = "com.app.records#1"
        val viewId = "com.app.records:id/note"
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(window, recordScreen("Record A", window, viewId, editable = true)))
        val session = admittedSession()
        bridge.activeWindowId = window
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        // Same-window replacement of the context around an identical field.
        bridge.setNodes(window, recordScreen("Record B", window, viewId, editable = true))
        val outcome = dispatchType(session, bridge, "n4", token, "hello")
        assertTrue("content-replaced field must not dispatch a type, was: $outcome",
            outcome is TapOutcome.Rejected)
        assertTrue("the reason must name the content change, was: $outcome",
            (outcome as TapOutcome.Rejected).reason.contains("content changed"))
        assertTrue("zero stale type effects, was: ${bridge.effects}", bridge.effects.isEmpty())
    }

    @Test fun unchangedContentStillDispatchesTapAndType() {
        // Positive control: with no content change, the generation still
        // matches and verified targets dispatch exactly once.
        val window = "com.app.records#1"
        val viewId = "com.app.records:id/ok"
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window(window, recordScreen("Record A", window, viewId)))
        val session = admittedSession()
        bridge.activeWindowId = window
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        val tapOutcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("unchanged content must dispatch, was: $tapOutcome",
            tapOutcome is TapOutcome.Dispatched)
        val fieldViewId = "com.app.records:id/note"
        bridge.setNodes(window, recordScreen("Record A", window, fieldViewId, editable = true))
        val token2 = session.recordObservation(checkNotNull(bridge.observe()))
        val typeOutcome = dispatchType(session, bridge, "n4", token2, "hello")
        assertTrue("unchanged content must dispatch a type, was: $typeOutcome",
            typeOutcome is TapOutcome.Dispatched)
        assertEquals(2, bridge.effects.size)
    }

    @Test fun fingerprintDistinguishesContentGenerations() {
        val window = "com.app.records#1"
        val viewId = "com.app.records:id/ok"
        val before = contentFingerprintOf(recordScreen("Record A", window, viewId))
        val after = contentFingerprintOf(recordScreen("Record B", window, viewId))
        assertNotEquals("replaced content must be a different generation", before, after)
        assertEquals(
            "identical content must be the same generation",
            before, contentFingerprintOf(recordScreen("Record A", window, viewId))
        )
        assertTrue(before.matches(Regex("^[0-9a-f]{16}$")))
    }

    @Test fun unchangedWindowDispatchesExactlyOnce() {
        val windowA = "com.app.a#1"
        val bridge = TwoWindowFakeBridge()
        val expectedNode = okButton(windowA, "com.app.a:id/ok")
        bridge.addWindow(TwoWindowFakeBridge.Window(windowA, listOf(expectedNode)))
        val session = admittedSession()
        bridge.activeWindowId = windowA
        val token = session.recordObservation(checkNotNull(bridge.observe()))
        val outcome = dispatchTap(session, bridge, "n4", token)
        assertTrue("unchanged window must dispatch, was: $outcome", outcome is TapOutcome.Dispatched)
        assertEquals("exactly one effect must be recorded on window A",
            listOf(expectedNode to windowA), bridge.effects)
    }

    @Test fun unestablishedIdentityFailsClosed() {
        val session = admittedSession()
        // Observation and nodes carry no window identity: fail closed even
        // though the live identity is established.
        val node = ScreenNode("n4", "OK", "button", "100,400-300,460", clickable = true)
        val obs = ScreenObservation(packageName = "com.example.app", nodes = listOf(node))
        val token = session.recordObservation(obs)
        val bridge = TwoWindowFakeBridge()
        bridge.addWindow(TwoWindowFakeBridge.Window("com.app.a#1", listOf(node)))
        bridge.activeWindowId = "com.app.a#1"
        val unestablished = session.verifyTarget("n4", token, bridge.currentWindowIdentity(), obs.contentFingerprint) { null }
        assertTrue("blank observed identity must be rejected: $unestablished",
            unestablished is TargetVerification.Rejected)
        // A live identity that cannot be established fails closed too.
        val noLive = session.verifyTarget("n4", token, null, obs.contentFingerprint) { null }
        assertTrue("null live identity must be rejected: $noLive",
            noLive is TargetVerification.Rejected)
        // A live content generation that cannot be established fails closed too.
        val noFingerprint = session.verifyTarget("n4", token, bridge.currentWindowIdentity(), null) { null }
        assertTrue("null live content generation must be rejected: $noFingerprint",
            noFingerprint is TargetVerification.Rejected)
        // The bridge also refuses nodes with no established identity.
        assertFalse("bridge tap on an identity-less node must not dispatch", bridge.tap(node))
        assertTrue(bridge.effects.isEmpty())
    }

    @Test fun windowStateChangeInvalidatesObservation() {
        val session = admittedSession()
        val obs = observation(button())
        val token = session.recordObservation(obs)
        assertTrue(session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null } is TargetVerification.Verified)
        session.invalidateObservation()
        val stale = session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null }
        assertTrue("invalidated observation must verify as stale: $stale",
            stale is TargetVerification.Rejected)
        assertTrue("invalidation must not release the grant", session.isAdmitted)
        assertTrue(windowChangeInvalidatesObservation("com.other.app", "com.battlesbudz.jarvis.v2"))
        assertFalse("Jarvis's own windows must not invalidate the observation",
            windowChangeInvalidatesObservation("com.battlesbudz.jarvis.v2", "com.battlesbudz.jarvis.v2"))
        assertTrue("an unidentifiable window fails closed",
            windowChangeInvalidatesObservation(null, "com.battlesbudz.jarvis.v2"))
    }

    @Test fun verificationBindsExpectedGenerationWithoutChangingTheContentHash() {
        val session = admittedSession()
        val obs = observation(button())
        val token = session.recordObservation(obs)
        val verified = session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null }
            as TargetVerification.Verified
        assertEquals(obs.contentFingerprint, verified.node.expectedContentFingerprint)
        assertNull("the raw observation is not a dispatch grant", obs.nodes.single().expectedContentFingerprint)
        assertEquals("dispatch metadata must not feed its own generation",
            obs.contentFingerprint, contentFingerprintOf(listOf(verified.node)))
    }

    @Test fun incompleteObservationsNeverVerifyEvenWithAnEqualFingerprint() {
        val session = admittedSession()
        val obs = observation(button()).copy(isComplete = false)
        val token = session.recordObservation(obs)
        assertTrue(session.verifyTarget("n0", token, liveIdentity, obs.contentFingerprint) { null }
            is TargetVerification.Rejected)
        assertTrue(obs.compactText(token).contains("Incomplete screen snapshot"))
    }

    // Voice stays denied by design (FinalVoiceToolGuard untouched).

    @Test fun screenToolsStayVoiceDenied() {
        for (name in listOf("screen_observe", "screen_tap", "screen_scroll", "screen_type")) {
            assertFalse(
                "$name must stay voice-denied",
                FinalVoiceToolGuard.allows("tap the button", name, mapOf("target" to "n0", "token" to "abcdef1234567890"))
            )
        }
        // Existing voice-allowed tools are unaffected.
        assertTrue(FinalVoiceToolGuard.allows("what is my battery", "read_battery", emptyMap()))
    }
}
