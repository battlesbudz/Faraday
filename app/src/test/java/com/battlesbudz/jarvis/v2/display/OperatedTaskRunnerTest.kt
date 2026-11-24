package com.battlesbudz.jarvis.v2.display

import android.content.Context
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.perception.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class OperatedTaskRunnerTest {
    private val request = OperatedTaskRequest(OperatedTaskRequest.Kind.SEND_MESSAGE,
        "Messages", "Andrea", "testing", "send a message to Andrea saying testing")
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun mainDisplayAppIsBlockedBeforeDisplayCreationOrInput() = runBlocking {
        var creates = 0
        var injections = 0
        val helper = helper { creates++ }
        val outcome = runner(HelperOperatedDisplay({ helper }),
            location = TaskLocation.MAIN_DISPLAY,
            injector = OperatedInputInjector { _, _ -> injections++; true }).run(request)
        assertEquals(OperatedTaskOutcome.Blocked("Messages is open on your main screen, so I left it alone."), outcome)
        assertEquals(0, creates)
        assertEquals(0, injections)
    }

    @Test fun helperUnavailableReturnsTheExistingHelperFactAndEndsOnce() = runBlocking {
        val ended = mutableListOf<String>()
        val outcome = runner(HelperOperatedDisplay({ null }), diagnostics = ended::add).run(request)
        assertEquals(OperatedTaskOutcome.Failed("The app-control helper is unavailable. The task stopped."), outcome)
        assertEquals(1, ended.count { it.contains("TaskEnded") })
    }

    @Test fun stepBudgetStopsBeforeDisplayMutationAndRecordsOneEnd() = runBlocking {
        var creates = 0
        val ended = mutableListOf<String>()
        val outcome = runner(HelperOperatedDisplay({ helper { creates++ } }), diagnostics = ended::add,
            maxSteps = 0).run(request)
        assertTrue(outcome is OperatedTaskOutcome.Failed)
        assertEquals(0, creates)
        assertEquals(1, ended.count { it.contains("TaskEnded") })
    }

    @Test fun absentRecipientAfterListEvidenceReturnsBlockedFact() = runBlocking {
        val display = HelperOperatedDisplay({ helper {} })
        val identity = OperatedDisplayIdentity(7, "task", "Messages")
        val stage = AtomicInteger(0)
        val observations = OperatedObservationReader {
            val nodes = when (stage.get()) {
                0 -> listOf(ObservedNode("android.widget.TextView", "messages:id/title", "Messages", emptyList()),
                    ObservedNode("android.widget.Button", "messages:id/compose", "Compose", listOf("clickable")))
                1 -> listOf(ObservedNode("android.widget.EditText", "messages:id/search", null, listOf("editable")))
                else -> listOf(ObservedNode("android.widget.TextView", "messages:id/result", "Andrew", listOf("clickable")))
            }
            createObservation(identity, TreeWindowCandidate(7, "com.example.messages", nodes), stage.get().toLong())
        }
        val injector = OperatedInputInjector { _, event ->
            if (event.getString("type") == "tap" && event.getFloat("x") >= 30f) stage.set(1)
            if (event.getString("type") == "key") stage.set(2)
            true
        }
        val liveNodes: (OperatedDisplayIdentity) -> List<LiveOperatedNode> = {
            when (stage.get()) {
                0 -> listOf(LiveOperatedNode("android.widget.Button", "messages:id/compose", "Compose", setOf("clickable"), OperatedNodeBounds(30, 0, 50, 20)))
                1 -> listOf(LiveOperatedNode("android.widget.EditText", "messages:id/search", null, setOf("editable"), OperatedNodeBounds(10, 0, 30, 20)))
                else -> listOf(LiveOperatedNode("android.widget.TextView", "messages:id/result", "Andrew", setOf("clickable"), OperatedNodeBounds(0, 0, 20, 20)))
            }
        }
        val outcome = runner(display, observations = observations, liveNodes = liveNodes, injector = injector).run(request)
        assertEquals(OperatedTaskOutcome.Blocked(
            "I opened Messages and searched, but there is no contact or conversation named \"Andrea\"."), outcome)
    }

    @Test fun ambiguousConversationRowsFallThroughToAmbiguousSearchWithoutTappingRows() = runBlocking {
        val display = HelperOperatedDisplay({ helper {} })
        val identity = OperatedDisplayIdentity(7, "task", "Messages")
        val stage = AtomicInteger(0)
        val taps = CopyOnWriteArrayList<Float>()
        val observations = OperatedObservationReader {
            val nodes = when (stage.get()) {
                0 -> listOf(
                    ObservedNode("android.widget.TextView", "messages:id/title", "Messages", emptyList()),
                    ObservedNode("android.widget.TextView", "messages:id/row1", "Andrea Smith", listOf("clickable")),
                    ObservedNode("android.widget.TextView", "messages:id/row2", "Andrea Jones", listOf("clickable")),
                    ObservedNode("android.widget.Button", "messages:id/compose", "Compose", listOf("clickable")))
                1 -> listOf(ObservedNode("android.widget.EditText", "messages:id/search", null, listOf("editable")))
                else -> listOf(
                    ObservedNode("android.widget.TextView", "messages:id/result1", "Andrea Smith", listOf("clickable")),
                    ObservedNode("android.widget.TextView", "messages:id/result2", "Andrea Jones", listOf("clickable")))
            }
            createObservation(identity, TreeWindowCandidate(7, "com.example.messages", nodes), stage.get().toLong())
        }
        val injector = OperatedInputInjector { _, event ->
            when (event.getString("type")) {
                "tap" -> {
                    val x = event.getFloat("x")
                    taps += x
                    if (x >= 50f) stage.set(1)
                }
                "key" -> stage.set(2)
            }
            true
        }
        val liveNodes: (OperatedDisplayIdentity) -> List<LiveOperatedNode> = {
            when (stage.get()) {
                0 -> listOf(
                    LiveOperatedNode("android.widget.TextView", "messages:id/row1", "Andrea Smith", setOf("clickable"), OperatedNodeBounds(0, 0, 20, 20)),
                    LiveOperatedNode("android.widget.TextView", "messages:id/row2", "Andrea Jones", setOf("clickable"), OperatedNodeBounds(20, 0, 40, 20)),
                    LiveOperatedNode("android.widget.Button", "messages:id/compose", "Compose", setOf("clickable"), OperatedNodeBounds(60, 0, 80, 20)))
                1 -> listOf(LiveOperatedNode("android.widget.EditText", "messages:id/search", null, setOf("editable"), OperatedNodeBounds(40, 0, 60, 20)))
                else -> listOf(
                    LiveOperatedNode("android.widget.TextView", "messages:id/result1", "Andrea Smith", setOf("clickable"), OperatedNodeBounds(0, 0, 20, 20)),
                    LiveOperatedNode("android.widget.TextView", "messages:id/result2", "Andrea Jones", setOf("clickable"), OperatedNodeBounds(20, 0, 40, 20)))
            }
        }
        val outcome = runner(display, observations = observations, liveNodes = liveNodes, injector = injector).run(request)
        assertEquals(OperatedTaskOutcome.Blocked(
            "I opened Messages and searched, but there is no contact or conversation named \"Andrea\"."), outcome)
        assertTrue(taps.none { it in 0f..40f })
    }

    @Test fun approvalIsRequestedBeforeSendAndEveryInjectionUsesOwnedDisplay() = runBlocking {
        val fixture = scriptedRunner(ApprovalDecision.APPROVED)
        val outcome = fixture.first.run(request)
        assertEquals(OperatedTaskOutcome.Completed("Message sent to Andrea: \"testing\""), outcome)
        assertTrue(fixture.second.isNotEmpty())
        assertTrue(fixture.second.all { it == 7 })
        assertTrue(fixture.third.any { it >= 50f })
    }

    @Test fun deniedApprovalNeverInjectsTheSendTap() = runBlocking {
        val fixture = scriptedRunner(ApprovalDecision.DENIED)
        val outcome = fixture.first.run(request)
        assertEquals(OperatedTaskOutcome.Declined("The message to Andrea wasn't sent — you declined the approval."), outcome)
        assertTrue(fixture.second.all { it == 7 })
        assertTrue(fixture.third.none { it >= 50f })
    }

    @Test fun stopDuringApprovalEndsWithoutSending() = runBlocking {
        val fixture = scriptedRunner(ApprovalDecision.STALE, stopDuringApproval = true)
        assertEquals(OperatedTaskOutcome.Failed("Task stopped."), fixture.first.run(request))
        assertTrue(fixture.third.none { it >= 50f })
    }

    private fun runner(
        display: HelperOperatedDisplay,
        observations: OperatedObservationReader = OperatedObservationReader { null },
        liveNodes: (OperatedDisplayIdentity) -> List<LiveOperatedNode> = { emptyList() },
        injector: OperatedInputInjector = OperatedInputInjector { _, _ -> true },
        location: TaskLocation = TaskLocation.NONE,
        diagnostics: (String) -> Unit = {},
        maxSteps: Int = 40,
    ) = OperatedTaskRunner(
        context = context,
        display = display,
        observations = observations,
        liveNodes = liveNodes,
        injector = injector,
        launcher = OperatedAppLauncher { _, _ -> true },
        approvals = SendApprovalGate { _, _ -> ApprovalDecision.DENIED },
        diagnostics = diagnostics,
        resolvePackage = { "com.example.messages" },
        taskLocation = TaskLocationProvider { location },
        screenSize = { Triple(1080, 1920, 420) },
        maxSteps = maxSteps)

    private fun helper(onCreate: () -> Unit) = object : OperatedDisplayHelper {
        override fun create(name: String, width: Int, height: Int, densityDpi: Int): Int { onCreate(); return 7 }
        override fun release(displayId: Int) = Unit
        override fun moveTask(taskId: Int, displayId: Int) = false
        override fun readFrame(displayId: Int): DisplayFrameMailbox.Frame? = null
    }

    private fun scriptedRunner(decision: ApprovalDecision, stopDuringApproval: Boolean = false): Triple<OperatedTaskRunner, List<Int>, List<Float>> {
        val stage = AtomicInteger(0)
        val ids = CopyOnWriteArrayList<Int>()
        val taps = CopyOnWriteArrayList<Float>()
        val identity = OperatedDisplayIdentity(7, "script", "Messages")
        fun observation(): OperatedObservation {
            val nodes = when (stage.get()) {
                0 -> listOf(ObservedNode("android.widget.TextView", "messages:id/row", "Andrea", listOf("clickable")))
                1 -> listOf(
                    ObservedNode("android.widget.TextView", "messages:id/title", "Andrea", emptyList()),
                    ObservedNode("android.widget.EditText", "messages:id/compose", "testing", listOf("editable")),
                    ObservedNode("android.widget.Button", "messages:id/send", "Send", listOf("clickable")))
                else -> listOf(
                    ObservedNode("android.widget.TextView", "messages:id/title", "Andrea", emptyList()),
                    ObservedNode("android.widget.EditText", "messages:id/compose", null, listOf("editable")),
                    ObservedNode("android.widget.TextView", "messages:id/body", "testing", emptyList()))
            }
            return createObservation(identity, TreeWindowCandidate(7, "com.example.messages", nodes), stage.get().toLong())!!
        }
        val helper = helper {}
        val injector = OperatedInputInjector { displayId, event ->
            ids += displayId
            if (event.getString("type") == "tap") {
                val x = event.getFloat("x")
                taps += x
                if (stage.get() == 0) stage.set(1)
                else if (x >= 50f && decision == ApprovalDecision.APPROVED) stage.set(2)
            }
            true
        }
        lateinit var runner: OperatedTaskRunner
        runner = OperatedTaskRunner(
            context = context,
            display = HelperOperatedDisplay({ helper }),
            observations = OperatedObservationReader { observation() },
            liveNodes = { liveIdentity ->
                assertEquals(7, liveIdentity.displayId)
                when (stage.get()) {
                    0 -> listOf(LiveOperatedNode("android.widget.TextView", "messages:id/row", "Andrea", setOf("clickable"), OperatedNodeBounds(0, 0, 20, 20)))
                    1 -> listOf(
                        LiveOperatedNode("android.widget.EditText", "messages:id/compose", "testing", setOf("editable"), OperatedNodeBounds(10, 0, 30, 20)),
                        LiveOperatedNode("android.widget.Button", "messages:id/send", "Send", setOf("clickable"), OperatedNodeBounds(50, 0, 70, 20)))
                    else -> emptyList()
                }
            },
            injector = injector,
            launcher = OperatedAppLauncher { _, launchedOn -> launchedOn.displayId == 7 },
            approvals = SendApprovalGate { taskId, _ ->
                if (stopDuringApproval) { runner.stop(taskId); runner.awaitControl() }
                decision
            },
            resolvePackage = { "com.example.messages" },
            taskLocation = TaskLocationProvider { TaskLocation.NONE },
            screenSize = { Triple(1080, 1920, 420) },
            textEntry = OperatedTextEntry(injector, KeyEventMapper { listOf(KeyPress(android.view.KeyEvent.KEYCODE_T, 0)) }))
        return Triple(runner, ids, taps)
    }
}
