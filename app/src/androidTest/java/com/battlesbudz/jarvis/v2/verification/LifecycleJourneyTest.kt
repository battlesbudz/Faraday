package com.battlesbudz.jarvis.v2.verification

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.battlesbudz.jarvis.v2.MainActivity
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.voice.SpeechCaptureProfile
import com.battlesbudz.jarvis.v2.voice.VoiceCallService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** External adb phases deliberately kill the process; reconstruction inside one JVM is insufficient. */
@RunWith(AndroidJUnit4::class)
class LifecycleJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val fixture get() = context.getSharedPreferences("release_lifecycle_fixture", Context.MODE_PRIVATE)
    private val journalFile get() = File(context.noBackupFilesDir, "phone-action-attempts.json")

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java)).also {
        assertTrue("MainActivity must remain reachable", device.wait(Until.hasObject(By.res("model_browse")), 15_000))
    }

    @Suppress("DEPRECATION")
    private fun callService() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .singleOrNull { it.service.className == VoiceCallService::class.java.name }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    /** Real service/notification/wakelock lifecycle, no fake inference and no model download. */
    private fun startService(activity: ActivityScenario<MainActivity>) {
        activity.onActivity {
            // The inert non-control action avoids starting an unavailable native model.
            // onCreate still runs production foreground microphone/playback registration.
            it.startForegroundService(Intent(it, VoiceCallService::class.java).setAction("release-verification-hold"))
        }
        awaitCondition("Call service must be foreground", { callService()?.foreground == true })
    }

    private fun stopService() {
        context.stopService(Intent(context, VoiceCallService::class.java))
        awaitCondition("Call service must release its owner on stop", { callService() == null })
    }

    /** Host stops us while the *actual* volume effect has happened and its receipt has not. */
    @Test fun testProcessSeedAtUnrecordedEffect() {
        launch() // Keep the app UID foreground while the external controller arms its kill.
        val voicePreferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
        assertTrue(voicePreferences.edit().putString("capture_profile", "speech_preserving").commit())
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(context))
        // Flush the migration's apply before the external kill; recovery must read disk state.
        assertTrue(voicePreferences.edit().commit())
        assertEquals("communication_noise_filtered", voicePreferences.getString("capture_profile", null))
        journalFile.delete()
        val ledger = ToolTaskLedger(FileToolTaskStore(journalFile))
        val native = AndroidMobileActionExecutor(context)
        assertTrue(JournaledActionPipeline(ledger, native).execute(ActionRequest("read_battery")).succeeded)
        val completed = ledger.snapshot().single()
        val audio = context.getSystemService(AudioManager::class.java)
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue(fixture.edit().putInt("original_volume", original).putString("committed_id", completed.id)
            .putLong("committed_generation", completed.generation).putString("committed_result", completed.result).commit())
        val boundary = CountDownLatch(1)
        val neverFinish = CountDownLatch(1)
        val worker = Thread {
            JournaledActionPipeline(ledger, MobileActionExecutor { request ->
                val result = native.execute(request)
                check(result.succeeded)
                check(fixture.edit().putInt("expected_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .putString("running_id", ledger.snapshot().single { it.state == ToolTaskState.RUNNING }.id).commit())
                boundary.countDown()
                neverFinish.await() // External process death is the only release of this boundary.
                result
            }).execute(ActionRequest("set_volume", mapOf("level" to "40")))
        }.apply { isDaemon = true; start() }
        assertTrue("Real effect must reach unrecorded boundary", boundary.await(15, TimeUnit.SECONDS))
        assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(worker.isAlive)
        ReleasePhaseEvidence.capture("testProcessSeedAtUnrecordedEffect")
        instrumentation.sendStatus(1, Bundle().apply { putString("jarvisBoundary", "process_kill") })
        // Never return OK for an intentionally terminated test. A timeout fails the host gate.
        assertFalse("Controller did not kill the process", neverFinish.await(60, TimeUnit.SECONDS))
        fail("Controller failed to terminate the unfinished action")
    }

    @Test fun testProcessRecoveryPreservesReceiptsAndDoesNotRepeatEffect() {
        val voicePreferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
        assertEquals("Migrated profile must survive actual process death", "communication_noise_filtered",
            voicePreferences.getString("capture_profile", null))
        repeat(2) {
            assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(context))
        }
        launch().use {
            val ledger = ToolTaskLedger(FileToolTaskStore(journalFile))
            val runningId = checkNotNull(fixture.getString("running_id", null))
            // MainActivity initializes the production process runtime; do not manually recover.
            awaitCondition("Production restart must fence the unfinished action", {
                ledger.get(runningId)?.state == ToolTaskState.UNKNOWN_OUTCOME
            })
            val completed = checkNotNull(ledger.get(checkNotNull(fixture.getString("committed_id", null))))
            assertEquals(ToolTaskState.SUCCEEDED, completed.state)
            assertEquals(fixture.getLong("committed_generation", -1), completed.generation)
            assertEquals(fixture.getString("committed_result", null), completed.result)
            val audio = context.getSystemService(AudioManager::class.java)
            assertEquals(fixture.getInt("expected_volume", -1), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val pending = checkNotNull(ledger.get(runningId))
            assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, pending.resultOutcome)
            assertFalse(JournaledActionPipeline(ledger, AndroidMobileActionExecutor(context)).executeAttempt(pending).succeeded)
            assertEquals(fixture.getInt("expected_volume", -1), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(2, ledger.snapshot().size)
            ReleasePhaseEvidence.capture("testProcessRecoveryPreservesReceiptsAndDoesNotRepeatEffect")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, fixture.getInt("original_volume", 0), 0)
        }
    }

    @Test fun testMicrophoneDeniedLeavesSetupAndTextReachable() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        launch().use {
            assertNull("Denied microphone must not silently start a call", callService())
            assertNotNull(device.findObject(By.res("model_browse")))
            ReleasePhaseEvidence.capture("testMicrophoneDeniedLeavesSetupAndTextReachable")
        }
    }

    @Test fun testLivePermissionSeedForegroundService() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val activity = launch()
        startService(activity)
        assertTrue(fixture.edit().putBoolean("live_service_started", true).commit())
        ReleasePhaseEvidence.capture("testLivePermissionSeedForegroundService")
        instrumentation.sendStatus(1, Bundle().apply { putString("jarvisBoundary", "permission_revoke") })
        // Revocation must occur while this real foreground service and process are alive.
        SystemClock.sleep(60_000)
        fail("Controller did not revoke microphone during the foreground service")
    }

    @Test fun testRevokedMicrophoneReopensWithoutActiveService() {
        assertTrue(fixture.getBoolean("live_service_started", false))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        launch().use {
            assertNull("Revocation must stop the prior microphone service", callService())
            ReleasePhaseEvidence.capture("testRevokedMicrophoneReopensWithoutActiveService")
        }
    }

    @Test fun testRegrantedMicrophoneStartsAndStopsForegroundService() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        launch().use { activity ->
            try { startService(activity); ReleasePhaseEvidence.capture("testRegrantedMicrophoneStartsAndStopsForegroundService") }
            finally { stopService() }
        }
    }

    @Test fun testNotificationDeniedStillAllowsVisibleUserStartedService() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        // API 29-32 have no runtime notification permission. Their real service lifecycle
        // still runs; the controller records platform-not-applicable, never a skipped test.
        launch().use { activity ->
            try {
                startService(activity)
                assertTrue(callService()?.foreground == true)
                ReleasePhaseEvidence.capture("testNotificationDeniedStillAllowsVisibleUserStartedService")
            } finally { stopService() }
        }
    }

    @Test fun testNotificationRegrantedPublishesServiceControls() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        launch().use { activity ->
            try {
                startService(activity)
                val manager = context.getSystemService(NotificationManager::class.java)
                awaitCondition("Foreground service notification must be published", { manager.activeNotifications.any { it.id == 481 } })
                val notification = manager.activeNotifications.single { it.id == 481 }.notification
                assertEquals(listOf("Pause mic", "Stop session"), notification.actions.map { it.title.toString() })
                assertNotNull(notification.contentIntent)
                ReleasePhaseEvidence.capture("testNotificationRegrantedPublishesServiceControls")
                // Exercise the production notification PendingIntent, rather than stopService.
                notification.actions.single { it.title.toString() == "Stop session" }.actionIntent.send()
                awaitCondition("Notification Stop must remove the service", { callService() == null })
                awaitCondition("Notification Stop must remove ongoing notification", { manager.activeNotifications.none { it.id == 481 } })
            } finally { stopService() }
        }
    }
}
