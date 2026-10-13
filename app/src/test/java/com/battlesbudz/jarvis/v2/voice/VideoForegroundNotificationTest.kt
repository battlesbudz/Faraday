package com.battlesbudz.jarvis.v2.voice

import java.io.File
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/**
 * Controlled AOSP queue schedule, not a replay of build 1240. AMS admits service
 * token updates and queues ServiceRecord post/cancel on one handler; NMS has its
 * own queue and an app cancel cannot remove an FGS-flagged record. Both API 30
 * and Android 16 source anchors are retained in the verification note.
 */
class VideoForegroundNotificationTest {
    private companion object {
        const val CAMERA = 64
        const val SPECIAL_USE = 1 shl 30
        const val VIDEO = 482
        const val AUDIO = 481
    }

    private data class Record(val status: String, val fgs: Boolean)

    private class QueuedPlatform {
        val ams = ArrayDeque<() -> Unit>()
        val nms = ArrayDeque<() -> Unit>()
        val records = mutableMapOf(AUDIO to Record("Audio on", true))
        val acceptedTypes = mutableListOf<Int>()
        var token: Any? = null
        var foreground = false
        var rejectNext: RuntimeException? = null
        var admittedUpdates = 0
        var appCancels = 0
        var systemCancels = 0

        fun create(serviceToken: Any) { token = serviceToken }

        fun startForeground(serviceToken: Any, status: String, type: Int) {
            // findServiceLocked requires the matching component AND token.
            // A stopped or replaced token is a no-op, not a new service start.
            if (token !== serviceToken) return
            rejectNext?.let { rejectNext = null; throw it }
            acceptedTypes += type
            admittedUpdates++
            foreground = true
            ams.addLast { nms.addLast { records[VIDEO] = Record(status, true) } }
        }

        fun removeForeground(serviceToken: Any) {
            if (token !== serviceToken || !foreground) return
            foreground = false
            ams.addLast { nms.addLast { systemCancels++; records.remove(VIDEO) } }
        }

        fun systemStop(serviceToken: Any) {
            removeForeground(serviceToken)
            if (token === serviceToken) token = null
        }

        fun appCancel() {
            nms.addLast {
                appCancels++
                if (records[VIDEO]?.fgs != true) records.remove(VIDEO)
            }
        }

        fun legacyNotify(status: String, afterFgsPolicy: () -> Unit) {
            // NMS checks foreground policy before it queues the enqueue work.
            val fgs = foreground
            afterFgsPolicy()
            nms.addLast { records[VIDEO] = Record(status, fgs) }
        }

        fun drain() {
            while (ams.isNotEmpty() || nms.isNotEmpty()) {
                if (ams.isNotEmpty()) ams.removeFirst().invoke()
                if (nms.isNotEmpty()) nms.removeFirst().invoke()
            }
        }
    }

    private class Owner(val platform: QueuedPlatform, cameraGranted: Boolean = true) {
        val token = Any()
        val foreground = VideoForegroundNotification(CAMERA, SPECIAL_USE) { status, type ->
            platform.startForeground(token, status, type)
        }
        val main = ArrayDeque<() -> Unit>()
        var cleanups = 0
        var stops = 0
        val lifecycle = VideoNotificationLifecycle(
            post = foreground::update,
            removeForeground = { platform.removeForeground(token) },
            cancelNotification = {
                if (platform.token == null || platform.token === token) platform.appCancel()
            },
            onPostRejected = {
                main.addLast {
                    lifecycleClose()
                    stops++
                    platform.systemStop(token)
                }
            },
        )

        init {
            platform.create(token)
            foreground.start("Video on", cameraGranted)
        }

        fun lifecycleClose() { lifecycle.close { cleanups++ } }
    }

    @Test fun legacyDirectNotifyCanSurviveSystemAndExplicitAppCancellation() {
        val platform = QueuedPlatform()
        val owner = Owner(platform)
        platform.drain()
        val legacy = VideoNotificationLifecycle(
            post = { status ->
                platform.legacyNotify(status) {
                    platform.systemStop(owner.token)
                    // Run the system cancellation's AMS hop before the paused
                    // direct notify reaches NMS. The queues remain independent.
                    platform.ams.removeFirst().invoke()
                }
            },
            removeForeground = { platform.removeForeground(owner.token) },
            cancelNotification = platform::appCancel,
        )
        assertTrue(legacy.publish("Video idle"))
        legacy.close {}
        platform.drain()
        assertEquals("The old route leaves an FGS-flagged orphan", Record("Video idle", true), platform.records[VIDEO])
        assertEquals(1, platform.systemCancels)
        assertEquals(1, platform.appCancels)
        assertFalse(legacy.publish("late callback"))
    }

