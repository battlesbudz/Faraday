package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.os.Binder
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import com.battlesbudz.jarvis.v2.privilege.*
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppControlPairingSectionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var bound: Context
    private val prefs get() = bound.getSharedPreferences("app_control_privilege", Context.MODE_PRIVATE)

    private val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = Unit
    }
    private val activityResultOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this@AppControlPairingSectionTest.activityResultRegistry
    }

    @Before fun setUp() {
        // Same singleton-binding discipline as PairingSetupControllerTest:
        // one captured application context for every store read/write in
        // this class, matching the single-Application device reality.
        bound = boundContext ?: context.applicationContext.also { boundContext = it }
        prefs.edit().clear().commit()
        PairingIdentity.initialize(bound)
        PrivilegeHandoffState.helper.set(null)
    }

    companion object {
        @Volatile private var boundContext: Context? = null
    }

    @After fun tearDown() {
        PrivilegeHandoffState.helper.set(null)
        prefs.edit().clear().commit()
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
    }

    @Test fun realVoiceCallSettingsDialogContainsThePairingEntryPoint() {
        mount()
        compose.onNodeWithTag("app_control_pairing_status").assertExists().assertTextContains("Helper status: not connected", substring = true)
    }

    @Test fun pairingControlsDrivePairingConsentAndRevocation() {
        val transport = RecordingTransport()
        val controller = PairingSetupController(
            context = bound,
            supervisor = PrivilegeSupervisor.get(bound),
            discovery = { AdbEndpoint("192.168.0.12", 5555) },
            transportFactory = { transport },
            windowTexts = { emptyList() },
            notifier = RecordingNotifier(),
            pollIntervalMs = 20,
            armedWindowMs = 2_000,
            autoReadGraceMs = 20,
        )
        compose.setContent {
            MaterialTheme { AppControlPairingSection(enabled = true, controller = controller) }
        }
        compose.onNodeWithTag("app_control_pairing_start").performClick()
        compose.onNodeWithText("Waiting for the pairing dialog…").assertExists()
        compose.onNodeWithTag("app_control_pairing_code").performTextInput("123456")
        compose.onNodeWithTag("app_control_pairing_manual").performClick()
        compose.waitUntil(5_000) { transport.calls.isNotEmpty() && controller.state.value.lastMessage?.startsWith("Paired.") == true }
        PrivilegeHandoffState.helper.set(Binder())
        compose.waitUntil(5_000) { controller.status().present }
        controller.arm()
        controller.disarm()
        compose.waitForIdle()
        compose.onNodeWithTag("app_control_pairing_status").assertTextContains("Helper status: connected", substring = true)
        compose.onNodeWithTag("app_control_pairing_revoke").performClick()
        compose.onNodeWithTag("app_control_pairing_status").assertTextContains("Helper status: not connected", substring = true)
        assertFalse(prefs.getBoolean(PrivilegeSupervisor.CONSENT, true))
        assertFalse(prefs.getBoolean("adb_pairing_complete", true))
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultOwner) {
                MaterialTheme {
                    VoiceCallScreen(
                        visible = true,
                        settingsOpen = true,
                        memoryOpen = false,
                        onDismissSettings = {},
                        startRequest = 0,
                        chatBusy = MutableStateFlow(false),
                        modelSelector = {},
                        resumedCall = null,
                        onResumeConsumed = {},
                        voicePlayback = MutableStateFlow(VoicePlaybackFrame()),
                        onVoiceTurn = { _, _, _, _ -> },
                        onEndVoiceCall = {},
                        onCopyDiagnostics = {},
                        callEvidenceActions = CallEvidenceActions({}, {}, { CallEvidenceSnapshot("", null) }),
                    )
                }
            }
        }
    }

    private class RecordingTransport : WirelessDebuggingPairingTransport {
        val calls = CopyOnWriteArrayList<String>()
        override fun pair(endpoint: AdbEndpoint, pairingCode: CharArray): String {
            calls += String(pairingCode)
            return "guid-ui"
        }
    }

    private class RecordingNotifier : PairingSetupNotifier {
        override fun showEnterCode(error: String?) = Unit
        override fun showWorking() = Unit
        override fun showDone() = Unit
        override fun showProblem(reason: String) = Unit
        override fun cancel() = Unit
    }
}
