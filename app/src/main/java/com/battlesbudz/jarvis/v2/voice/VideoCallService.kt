package com.battlesbudz.jarvis.v2.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.battlesbudz.jarvis.v2.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Companion to [VoiceCallService]: holds the camera foreground-service type
 * and runs the frame-capture pipeline while a call is active.
 *
 * Audio stays entirely in VoiceCallService — this service never touches the
 * microphone, TTS, or audio routing. Lifecycle: capture starts per call via
 * [startCapture] (the call's identity owns the capture) and ends when that
 * call ends, by its own Stop action, by [onTaskRemoved], or automatically
 * when the voice session ends (it observes VoiceCallService.stopRequested
 * as a fail-safe). Capture never runs for a merely-armed wake session: a
 * spoken farewell ends the call's capture while wake listening stays armed.
 *
 * If the camera permission is denied, the service still runs (so Stop works)
 * but never binds the camera: the call degrades to audio-only.
 */
class VideoCallService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: CallVisionController
    private var status = "Video idle — starts with your next call"
    private val foregroundNotification = VideoForegroundNotification(
        cameraType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        fallbackType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        startForeground = { nextStatus, acceptedType ->
            startForeground(NOTIFICATION_ID, notification(nextStatus), acceptedType)
            status = nextStatus
        },
    )
    private val notificationLifecycle = VideoNotificationLifecycle(
        post = foregroundNotification::update,
        removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
        cancelNotification = {
            // Retain explicit final removal after revoking publication. Live
            // updates use the foreground token route, never raw notify: app
            // cancellation cannot remove an in-flight FGS-flagged notification.
            // Service creation/destruction and this removal are serialized
            // on Android's main thread. A retired instance must never cancel
            // the same ID after ownership has passed to a replacement.
            if (instance == null || instance === this) {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        },
        onPostRejected = { failure ->
            android.util.Log.e("JarvisVideo", "Video foreground update rejected; stopping video", failure)
            // Main (not Main.immediate) queues this outside camera callbacks and
            // their controller locks. Only this video's scope/service is stopped.
            scope.launch { stopVideo() }
        },
    )

    override fun onCreate() {
        super.onCreate()
        // startForeground() FIRST, before any pipeline construction: the
        // system kills the whole process when a startForegroundService()
        // start is not followed by startForeground() in time
        // (ForegroundServiceDidNotStartInTimeException — the test49 CI
        // crash). Camera-pipeline construction must never be able to delay
        // it; VoiceCallService follows the same order. Only the cheap
        // permission check runs first, to select the foreground type.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Video", NotificationManager.IMPORTANCE_LOW)
        )
        // The camera FGS type requires the CAMERA runtime permission on API 34+
        // (targetSdk 35): starting it without the grant throws SecurityException
        // out of onCreate and kills the whole process. When denied, fall back to
        // the specialUse type declared in the manifest, so the service still runs
        // (Stop keeps working) and the call degrades to audio-only.
        //
        // The grant check can also lie: Android documents that background
        // camera-FGS creation may still throw SecurityException even when the
        // check reports granted. Catch that too and degrade instead of dying.
        val cameraGranted = CameraPermission.isGranted(this)
        foregroundNotification.start(status, cameraGranted)

        val hub = VisionFrameHub()
        val cadence = FrameCadence()
        val binder = CameraXVideoBinder(this, this, hub, cadence, onVideoError = { error ->
            controller.degrade(error)
            notifyChanged("Camera unavailable — continuing audio-only")
        }, onDetachFailure = { cause ->
            // A failed or timed-out camera detach must never be silently
            // treated as successful cleanup: log it and say so on the
            // service status so a wedged capture is visible.
            android.util.Log.e("JarvisVideo", "Camera detach failed during teardown", cause)
            notifyChanged("Camera cleanup failed — video may misbehave until the next call")
        })
        controller = CallVisionController(binder, cadence, hub)
        CallVisionRegistry.hub = hub
        CallVisionRegistry.controller = controller

        // Fail-safe: a dead voice call must never leave the camera running.
        scope.launch {
            VoiceCallService.stopRequested.collect { stop ->
                if (stop) stopVideo()
            }
        }
        // Capture is not started here: it begins per call via START_CAPTURE,
        // so a merely-armed wake session never holds the camera.
        instance = this
    }

    /**
     * The capture generation: bumped on every START_CAPTURE. A spoken
     * farewell's refresh carries the generation it was queued against; the
     * live instance drops the refresh when a newer call has started since
     * (generation moved on), so a delayed "idle" can never overwrite a
     * newer call's "Video on".
     */
    @Volatile
    internal var captureGeneration = 0L
        private set

    private fun notification(nextStatus: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VideoCallService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis video")
            .setContentText(nextStatus)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop video", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyChanged(nextStatus: String) {
        notificationLifecycle.publish(nextStatus)
    }

    /**
     * Handle a spoken-farewell refresh on the service's main thread. The
     * refresh is fenced by the capture generation and by liveness: it is
     * dropped when this instance is no longer the live one, or when a newer
     * call's START_CAPTURE moved the generation on. The notification status
     * is derived from the controller's CURRENT state at handle time — never
     * a precomputed string — so a delayed refresh can never apply a stale
     * "idle" over a newer call's "Video on".
     */
    internal fun enqueueFarewellRefresh(endedCallId: String, generation: Long) {
        scope.launch {
            val derived = resolveFarewellRefresh(
                endedCallId = endedCallId,
                refreshGeneration = generation,
                currentGeneration = captureGeneration,
                isLive = (instance === this@VideoCallService),
            )
            if (derived != null) {
                notifyChanged(derived)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopVideo()
            START_CAPTURE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                // This is the main-thread admission point. An off-main update
                // rejection after admission queues cleanup behind this command;
                // it never holds the publication lock during controller work.
                if (notificationLifecycle.isOpen && !callId.isNullOrBlank() && captureStarts.admit(callId) {
                    // A newer call obsoletes any farewell refresh still
                    // queued for an older one: bump the generation first so
                    // a delayed refresh can never overwrite this call's
                    // status. Admission and the bump are atomic with farewell
                    // revocation; camera/controller work runs outside that lock.
                    captureGeneration++
                }) {
                    // Eligibility requires the type actually accepted at startup.
                    // A later permission grant cannot silently promote specialUse.
                    val granted = foregroundNotification.canCapture(CameraPermission.isGranted(this))
                    controller.start(granted, callId)
                    // The status is derived from the controller state, so a
                    // refused bind (cleanup pending) can never read "Video on".
                    notifyChanged(videoStatusText(controller.state))
                }
            }
            STOP_CAPTURE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!callId.isNullOrBlank()) {
                    captureStarts.end(callId)
                    // Only the owning call's identity stops the capture; a
                    // stale stop for an older call is a no-op. The status is
                    // derived from the controller state, so an unresolved
                    // cleanup stays visible instead of reading idle.
                    controller.stopForCall(callId)
                    notifyChanged(videoStatusText(controller.state))
                }
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopVideo()
    }

    override fun onDestroy() {
        try {
            closeVideo()
        } finally {
            // Observers seeing null know notification removal was requested,
            // not just that camera teardown has started.
            if (instance === this) instance = null
            super.onDestroy()
        }
    }

    /** Main-thread terminal decision: revoke before any queued start can run. */
    private fun stopVideo() {
        closeVideo()
        try { stopSelf() } catch (failure: RuntimeException) {
            android.util.Log.e("JarvisVideo", "Unable to stop closed video service", failure)
        }
    }

    private fun closeVideo() {
        try {
            notificationLifecycle.close {
                scope.cancel()
                if (::controller.isInitialized) {
                    try {
                        controller.stop()
                    } catch (_: Exception) {
                        // Retain the binder's unresolved-detach ownership; never
                        // turn a failed camera cleanup into a successful bind.
                    }
                    if (CallVisionRegistry.controller === controller) CallVisionRegistry.clear()
                }
            }
        } catch (failure: RuntimeException) {
            // The owner has already closed and attempted both removals. A
            // platform teardown rejection must not kill the independent audio
            // call through an uncaught main-coroutine/lifecycle exception.
            android.util.Log.e("JarvisVideo", "Video notification removal failed", failure)
        }
    }

    companion object {
        private const val CHANNEL = "jarvis_video_calls"
        private const val NOTIFICATION_ID = 482
        private const val STOP = "com.battlesbudz.jarvis.v2.STOP_VIDEO"
        private const val START_CAPTURE = "com.battlesbudz.jarvis.v2.START_VIDEO_CAPTURE"
        private const val STOP_CAPTURE = "com.battlesbudz.jarvis.v2.STOP_VIDEO_CAPTURE"
        private const val EXTRA_CALL_ID = "com.battlesbudz.jarvis.v2.EXTRA_CALL_ID"
        private val captureStarts = VideoCaptureStartFence()

        /**
         * The live service instance, or null when the service is not
         * running. Farewell refreshes are delivered ONLY to this instance —
         * never via startForegroundService, which could resurrect a stopped
         * service (and its notification) on its own. A refresh posted just
         * as the service dies is dropped by the liveness fence instead.
         */
        @Volatile
        var instance: VideoCallService? = null
            private set

        /**
         * Begin call-scoped video capture for [callId]. Starts the service
         * when needed; only [callId] owns the capture, so ending any other
         * call cannot stop it. [currentCallId] is the call owner's read-only
         * identity check; it is checked both before queueing and at delivery.
         */
        fun startCapture(context: Context, callId: String, currentCallId: () -> String?) {
            if (!captureStarts.request(callId, currentCallId)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoCallService::class.java)
                    .setAction(START_CAPTURE)
                    .putExtra(EXTRA_CALL_ID, callId),
            )
        }

        /**
         * A spoken farewell ends the call's capture outside this service's
         * start/stop command path. Route the refresh to the live service
         * instance only: it ends [endedCallId]'s capture through the registry
         * controller and derives the notification status from the
         * controller's current state, fenced by the capture generation — a
         * delayed refresh can never overwrite a newer call's status, and a
         * refresh for a dead service is dropped. Revoke queued capture starts
         * synchronously even when no service exists yet: a START_CAPTURE
         * delivered after farewell must never start the ended call's camera.
         * The refresh never resurrects a stopped service or notification.
         */
        fun refreshVideoStatusAfterFarewell(endedCallId: String) {
            captureStarts.end(endedCallId)
            val svc = instance ?: return
            svc.enqueueFarewellRefresh(endedCallId, svc.captureGeneration)
        }
    }
}