    @Test fun tokenUpdatesBeforeStopDrainBeforeSystemCancellationForEveryQueueSchedule() {
        // Exhaust every 8-step choice of which FIFO handler to advance; drain
        // the remaining work afterward. No scheduler sleeps or Android timings.
        for (schedule in 0 until 256) {
            val platform = QueuedPlatform()
            val owner = Owner(platform)
            platform.drain()
            assertTrue(owner.lifecycle.publish("Video idle"))
            platform.systemStop(owner.token)
            owner.lifecycleClose()
            repeat(8) { bit ->
                val queue = if (schedule and (1 shl bit) == 0) platform.ams else platform.nms
                if (queue.isNotEmpty()) queue.removeFirst().invoke()
            }
            platform.drain()
            assertNull("Orphan after queue schedule $schedule", platform.records[VIDEO])
            assertEquals(Record("Audio on", true), platform.records[AUDIO])
            assertEquals(listOf(CAMERA, CAMERA), platform.acceptedTypes)
            assertEquals(1, owner.cleanups)
        }
    }

    @Test fun updateAfterSystemStopCannotAdmitNewNotificationOrResurrectService() {
        val platform = QueuedPlatform()
        val owner = Owner(platform)
        platform.drain()
        platform.systemStop(owner.token)
        // App onDestroy has not arrived, so its local owner is still open.
        // AMS rejects the obsolete token without needing an app timing guess.
        assertTrue(owner.lifecycle.publish("Video idle"))
        owner.lifecycleClose()
        platform.drain()
        assertNull(platform.records[VIDEO])
        assertNull(platform.token)
        assertEquals(1, platform.admittedUpdates)
        assertFalse(owner.lifecycle.publish("late farewell"))
        owner.lifecycleClose()
        assertEquals(1, owner.cleanups)
    }

    @Test fun oldTokenAndDuplicateCallbacksCannotOverwriteOrCancelReplacement() {
        val platform = QueuedPlatform()
        val old = Owner(platform)
        platform.drain()
        assertTrue(old.lifecycle.publish("old idle"))
        platform.systemStop(old.token)
        val replacement = Owner(platform)
        assertTrue(old.lifecycle.publish("obsolete token"))
        old.lifecycleClose()
        old.lifecycleClose()
        assertFalse(old.lifecycle.publish("closed callback"))
        assertTrue(replacement.lifecycle.publish("new Video on"))
        platform.drain()
        assertSame(replacement.token, platform.token)
        assertEquals(Record("new Video on", true), platform.records[VIDEO])
        assertEquals(0, platform.appCancels)
        assertEquals(1, old.cleanups)
        replacement.lifecycleClose()
        platform.drain()
        assertNull(platform.records[VIDEO])
    }

    @Test fun appOwnedStopClosesBeforeQueuedStartOrCallback() {
        val platform = QueuedPlatform()
        val owner = Owner(platform)
        platform.drain()
        owner.lifecycleClose() // STOP/task removal/fail-safe decision, before stopSelf
        assertFalse(owner.lifecycle.isOpen)
        if (owner.lifecycle.isOpen) fail("Queued capture cannot be admitted after terminal stop")
        assertFalse(owner.lifecycle.publish("queued Video on"))
        platform.systemStop(owner.token)
        owner.lifecycleClose() // eventual onDestroy
        platform.drain()
        assertNull(platform.records[VIDEO])
        assertEquals(1, platform.admittedUpdates)
        assertEquals(1, owner.cleanups)
    }

    @Test fun acceptedCameraTypeIsReusedAndPermissionLossRemovesCaptureEligibility() {
        val platform = QueuedPlatform()
        val owner = Owner(platform)
        assertTrue(owner.foreground.canCapture(true))
        assertFalse(owner.foreground.canCapture(false))
        assertTrue(owner.lifecycle.publish("idle"))
        assertEquals(listOf(CAMERA, CAMERA), platform.acceptedTypes)
    }

