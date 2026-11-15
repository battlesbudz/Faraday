package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #19's gap, the in-code half (2026-11-10): the conversation
 * root column pads by WindowInsets.safeDrawing, which already
 * includes the IME inset, and the composer row padded by imePadding
 * on top of it with nothing consuming the inset in between — the
 * keyboard height landed twice and the composer floated about one
 * keyboard height above the keyboard (Battles's build-1771 proof).
 * These tests mount the shipping ConversationScreen, dispatch real
 * window insets at its host window, and measure where the composer
 * actually lands: with a 600 px IME inset its bottom edge must sit
 * exactly one inset above the root's bottom (plus the row's own
 * 12 dp padding), never two; with no IME it keeps the resting
 * position it has always had, just above the navigation bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ConversationImeInsetsTest {

    @get:Rule
    val compose = createComposeRule()

    // Back-press and activity-result doubles, as in
    // InlineApprovalCardsTest: ConversationScreen registers a
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

    private var hostActivity: ComponentActivity? = null

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
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("conversation_ime_insets_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val journalFlow = MutableStateFlow(ToolTaskLedger().journal())
        compose.setContent {
            val localContext = LocalContext.current
            SideEffect { hostActivity = unwrapActivity(localContext) }
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

    private fun unwrapActivity(context: Context): ComponentActivity? {
        var current: Context? = context
        while (current != null) {
            if (current is ComponentActivity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun findComposeView(view: View): View? {
        // Matched by name: AndroidComposeView is internal to the
        // compose library at this version and cannot be referenced
        // from the test source set.
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * Deliver a full inset set to the mounted screen's window: a
     * 24 px status bar, a 48 px navigation bar, and an IME of the
     * given height. Dispatched on the decor view (the natural path)
     * and directly on the compose view, so the value lands however
     * the host propagates insets under Robolectric.
     */
    private fun dispatchInsets(imeBottomPx: Int) {
        val activity = checkNotNull(hostActivity) { "compose host activity was not captured" }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
            .build()
        activity.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
            findComposeView(activity.window.decorView)?.let {
                ViewCompat.dispatchApplyWindowInsets(it, insets)
            }
        }
        compose.waitForIdle()
    }

    /** Distance from the composer's bottom edge to the root's bottom, in px. */
    private fun composerGapPx(): Int {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        return (root.bottom - composer.bottom).toInt()
    }

    @Test
    fun keyboardOpenAppliesTheImeInsetExactlyOnce() {
        dispatchInsets(imeBottomPx = 600)
        val gap = composerGapPx()
        assertTrue(
            "the composer must clear the keyboard: gap ${gap}px must exceed the 600px IME inset " +
                "(the inset never landed, or the composer sits under the keyboard)",
            gap > 600
        )
        assertTrue(
            "the IME inset must be applied exactly once: gap ${gap}px must stay well under " +
                "the 1200px a double application would leave",
            gap < 900
        )
    }

    @Test
    fun keyboardClosedKeepsTheRestingPositionAboveTheNavigationBar() {
        dispatchInsets(imeBottomPx = 0)
        val gap = composerGapPx()
        assertTrue(
            "with no keyboard the composer rests on the navigation-bar inset: gap ${gap}px " +
                "must cover the 48px navigation bar",
            gap >= 48
        )
        assertTrue(
            "with no keyboard nothing else may lift the composer: gap ${gap}px must stay " +
                "near the resting 48px bar plus the row's own 12dp padding",
            gap < 200
        )
    }
}
