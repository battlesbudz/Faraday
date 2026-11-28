package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.Manifest
import android.provider.Settings
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.Shadows.shadowOf
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
        Settings.Global.putInt(bound.contentResolver, "development_settings_enabled", 1)
        Settings.Global.putInt(bound.contentResolver, "adb_enabled", 1)
        Settings.Global.putInt(bound.contentResolver, "adb_wifi_enabled", 1)
        shadowOf(bound as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PairingIdentity.initialize(bound)
        // Pin the supervisor singleton to the bound context when this
        // class creates it first, so composition-created instances in
        // the wiring test and this class's reads/writes share one store.
        // The get() below only pins the singleton when this class creates
        // it first. If another test class or method created it earlier in
        // this sandbox, it is pinned to that captured Application's store
        // and can never observe this class's pairing writes (the status
        // poll then fails durably, however long it runs). Reset both
        // pairing singletons so the get() below re-creates the supervisor
        // bound to this class's store — the one every write in this
        // class lands in.
        resetCompanionSingleton(PrivilegeSupervisor::class.java)
        resetCompanionSingleton(PairingSetupController::class.java)
        PrivilegeSupervisor.get(bound)
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

    /** Clears a Kotlin companion-object singleton so the next get()
     * re-creates it under this class's bound context. The compiler places
     * a private companion property's backing field either on the companion
     * instance or as a private static on the owner class, under a
     * compiler-chosen name — so locate every field of the owner's own
     * type in both holders and clear it. */
    private fun resetCompanionSingleton(owner: Class<*>) {
        fun clear(holder: Any?, fields: Array<java.lang.reflect.Field>) {
            fields.filter { it.type == owner }.forEach { field ->
                field.isAccessible = true
                field.set(holder, null)
            }
        }
        clear(null, owner.declaredFields)
        val companion = owner.declaredFields
            .firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type.name.contains("Companion") }
            ?.apply { isAccessible = true }?.get(null)
        if (companion != null) clear(companion, companion.javaClass.declaredFields)
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
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultOwner) {
                MaterialTheme {
                    // Scrollable host: the section is taller than the
                    // Robolectric surface, and the Revoke button below the
                    // fold did not receive performClick in run 45368112213
                    // (Start/Manual, above the fold, worked; the supervisor
                    // never registered the revocation).
                    androidx.compose.foundation.layout.Column(
                        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    ) {
                        AppControlPairingSection(enabled = true, controller = controller)
                    }
                }
            }
        }
        compose.onNodeWithTag("app_control_pairing_start").performClick()
        compose.onNodeWithText("Waiting for the pairing dialog…").assertExists()
        compose.onNodeWithTag("app_control_pairing_manual_toggle").performClick()
        compose.onNodeWithTag("app_control_pairing_code").performTextInput("123456")
        compose.onNodeWithTag("app_control_pairing_manual").performClick()
        compose.waitUntil(5_000) { transport.calls.isNotEmpty() && controller.state.value.lastMessage?.startsWith("Paired.") == true }
        // The supervisor must now see the pairing (paired + consented,
        // helper not yet attached): its blocked reason is the consented-
        // recovery one. Poll the supervisor directly and keep its reason
        // in the failure message — a raw Binder()'s ping semantics under
        // Robolectric are not a reliable stand-in for helper liveness,
        // so this test does not fake a live helper.
        val pairedDeadline = System.currentTimeMillis() + 5_000
        var pairedStatus = controller.status()
        while (pairedStatus.reason?.contains("did not establish") != true && System.currentTimeMillis() < pairedDeadline) {
            Thread.sleep(50)
            pairedStatus = controller.status()
        }
        assertTrue("supervisor never registered the pairing: ${pairedStatus.reason}",
            pairedStatus.reason?.contains("did not establish") == true)
        compose.waitForIdle()
        compose.onNodeWithTag("app_control_pairing_status").assertTextContains("Faraday couldn't start the helper", substring = true)
        compose.onNodeWithTag("app_control_pairing_revoke").performScrollTo().performClick()
        // Poll the supervisor until the revocation registers (reason in
        // the failure message), then poll the status TEXT until the
        // recomposition lands — a single waitForIdle proved too weak a
        // sync for the click-thread state change under Robolectric
        // (run 45361671230: supervisor-side proof passed, text stale).
        val revokeDeadline = System.currentTimeMillis() + 5_000
        var revokedStatus = controller.status()
        while (revokedStatus.reason != "wireless-debugging pairing is absent or revoked" && System.currentTimeMillis() < revokeDeadline) {
            Thread.sleep(50)
            revokedStatus = controller.status()
        }
        assertEquals("wireless-debugging pairing is absent or revoked", revokedStatus.reason)
        compose.waitUntil(5_000) {
            val node = compose.onNodeWithTag("app_control_pairing_status").fetchSemanticsNode()
            val text = node?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                ?.joinToString("") { it.text }.orEmpty()
            text.contains("pairing was revoked")
        }
        assertFalse(prefs.getBoolean(PrivilegeSupervisor.CONSENT, true))
        assertFalse(prefs.getBoolean("adb_pairing_complete", true))
    }

    @Test fun connectedStateShowsSetupMessageWithoutWizardSteps() {
        val controller = PairingSetupController(
            context = bound,
            supervisor = PrivilegeSupervisor.get(bound),
            discovery = { AdbEndpoint("192.168.0.12", 5555) },
            transportFactory = { RecordingTransport() },
            windowTexts = { emptyList() },
            notifier = RecordingNotifier(),
        )
        assertTrue(controller.submitManual("123456"))
        compose.waitUntil(5_000) { controller.state.value.lastMessage?.startsWith("Paired.") == true }
        PrivilegeHandoffState.helper.set(android.os.Binder())
        compose.waitUntil(5_000) { controller.status().present }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultOwner) {
                MaterialTheme { AppControlPairingSection(enabled = true, controller = controller) }
            }
        }
        compose.onNodeWithText("Phone control is set up").assertExists()
        compose.onNodeWithText("Helper status: connected.").assertExists()
        compose.onNodeWithText("Pair again").assertExists()
        compose.onNodeWithText("1. Developer options").assertDoesNotExist()
    }

    @Test fun manualEntryStartsCollapsedAndPortIsHiddenOnceDialogIsFound() {
        val controller = PairingSetupController(
            context = bound,
            supervisor = PrivilegeSupervisor.get(bound),
            discovery = { null },
            transportFactory = { RecordingTransport() },
            windowTexts = { listOf(listOf("Pair device with pairing code", "123456")) },
            notifier = RecordingNotifier(),
            pollIntervalMs = 20,
            armedWindowMs = 2_000,
            autoReadGraceMs = 20,
        )
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultOwner) {
                MaterialTheme { AppControlPairingSection(enabled = true, controller = controller) }
            }
        }
        compose.onNodeWithTag("app_control_pairing_code").assertDoesNotExist()
        compose.onNodeWithTag("app_control_pairing_manual_toggle").performClick()
        compose.onNodeWithTag("app_control_pairing_code").assertExists()
        compose.onNodeWithTag("app_control_pairing_port").assertExists()
        controller.arm()
        compose.waitUntil(5_000) { controller.state.value.dialogFound }
        compose.onNodeWithTag("app_control_pairing_port").assertDoesNotExist()
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
