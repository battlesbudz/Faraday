package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingSetupControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var bound: Context
    private val prefs get() = bound.getSharedPreferences("app_control_privilege", Context.MODE_PRIVATE)
    private lateinit var supervisor: PrivilegeSupervisor

    @Before fun setUp() {
        // Robolectric gives each test method a fresh Application, while
        // PrivilegeSupervisor and PairingIdentity are process singletons
        // bound to the first context they saw: the supervisor writes
        // through its creation context, storePeerInfo through the latest
        // initialize() context. Bind every read/write in this class to
        // one captured application context — the device reality of
        // exactly one Application — or the writes and this class's
        // prefs reads land in different stores.
        bound = boundContext ?: context.applicationContext.also { boundContext = it }
        prefs.edit().clear().commit()
        PairingIdentity.initialize(bound)
        PrivilegeHandoffState.helper.set(null)
        supervisor = PrivilegeSupervisor.get(bound)
    }

    companion object {
        @Volatile private var boundContext: Context? = null
    }

    @After fun tearDown() {
        PrivilegeHandoffState.helper.set(null)
        prefs.edit().clear().commit()
    }

    @Test fun readerPairsTheCodeAndPortFromOneSettingsWindowThenDisarms() {
        val transport = RecordingTransport()
        val notifier = RecordingNotifier()
        val controller = controller(transport, notifier) {
            listOf(listOf("Pair device with pairing code", "123456", "IP address & port", "192.168.0.12:37123", "Other text"))
        }
        controller.arm()
        await { transport.calls.size == 1 && !controller.state.value.armed }

        assertEquals(RecordingTransport.Call("123456", AdbEndpoint("192.168.0.12", 37123)), transport.calls.single())
        assertTrue(prefs.getBoolean(PrivilegeSupervisor.CONSENT, false))
        assertTrue(prefs.getBoolean("adb_pairing_complete", false))
        assertFalse(controller.state.value.armed)
        assertEquals("Paired. The app-control helper can now start.", controller.state.value.lastMessage)
    }

    @Test fun missingAccessibilityPostsNotificationAndManualCodePairs() {
        val noAccessTransport = RecordingTransport()
        val noAccessNotifier = RecordingNotifier()
        val noAccessController = controller(noAccessTransport, noAccessNotifier) { emptyList() }
        noAccessController.arm()
        await { noAccessNotifier.enterCodes.isNotEmpty() }
        Thread.sleep(80)
        assertTrue(noAccessTransport.calls.isEmpty())
        noAccessController.disarm()

        prefs.edit().putBoolean("adb_pairing_complete", false).putBoolean(PrivilegeSupervisor.CONSENT, false).commit()
        val manualTransport = RecordingTransport()
        val manualController = controller(manualTransport, RecordingNotifier()) { emptyList() }
        assertTrue(manualController.submitManual("123456"))
        await { manualTransport.calls.isNotEmpty() && manualController.state.value.lastMessage?.startsWith("Paired.") == true }
        assertEquals("123456", manualTransport.calls.single().code)
        assertTrue(prefs.getBoolean(PrivilegeSupervisor.CONSENT, false))
        assertTrue(prefs.getBoolean("adb_pairing_complete", false))
    }

    @Test fun rejectedReaderCodeIsTriedOnlyOnceAndReasonIsShownAgain() {
        val transport = RecordingTransport("Wireless-debugging pairing code was rejected.")
        val notifier = RecordingNotifier()
        val controller = controller(transport, notifier) { listOf(listOf("123456", "192.168.0.12:37123")) }
        controller.arm()
        await { controller.state.value.lastMessage == "Wireless-debugging pairing code was rejected." }
        Thread.sleep(160)
        assertEquals(1, transport.calls.size)
        assertTrue(notifier.enterCodes.contains("Wireless-debugging pairing code was rejected."))
    }

    @Test fun dialogFoundTurnsOnForCodeOrPortSeenInSettingsWindow() {
        var texts = listOf(listOf("Pair device with pairing code", "123456"))
        val controller = controller(RecordingTransport(), RecordingNotifier()) { texts }
        controller.arm()
        await { controller.state.value.dialogFound }
        texts = emptyList()
        controller.disarm()
        assertTrue(controller.state.value.dialogFound)
        controller.arm()
        assertFalse(controller.state.value.dialogFound)
        Thread.sleep(60)
        assertFalse(controller.state.value.dialogFound)
        controller.disarm()
    }

    @Test fun manualCodeUsesDiscoveredHostAndEnteredPort() {
        val transport = RecordingTransport()
        val controller = PairingSetupController(
            context = bound,
            supervisor = supervisor,
            discovery = { AdbEndpoint("192.168.0.12", 5555) },
            transportFactory = { transport },
            windowTexts = { emptyList() },
            notifier = RecordingNotifier(),
        )
        assertTrue(controller.submitManualCode("123456", 37123))
        await { transport.calls.isNotEmpty() }
        assertEquals(AdbEndpoint("192.168.0.12", 37123), transport.calls.single().endpoint)
    }

    @Test fun manualPortWithoutDiscoveredHostKeepsCurrentDiscoveryFailure() {
        val transport = RecordingTransport()
        val controller = PairingSetupController(
            context = bound,
            supervisor = supervisor,
            discovery = { null },
            transportFactory = { transport },
            windowTexts = { emptyList() },
            notifier = RecordingNotifier(),
        )
        assertTrue(controller.submitManualCode("123456", 37123))
        await { controller.state.value.lastMessage?.startsWith("Faraday hasn't found the pairing dialog yet") == true }
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun invalidManualCodeNeverCallsTransportAndRevokeClearsPairingAndConsent() {
        val transport = RecordingTransport()
        val controller = controller(transport, RecordingNotifier()) { emptyList() }
        assertFalse(controller.submitManual("12345"))
        assertTrue(transport.calls.isEmpty())
        assertEquals("That is not the 6-digit code from the pairing dialog.", controller.state.value.lastMessage)

        assertTrue(controller.submitManual("123456"))
        await { controller.state.value.lastMessage?.startsWith("Paired.") == true }
        controller.revoke()
        assertFalse(prefs.getBoolean(PrivilegeSupervisor.CONSENT, true))
        assertFalse(prefs.getBoolean("adb_pairing_complete", true))
        assertFalse(supervisor.status().present)
        assertEquals("wireless-debugging pairing is absent or revoked", supervisor.status().reason)
    }

    private fun controller(
        transport: RecordingTransport,
        notifier: RecordingNotifier,
        windowTexts: () -> List<List<String>>,
    ) = PairingSetupController(
        context = bound,
        supervisor = supervisor,
        discovery = { AdbEndpoint("192.168.0.12", 5555) },
        transportFactory = { transport },
        windowTexts = windowTexts,
        notifier = notifier,
        pollIntervalMs = 25,
        armedWindowMs = 2_000,
        autoReadGraceMs = 50,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 4_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition was not reached before timeout", condition())
    }

    private class RecordingTransport(private val failure: String? = null) : WirelessDebuggingPairingTransport {
        data class Call(val code: String, val endpoint: AdbEndpoint)
        val calls = CopyOnWriteArrayList<Call>()
        override fun pair(endpoint: AdbEndpoint, pairingCode: CharArray): String {
            calls += Call(String(pairingCode), endpoint)
            failure?.let { throw PairingTransportException(it) }
            return "guid-1"
        }
    }

    private class RecordingNotifier : PairingSetupNotifier {
        val enterCodes = CopyOnWriteArrayList<String?>()
        override fun showEnterCode(error: String?) { enterCodes += error }
        override fun showWorking() = Unit
        override fun showDone() = Unit
        override fun showProblem(reason: String) = Unit
        override fun cancel() = Unit
    }
}
