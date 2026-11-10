package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.actions.ActionApprovalStore
import com.battlesbudz.jarvis.v2.actions.ActionDispatchGate
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ApprovalDecision
import com.battlesbudz.jarvis.v2.actions.AuthorizedDispatch
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.JournaledActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.actions.ToolTaskState
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Inline approval cards (2026-11-10): approvals requested during a
 * conversation render as cards in that conversation's thread and are
 * decided in place, through the same (attempt id, generation, command)
 * callback the phone-tasks panel used. These tests mount the shipping
 * ConversationScreen with a real ledger and approval store: a sandbox
 * command card shows the exact command and its approval executes the
 * parked action exactly once; a phone-action card's denial cancels the
 * attempt with nothing executed; the tasks panel no longer opens for a
 * pending approval; and a card never leaks into another conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineApprovalCardsTest {

    @get:Rule
    val compose = createComposeRule()

    // Back-press and activity-result doubles, as in
    // VoiceCallEndVisibilityTest: ConversationScreen registers a
    // BackHandler and ChatVoiceInput remembers a permission launcher,
    // and Robolectric cannot resolve a bare ComponentActivity.
    private val backDispatcher = OnBackPressedDispatcher()
    private val backLifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }
    private val backOwner = object : OnBackPressedDispatcherOwner {
        override val lifecycle: Lifecycle get() = backLifecycleOwner.lifecycle
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher
    }
    private val testActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
        }
    }
    private val testActivityResultOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry
            get() = testActivityResultRegistry
    }

    private lateinit var history: ConversationHistory
    private lateinit var ledger: ToolTaskLedger
    private lateinit var approvals: ActionApprovalStore
    private lateinit var journalFlow: MutableStateFlow<ToolTaskJournal?>
    private val effects = AtomicInteger(0)
    private val approveCommands = AtomicInteger(0)

    private fun resetVoiceRuntime() {
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
        VoiceSessionUi.phase.value = VoicePhase.IDLE
        VoiceSessionUi.status.value = ""
        VoiceSessionUi.paused.value = false
        VoiceSessionUi.liveTranscript.value = ""
        VoiceSessionUi.level.value = 0f
        VoiceSessionUi.failure.value = null
    }

    @Before
    fun setUp() {
        resetVoiceRuntime()
        effects.set(0)
        approveCommands.set(0)
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("inline_approval_cards_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        history = ConversationHistory(prefs)
        ledger = ToolTaskLedger()
        approvals = ActionApprovalStore(ledger.store)
        journalFlow = MutableStateFlow(ledger.journal())
        compose.setContent {
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides backOwner,
                LocalActivityResultRegistryOwner provides testActivityResultOwner
            ) {
                ConversationScreen(
                    history = history,
                    busy = MutableStateFlow(false),
                    callState = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                    onSend = { _, _ -> null },
                    selectedModel = LocalModelSpec(
                        id = "test-model",
                        fileName = "test-model.litertlm",
                        recommendedGpu = false
                    ),
                    onSelectConversation = { null },
                    onEndVoice = {},
                    onOpenVoiceCalls = {},
                    resumedVoice = false,
                    phoneTasks = journalFlow,
                    onPhoneTaskAction = { id, generation, command -> decide(id, generation, command) },
                    voiceContent = { _, _, _, _ -> }
                )
            }
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        resetVoiceRuntime()
    }

    /** The coordinator's decision shape, minus Android admissions. */
    private fun decide(id: String, generation: Long, command: String) {
        val attempt = ledger.get(id)?.takeIf { it.generation == generation }
        if (attempt != null) {
            when (command) {
                "approve" -> {
                    approveCommands.incrementAndGet()
                    JournaledActionPipeline(
                        ledger,
                        MobileActionExecutor { effects.incrementAndGet(); ExecutionResult(true, "done") }
                    ).executeAttempt(attempt, attempt.approvalId?.let { approvals.get(it) })
                }
                "deny" -> attempt.approvalId?.let { approvals.deny(it) }
            }
        }
        journalFlow.value = ledger.journal()
    }

    /** Park a request in the current conversation and request its approval. */
    private fun park(request: ActionRequest): AuthorizedDispatch {
        val group = ledger.admit(listOf(request), history.current.value.id)
        val pending = ActionDispatchGate(approvals, ledger)
            .prepare(checkNotNull(ledger.get(group.attemptIds.single())))
        journalFlow.value = ledger.journal()
        compose.waitForIdle()
        return pending
    }

    @Test
    fun sandboxCardShowsExactCommandAndApproveExecutesExactlyOnce() {
        val pending = park(ActionRequest("run_in_sandbox", mapOf("command" to "echo hello-inline")))

        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertExists()
        compose.onNodeWithText("Run in the sandbox: echo hello-inline").assertExists()
        // The tasks panel no longer opens for a pending approval.
        compose.onNodeWithTag("phone_tasks_open").assertDoesNotExist()
        assertEquals(0, effects.get())

        compose.onNodeWithTag("task_approve_${pending.task.id}").performClick()
        compose.waitForIdle()

        assertEquals(1, approveCommands.get())
        assertEquals("the approved command must execute exactly once", 1, effects.get())
        assertEquals(ToolTaskState.SUCCEEDED, ledger.get(pending.task.id)?.state)
        assertTrue(checkNotNull(approvals.get(pending.approval.id)).consumed)
        // The decided card is gone and cannot be tapped again.
        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertDoesNotExist()
        compose.onNodeWithTag("task_approve_${pending.task.id}").assertDoesNotExist()
    }

    @Test
    fun phoneActionCardDenyCancelsWithoutExecuting() {
        val pending = park(ActionRequest("set_volume", mapOf("level" to "25")))

        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertExists()
        compose.onNodeWithText("Set media volume to 25%").assertExists()

        compose.onNodeWithTag("task_deny_${pending.task.id}").performClick()
        compose.waitForIdle()

        assertEquals("a denied action must never execute", 0, effects.get())
        assertEquals(ToolTaskState.CANCELLED, ledger.get(pending.task.id)?.state)
        assertEquals(ApprovalDecision.DENIED, approvals.get(pending.approval.id)?.decision)
        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertDoesNotExist()
        compose.onNodeWithTag("task_deny_${pending.task.id}").assertDoesNotExist()
    }

    @Test
    fun cardDoesNotLeakIntoAnotherConversation() {
        val pending = park(ActionRequest("run_in_sandbox", mapOf("command" to "echo only-here")))
        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertExists()

        history.newConversation()
        compose.waitForIdle()

        compose.onNodeWithTag("inline_approval_${pending.task.id}").assertDoesNotExist()
        compose.onNodeWithText("Run in the sandbox: echo only-here").assertDoesNotExist()
        // The approval itself is untouched: still parked for its own conversation.
        assertEquals(ToolTaskState.WAITING_APPROVAL, ledger.get(pending.task.id)?.state)
    }
}
