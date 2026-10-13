package com.battlesbudz.jarvis.v2.actions

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config

/**
 * Finding 3 residual: real accessibility-service coverage of the event
 * boundary. Drives the actual [ScreenControlService.onAccessibilityEvent]
 * with genuine [AccessibilityEvent] instances and asserts how the shared
 * session treats them: another package's window-state change invalidates
 * the observation, Jarvis's own overlay windows are handled explicitly and
 * never invalidate, content-change events never invalidate by themselves
 * (dispatch-time content-generation verification covers them), and touch
 * events still feed the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenControlServiceEventBoundaryTest {

    private lateinit var service: ScreenControlService
    private val session get() = ScreenControlService.sharedSession
    private val fixtureNodes = mutableListOf<AccessibilityNodeInfo>()

    @Before fun setUp() {
        service = Robolectric.buildService(ScreenControlService::class.java).create().get()
        session.release()
        session.admit("event-boundary", userApproved = true)
    }

    @After fun tearDown() {
        session.release()
        fixtureNodes.forEach { it.recycle() }
        fixtureNodes.clear()
    }

    private fun event(type: Int, packageName: String?): AccessibilityEvent =
        AccessibilityEvent.obtain().apply {
            eventType = type
            setPackageName(packageName)
        }

    private fun observedToken(): String {
        val obs = ScreenObservation(
            packageName = "com.other.app",
            nodes = listOf(
                ScreenNode("n0", "OK", "button", "100,400-300,460", clickable = true,
                    viewId = "com.other.app:id/ok", windowIdentity = "com.other.app#1")
            ),
            windowIdentity = "com.other.app#1"
        )
        return session.recordObservation(obs)
    }

    private fun isStale(token: String): Boolean =
        session.verifyTarget(
            "n0", token, "com.other.app#1",
            contentFingerprintOf(
                listOf(
                    ScreenNode("n0", "OK", "button", "100,400-300,460", clickable = true,
                        viewId = "com.other.app:id/ok", windowIdentity = "com.other.app#1")
                )
            )
        ) { null } is TargetVerification.Rejected

    @Test fun otherPackageWindowStateChangeInvalidatesObservation() {
        val token = observedToken()
        assertFalse("fresh observation must verify", isStale(token))
        service.onAccessibilityEvent(
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.other.app")
        )
        assertTrue("another package's window change must invalidate the observation", isStale(token))
        assertTrue("invalidation must not release the grant", session.isAdmitted)
    }

    @Test fun ownOverlayWindowEventsNeverInvalidate() {
        val token = observedToken()
        val own = service.packageName
        // Jarvis's own stop overlay / approval windows share the app
        // package: their window and content events are handled explicitly
        // and must neither invalidate the observation nor inherit approvals.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, own))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, own))
        assertFalse("own overlay window events must not invalidate the observation", isStale(token))
        assertTrue(session.isAdmitted)
    }

    @Test fun contentChangesNeverInvalidateByThemselves() {
        val token = observedToken()
        // Content-change events are deliberately not invalidated here: they
        // are too noisy, and dispatch-time content-generation verification
        // already covers stale content.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.other.app"))
        assertFalse("content changes must not invalidate the observation", isStale(token))
    }

    @Test fun touchEventsStillFeedTheSession() {
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, null))
        assertEquals("touch start must pause dispatch", DispatchGate.Paused, session.dispatchGate())
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, null))
        assertEquals("touch end must arm the idle-resume path", DispatchGate.Paused, session.dispatchGate())
    }

    @Test fun nullEventIsIgnored() {
        val token = observedToken()
        service.onAccessibilityEvent(null)
        assertFalse("a null event must not disturb the observation", isStale(token))
    }

    // These regressions exercise the real executor AND ServiceScreenBridge.
    // Only root acquisition is controlled; all traversal, hashing, identity
    // matching and performAction calls use the production Android adapter.
    private data class RecordTree(
        val root: AccessibilityNodeInfo,
        val context: AccessibilityNodeInfo,
        val target: AccessibilityNodeInfo
    )

    private fun node(label: String? = null, viewId: String? = null): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().also {
            fixtureNodes += it
            it.packageName = "com.records"
            it.className = "android.view.View"
            ReflectionHelpers.setField(it, "mWindowId", 7)
            it.text = label
            it.viewIdResourceName = viewId
            it.setBoundsInScreen(Rect(10, 20, 100, 80))
        }

    private fun recordTree(label: String = "Record A", description: String? = null): RecordTree {
        val root = node()
        val context = node(label).apply { contentDescription = description }
        val target = node("OK", "com.records:id/ok").apply {
            isClickable = true
            isEditable = true
            isScrollable = true
        }
        shadowOf(root).addChild(context)
        shadowOf(root).addChild(target)
        return RecordTree(root, context, target)
    }

    private fun bridge(root: () -> AccessibilityNodeInfo) = ServiceScreenBridge(
        service,
        activeRoot = { AccessibilityNodeInfo.obtain(root()) },
        available = { true }
    )

    private fun actions(token: String) = listOf(
        MobileAction.ScreenTap("n1", token),
        MobileAction.ScreenType("n1", "hello", token),
        MobileAction.ScreenScroll("n1", ScreenScrollDirection.DOWN, token)
    )

    @Test fun finalBridgeGenerationRejectsContextSwapAfterExecutorPrecheckForEveryMutation() {
        for (actionIndex in 0..2) {
            val before = recordTree()
            val after = recordTree("Record B")
            var active = before.root
            var acquiredRoots = 0
            val bridge = bridge { acquiredRoots++; active }
            val localSession = ScreenControlSession().apply { admit("swap-$actionIndex", true) }
            var swappedAfterPrecheck = false
            val executor = AndroidMobileActionExecutor(
                service, screenBridge = bridge, screenSession = localSession,
                onDiagnostic = { message ->
                    // This diagnostic occurs only after verifyTarget succeeds
                    // and immediately before the actual bridge mutation.
                    if (message.contains(" target=")) {
                        swappedAfterPrecheck = true
                        active = after.root
                    }
                }
            )
            assertTrue(executor.execute(MobileAction.ScreenObserve).succeeded)
            val token = checkNotNull(localSession.currentToken)
            val result = executor.execute(actions(token)[actionIndex])
            assertTrue("regression must reach the after-precheck swap", swappedAfterPrecheck)
            assertFalse("changed context must fail closed: $result", result.succeeded)
            assertEquals("observation, identity, precheck, final dispatch root", 4, acquiredRoots)
            assertTrue("old record must have zero effects", shadowOf(before.target).performedActions.isEmpty())
            assertTrue("new record must have zero effects, including focus", shadowOf(after.target).performedActions.isEmpty())
        }
    }

    @Test fun unchangedCompleteTreeDispatchesEachMutationExactlyOnce() {
        val expected = listOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_SET_TEXT,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        for (actionIndex in 0..2) {
            val tree = recordTree()
            val localSession = ScreenControlSession().apply { admit("positive-$actionIndex", true) }
            val executor = AndroidMobileActionExecutor(service, screenBridge = bridge { tree.root },
                screenSession = localSession)
            assertTrue(executor.execute(MobileAction.ScreenObserve).succeeded)
            assertTrue(executor.execute(actions(checkNotNull(localSession.currentToken))[actionIndex]).succeeded)
            assertEquals(listOf(expected[actionIndex]), shadowOf(tree.target).performedActions)
            if (actionIndex == 1) {
                val args = shadowOf(tree.target).performedActionsWithArgs.single().second
                assertEquals("hello", args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            }
        }
    }

    @Test fun directLegacyBridgeMutationsWithoutVerifiedGenerationFailClosed() {
        val tree = recordTree()
        val bridge = bridge { tree.root }
        val target = checkNotNull(bridge.observe()).nodes.single { it.id == "n1" }
        assertNull(target.expectedContentFingerprint)
        assertFalse(bridge.tap(target))
        assertFalse(bridge.type(target, "hello"))
        assertFalse(bridge.scroll(target, ScreenScrollDirection.DOWN))
        assertTrue(shadowOf(tree.target).performedActions.isEmpty())
    }

    private fun boundTarget(bridge: ScreenBridge): ScreenNode {
        val observation = checkNotNull(bridge.observe())
        assertTrue("fixture must be completely observable", observation.isComplete)
        val localSession = ScreenControlSession()
        val token = localSession.recordObservation(observation)
        val verified = localSession.verifyTarget("n1", token, bridge.currentWindowIdentity(),
            bridge.currentContentFingerprint()) { null } as TargetVerification.Verified
        return verified.node
    }

    @Test fun eightyFirstTextCharacterChangesGenerationWithoutExpandingPromptLabels() {
        val before = recordTree("x".repeat(80) + "A")
        val after = recordTree("x".repeat(80) + "B")
        var active = before.root
        val bridge = bridge { active }
        val observation = checkNotNull(bridge.observe())
        assertEquals(80, observation.nodes.first().label.length)
        val target = boundTarget(bridge)
        active = after.root
        val changed = checkNotNull(bridge.observe())
        assertEquals("model-facing labels remain identical and bounded", observation.nodes, changed.nodes)
        assertNotEquals(observation.contentFingerprint, changed.contentFingerprint)
        assertFalse(bridge.tap(target))
        assertTrue(shadowOf(after.target).performedActions.isEmpty())
    }

    @Test fun rawDescriptionChangesGenerationEvenWhenTextMasksItInTheLabel() {
        val before = recordTree("Same label", "Record A")
        val after = recordTree("Same label", "Record B")
        var active = before.root
        val bridge = bridge { active }
        val target = boundTarget(bridge)
        active = after.root
        assertFalse(bridge.type(target, "hello"))
        assertTrue(shadowOf(after.target).performedActions.isEmpty())
    }

    private fun assertIncomplete(tree: RecordTree) {
        val bridge = bridge { tree.root }
        val observation = checkNotNull(bridge.observe())
        assertFalse(observation.isComplete)
        assertNull(bridge.currentContentFingerprint())
        // Supplying a made-up equal-looking grant must not bless an unknown
        // generation at the bridge, even if target identity is unchanged.
        val target = ScreenNode("n1", "OK", "field", "10,20-100,80",
            clickable = true, editable = true, scrollable = true,
            viewId = "com.records:id/ok", windowIdentity = "com.records#7",
            expectedContentFingerprint = "unverified")
        assertFalse(bridge.tap(target))
        assertFalse(bridge.type(target, "hello"))
        assertFalse(bridge.scroll(target, ScreenScrollDirection.DOWN))
        assertTrue(shadowOf(tree.target).performedActions.isEmpty())
    }

    @Test fun nodeLimitIncludesUnlabelledContainersAndRejectsUnseenContext() {
        val tree = recordTree()
        repeat(197) { shadowOf(tree.root).addChild(node()) }
        val bridge = bridge { tree.root }
        assertTrue("exactly 200 total nodes remains complete", checkNotNull(bridge.observe()).isComplete)
        shadowOf(tree.root).addChild(node("unseen record context"))
        assertIncomplete(tree)
    }

    @Test fun depthOverflowRejectsUnseenContextEvenWithAnEarlyIdenticalTarget() {
        val tree = recordTree()
        var parent = tree.root
        repeat(26) {
            val child = node()
            shadowOf(parent).addChild(child)
            parent = child
        }
        assertIncomplete(tree)
    }

    @Test fun advertisedMissingChildFailsClosed() {
        val tree = recordTree()
        val children = mutableListOf<AccessibilityNodeInfo?>(tree.context, tree.target, null)
        ReflectionHelpers.setField(shadowOf(tree.root), "children", children)
        assertIncomplete(tree)
    }

    @Test fun oversizedTextBudgetFailsClosedInsteadOfHashingAPrefix() {
        assertIncomplete(recordTree("x".repeat(65_537)))
    }

    @Test fun missingPackageOrInvalidWindowIdNeverEstablishesIdentity() {
        for (missingPackage in listOf(true, false)) {
            val tree = recordTree()
            if (missingPackage) tree.root.packageName = " "
            else ReflectionHelpers.setField(tree.root, "mWindowId", -1)
            val bridge = bridge { tree.root }
            assertNull(bridge.currentWindowIdentity())
            assertNull(bridge.currentContentFingerprint())
            assertFalse(checkNotNull(bridge.observe()).isComplete)
        }
    }

}
