package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding: end-call visibility (round-two review). The sessionAlive state
 * gives VoiceCallScreen a better lifetime signal, but the parent
 * ConversationScreen used to hide the whole voice surface when armed went
 * true->false and when the call segment returned to PASSIVE_LISTENING —
 * and VoiceCallScreen only draws its bubble inside `if (visible)`, so
 * sessionAlive=true could not preserve a hidden End-call control.
 *
 * These tests mount the shipping ConversationScreen with the production
 * voiceContent wiring (the real VoiceCallScreen), start an armed session,
 * and assert the End-call control survives farewell/passive-listening and
 * disarm while the session is alive — and that it invokes the real
 * teardown callback. The child Bubble composable alone is not tested here:
 * the point is the parent visibility behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallEndVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Back-press double. ConversationScreen's BackHandler reads
     * LocalOnBackPressedDispatcherOwner, so the test provides its own
     * dispatcher instead of launching an Activity: Robolectric cannot
     * resolve a bare ComponentActivity from the manifest
     * (robolectric/robolectric#4736). The lifecycle is held at RESUMED so
     * the registered back callback actually fires.
     */
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

    /**
     * Activity-result double. VoiceCallScreen remembers permission/settings
     * launchers at its top level, and rememberLauncherForActivityResult
     * crashes without a LocalActivityResultRegistryOwner. The test registry
     * never launches for real.
     */
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

    private val callState = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING)
    private val busy = MutableStateFlow(false)
    private var teardownCalls = 0
    private lateinit var history: ConversationHistory

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
        callState.value = VoiceSessionState.PASSIVE_LISTENING
        busy.value = false
        teardownCalls = 0
        val context: Context = ApplicationProvider.getApplicationContext()
        history = ConversationHistory(
            context.getSharedPreferences("voice_call_end_visibility_test", Context.MODE_PRIVATE)
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides backOwner,
                LocalActivityResultRegistryOwner provides testActivityResultOwner
            ) {
                ConversationScreen(
                history = history,
                busy = busy,
                callState = callState,
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
                voiceContent = { visible, settingsOpen, dismissSettings, startRequest ->
                    // Production wiring (JarvisApp): the real VoiceCallScreen.
                    VoiceCallScreen(
                        visible = visible,
                        settingsOpen = settingsOpen,
                        memoryOpen = false,
                        onDismissSettings = dismissSettings,
                        startRequest = startRequest,
                        chatBusy = busy,
                        modelSelector = {},
                        resumedCall = null,
                        onResumeConsumed = {},
                        voicePlayback = MutableStateFlow(VoicePlaybackFrame()),
                        onVoiceTurn = { _, _, _, _ -> },
                        onEndVoiceCall = { report ->
                            teardownCalls++
                            // The real teardown (MainActivity.endVoiceCall ->
                            // JarvisRuntime.finishVoiceCall(stopSession =
                            // true)) truly ends the session.
                            VoiceSessionUi.sessionAlive.value = false
                            VoiceSessionUi.armed.value = false
                            report("Jarvis session stopped — microphone off.")
                        },
                        onCopyDiagnostics = {},
                        callEvidenceActions = CallEvidenceActions(
                            armAudio = {}, clearAudio = {},
                            snapshot = { CallEvidenceSnapshot("", null) }
                        )
                    )
                }
                )
            }
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        resetVoiceRuntime()
    }

    /** Start an armed session the way the runtime does: session alive + armed + active segment. */
    private fun startArmedSession() {
        VoiceSessionUi.sessionAlive.value = true
        VoiceSessionUi.armed.value = true
        callState.value = VoiceSessionState.ACTIVELY_LISTENING
        compose.waitForIdle()
    }

    @Test
    fun endCallSurvivesFarewellAndDisarmWhileSessionAlive() {
        startArmedSession()
        compose.onNodeWithTag("voice_call_end").assertExists()

        // Farewell: the call segment returns to passive listening and the
        // armed flag drops, but the session (wake listener, mic, foreground
        // service) stays alive.
        callState.value = VoiceSessionState.PASSIVE_LISTENING
        VoiceSessionUi.armed.value = false
        compose.waitForIdle()

        compose.onNodeWithTag("voice_call_end")
            .assertExists("End call must survive farewell + disarm while sessionAlive=true")

        // The control invokes the real teardown callback.
        compose.onNodeWithTag("voice_call_end").performClick()
        compose.waitForIdle()
        assertEquals("End call must invoke the teardown callback", 1, teardownCalls)

        // The true session end (what the real teardown does) hides the surface.
        compose.onNodeWithTag("voice_call_end").assertDoesNotExist()
    }

    @Test
    fun farewellWakeRearmThenExplicitEnd() {
        startArmedSession()

        // Farewell -> waiting for wake.
        callState.value = VoiceSessionState.PASSIVE_LISTENING
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.phase.value = VoicePhase.WAKE
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_end")
            .assertExists("End call must survive the farewell -> waiting-for-wake phase")

        // Wake rearm.
        VoiceSessionUi.armed.value = true
        callState.value = VoiceSessionState.ACTIVELY_LISTENING
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_end")
            .assertExists("End call must survive wake rearm")

        // Explicit End invokes the teardown and ends the session.
        compose.onNodeWithTag("voice_call_end").performClick()
        compose.waitForIdle()
        assertEquals("End call must invoke the teardown callback", 1, teardownCalls)
        compose.onNodeWithTag("voice_call_end").assertDoesNotExist()
    }

    @Test
    fun backNeverDismissesLiveSessionButDismissesPreCallSurface() {
        // No session: the pre-call surface opens from the voice button and
        // Back dismisses it, as before.
        compose.onNodeWithTag("voice_call_open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_overlay").assertExists()
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_overlay").assertDoesNotExist()

        // Live session: Back must not dismiss the surface — the End-call
        // control is the way out.
        startArmedSession()
        compose.onNodeWithTag("voice_call_end").assertExists()
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_end")
            .assertExists("Back must not dismiss a live session's End-call control")
    }

    @Test
    fun terminalFailureRemainsVisibleAfterTheCallOverlayClosesUntilDismissed() {
        startArmedSession()
        val message = "Voice Call turn failed: required audio asset is missing"
        VoiceSessionUi.reportFailure(history.current.value.id, message)
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
        callState.value = VoiceSessionState.PASSIVE_LISTENING
        compose.waitForIdle()

        compose.onNodeWithTag("voice_call_overlay").assertDoesNotExist()
        compose.onNodeWithText(message).assertExists()
        // A service teardown status cannot erase the failure explanation.
        VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        compose.waitForIdle()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithTag("voice_call_failure_dismiss").performClick()
        compose.onNodeWithTag("voice_call_failure").assertDoesNotExist()
    }

    @Test
    fun explicitEndDoesNotCreateAFailureAndAnotherConversationDoesNotShowIt() {
        startArmedSession()
        compose.onNodeWithTag("voice_call_end").performClick()
        compose.onNodeWithTag("voice_call_failure").assertDoesNotExist()
        VoiceSessionUi.reportFailure("another-conversation", "Voice Call turn failed: unrelated")
        compose.waitForIdle()
        compose.onNodeWithTag("voice_call_failure").assertDoesNotExist()
    }
}