/**
 * Process-owned admission for asynchronous service starts. The narrow current-call
 * reader comes from the call owner, so a delayed onCallBegan callback cannot replace
 * a newer request. The ending identity fences the interval before that owner clears
 * its call; no unbounded history of ended calls is retained.
 *
 * Lock order is this fence -> the call owner's read-only identity lock. The owner
 * invokes onCallBegan outside its lock. [admit]'s callback only advances the service
 * generation: controller/binder work must run AFTER admission releases this lock.
 * A farewell racing an admitted start then sees its new generation and queues the
 * stop on the same main thread that handles START_CAPTURE.
 */
internal class VideoCaptureStartFence {
    private var requestedCallId: String? = null
    private var endedCallId: String? = null
    private var currentCallId: (() -> String?)? = null

    @Synchronized
    fun request(callId: String, currentCallId: () -> String?): Boolean {
        this.currentCallId = currentCallId
        if (callId.isBlank() || currentCallId() != callId || endedCallId == callId) return false
        requestedCallId = callId
        return true
    }

    @Synchronized
    fun end(callId: String) {
        // Preserve the current ending call's fence when an old farewell arrives.
        // Before the first start request there is no reader yet, but the ending
        // identity still has to be remembered for a delayed onCallBegan callback.
        if (currentCallId == null || currentCallId?.invoke() == callId) endedCallId = callId
        if (requestedCallId == callId) requestedCallId = null
    }