    @Test fun deniedCameraStartupNeverPromotesAfterLaterPermissionGrant() {
        val platform = QueuedPlatform()
        val owner = Owner(platform, cameraGranted = false)
        assertFalse(owner.foreground.canCapture(false))
        assertFalse(owner.foreground.canCapture(true))
        assertTrue(owner.lifecycle.publish("audio-only"))
        assertEquals(listOf(SPECIAL_USE, SPECIAL_USE), platform.acceptedTypes)
    }

    @Test fun rejectedCameraStartupRemembersOnlyAcceptedFallbackType() {
        val platform = QueuedPlatform()
        platform.rejectNext = SecurityException("camera background restriction")
        val owner = Owner(platform)
        assertFalse(owner.foreground.canCapture(true))
        assertTrue(owner.lifecycle.publish("audio-only"))
        assertEquals(listOf(SPECIAL_USE, SPECIAL_USE), platform.acceptedTypes)
    }

    @Test fun rejectedFallbackDoesNotCreateAnAcceptedTypeOrRetryIt() {
        var attempts = 0
        val foreground = VideoForegroundNotification(CAMERA, SPECIAL_USE) { _, _ ->
            attempts++
            throw SecurityException("denied")
        }
        assertThrows(SecurityException::class.java) { foreground.start("idle", false) }
        assertEquals(1, attempts)
        assertFalse(foreground.canCapture(true))
        assertThrows(IllegalStateException::class.java) { foreground.update("Video on") }
        assertEquals(1, attempts)
    }

    @Test fun laterUpdateRejectionRevokesBeforeQueuedCleanupAndNeverRetriesOrFallsBack() {
        for (rejection in listOf(
            SecurityException("camera revoked"),
            IllegalStateException("foreground start refused"),
            IllegalArgumentException("foreground type rejected"),
            RuntimeException("system-server runtime rejection"),
        )) {
            val platform = QueuedPlatform()
            val owner = Owner(platform)
            platform.drain()
            platform.rejectNext = rejection
            assertFalse(owner.lifecycle.publish("Video on"))
            assertFalse(owner.lifecycle.isOpen)
            assertFalse(owner.lifecycle.publish("duplicate camera callback"))
            assertFalse(owner.lifecycle.publish("late farewell"))
            assertEquals(1, owner.main.size)
            assertEquals(0, owner.cleanups)
            owner.main.removeFirst().invoke()
            owner.lifecycleClose() // onDestroy after the rejection cleanup
            platform.drain()
            assertEquals(1, owner.cleanups)
            assertEquals(1, owner.stops)
            assertNull(platform.records[VIDEO])
            assertEquals(listOf(CAMERA), platform.acceptedTypes)
            assertEquals(Record("Audio on", true), platform.records[AUDIO])
        }
    }

    @Test fun productionServiceWiresTheTestedOwnersToOnlyTheTypedForegroundRoute() {
        // Keep the asynchronous model attached to the shipping adapter. This
        // fails if that adapter goes back to notify, uses manifest-default FGS
        // types, bypasses the tested owner, or drops accepted-type admission.
        val relative = "src/main/java/com/battlesbudz/jarvis/v2/voice/VideoCallService.kt"
        val source = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Cannot locate shipping VideoCallService source")
        val code = source.readText().replace(Regex("(?s)/\\*.*?\\*/|//[^\\n]*"), "")
            .replace(Regex("\\s+"), "")
        assertTrue(code.contains("post=foregroundNotification::update,"))
        assertTrue(code.contains("startForeground={nextStatus,acceptedType->startForeground(NOTIFICATION_ID,notification(nextStatus),acceptedType)"))
        assertEquals(1, Regex("startForeground\\(").findAll(code).count())
        assertFalse(code.contains(".notify("))
        assertTrue(code.contains("foregroundNotification.start(status,cameraGranted)"))
        assertTrue(code.contains("notificationLifecycle.isOpen&&!callId.isNullOrBlank()&&captureStarts.admit"))
        assertTrue(code.contains("foregroundNotification.canCapture(CameraPermission.isGranted(this))"))
        assertTrue(code.contains("scope.launch{stopVideo()}"))
        assertTrue(code.contains("STOP->stopVideo()"))
        assertTrue(code.contains("if(stop)stopVideo()"))
        assertTrue(code.contains("overridefunonTaskRemoved(rootIntent:Intent?){stopVideo()}"))
        assertTrue(code.contains("privatefunstopVideo(){closeVideo()try{stopSelf()}catch"))
        assertTrue(code.contains("catch(failure:RuntimeException){android.util.Log.e"))
        assertTrue(code.contains("CallVisionRegistry.controller===controller"))
    }
}