    @Synchronized
    fun admit(callId: String, onAdmitted: () -> Unit): Boolean {
        if (requestedCallId != callId || endedCallId == callId || currentCallId?.invoke() != callId) {
            return false
        }
        onAdmitted()
        return true
    }
}

/**
 * Process-wide access to the active call's vision pipeline. Slice 2 (and any
 * UI) registers a [VisionObserver] here; null when no video call is active.
 */
object CallVisionRegistry {
    @Volatile
    var hub: VisionFrameHub? = null
        internal set

    @Volatile
    var controller: CallVisionController? = null
        internal set

    internal fun clear() {
        hub = null
        controller = null
    }
}

/**
 * Decide a spoken-farewell video refresh at handle time. [isLive] is true
 * only when the refresh reached the still-live service instance it was
 * queued for. Returns the notification status derived from the CURRENT
 * controller state, or null when the refresh must be dropped: the service
 * is gone, or a newer call's START_CAPTURE moved the generation on since
 * the refresh was queued — a delayed "idle" must never overwrite a newer
 * call's "Video on".
 *
 * The status is derived here, at handle time, never from a precomputed
 * string carried by the refresh: only the live controller knows the
 * current state. The capture teardown ([farewellVideoStatus]) also runs
 * here, so a dropped refresh cannot stop a newer call's capture either —
 * and every drop path is safe: a dead service has no capture left to stop,
 * and a newer START_CAPTURE already stopped the older call's capture.
 */
internal fun resolveFarewellRefresh(
    endedCallId: String,
    refreshGeneration: Long,
    currentGeneration: Long,
    isLive: Boolean,
): String? {
    if (!isLive) return null
    if (refreshGeneration != currentGeneration) return null
    return farewellVideoStatus(endedCallId)
}

/**
 * The video service's notification status after a spoken farewell: end the
 * ended call's capture through the registry controller, then derive the
 * status from the controller state. The runtime's farewell path runs this
 * exact composition (via [VideoCallService.refreshVideoStatusAfterFarewell])
 * before the resulting status is pushed into the service notification, so
 * the notification can never read "Video on" after the controller is IDLE.
 */
fun farewellVideoStatus(endedCallId: String?): String {
    val controller = CallVisionRegistry.controller
    endedCallId?.let { callId -> runCatching { controller?.stopForCall(callId) } }
    return videoStatusText(controller?.state ?: CallVisionController.State.IDLE)
}
