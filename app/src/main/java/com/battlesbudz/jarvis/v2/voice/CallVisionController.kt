package com.battlesbudz.jarvis.v2.voice

/**
 * Pure state machine orchestrating video capture for a call. Android-free:
 * the [VideoBinder] does the platform work (CameraX), so this is fully
 * unit-testable.
 *
 * Capture is owned by exactly one call identity: [start] records the call
 * that owns the capture, and only [stopForCall] with that same identity (or
 * [stop]) ends it. A stale farewell for an ended call can never stop a
 * newer call's capture, and capture never survives into passive wake
 * listening — the runtime ends it with the call that owned it.
 *
 * States: IDLE -> ACTIVE -> IDLE, IDLE -> DENIED -> IDLE,
 * ACTIVE -> DEGRADED -> IDLE, or * -> CLEANUP_PENDING -> IDLE / ACTIVE.
 * DENIED means the camera permission was refused: the binder is never
 * touched and the service degrades to audio-only. DEGRADED means the
 * permission was granted but the camera itself failed (provider failure,
 * no back camera, bind failure): the binder is released and the call
 * continues audio-only. The owning call identity is retained through
 * DEGRADED so ending the call still cleans up exactly once.
 * CLEANUP_PENDING means a bind was refused or a teardown's detach failed:
 * the binder still owns the previous capture's camera handle because the
 * detach was never confirmed, so a fresh capture cannot start on top of it.
 * The refusal is explicit ([VideoBinder.bind] returns [BindResult]) and the
 * pending state stays visible in the controller and the service status —
 * a failed detach followed by the next call can never advertise video the
 * binder deliberately did not start. A new start retries the retained
 * detach first; only a confirmed cleanup re-arms capture.
 */
class CallVisionController(
    private val binder: VideoBinder,
    private val cadence: FrameCadence = FrameCadence(),
    private val hub: VisionFrameHub = VisionFrameHub(),
) {
    enum class State { IDLE, DENIED, ACTIVE, DEGRADED, CLEANUP_PENDING }

    /**
     * What the binder did with a bind request. A refused bind is an
     * explicit outcome, never a silent no-op: the controller must not
     * report ACTIVE for capture the binder did not start.
     */
    sealed interface BindResult {
        /** A capture attempt is live (attaching) or already bound. */
        data object Started : BindResult
        /** Refused: the previous capture's cleanup is unresolved. */
        data object CleanupBlocked : BindResult
    }

    interface VideoBinder {
        /**
         * Attempt to start capture. Returns [BindResult.Started] when a
         * capture attempt is live (or already bound), or
         * [BindResult.CleanupBlocked] when the previous capture's cleanup
         * is unresolved and the attempt was refused.
         */
        fun bind(): BindResult
        fun unbind()
        /** True while a failed/timed-out detach retains the camera handle. */
        val cleanupUnresolved: Boolean
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** The call that owns the active capture (or the pending one); null when nothing is capturing. */
    @Volatile
    private var captureCallId: String? = null

    fun captureCallId(): String? = captureCallId

    /** Why the controller degraded, when [state] is DEGRADED; null otherwise. */
    @Volatile
    private var videoError: VideoError? = null

    fun videoError(): VideoError? = videoError

    /**
     * Start video capture for [callId]. Returns the resulting state.
     * Idempotent for the owning call; a different call rebinds defensively
     * (beginCall guarantees no overlap, but a late start must never inherit
     * a dead call's capture). When cleanup is pending, the retained detach
     * is retried first — only a confirmed cleanup re-arms capture — and a
     * still-unresolved cleanup returns CLEANUP_PENDING, never ACTIVE. A
     * denied permission while the cleanup is still unresolved keeps
     * CLEANUP_PENDING visible instead of DENIED: the binder still owns the
     * camera handle, and the next call must retry its release first.
     */
    @Synchronized
    fun start(cameraPermissionGranted: Boolean, callId: String): State {
        require(callId.isNotBlank()) { "Video capture needs a call identity." }
        if (state == State.ACTIVE) {
            if (captureCallId == callId) return state
            stopLocked()
        }
        if (state == State.CLEANUP_PENDING) {
            // A fresh capture must wait for the retained cleanup to confirm.
            // Retry the retained detach; the binder refuses the bind while
            // it is unresolved, so this never starts video on a wedged
            // camera.
            binder.unbind()
        }
        if (!cameraPermissionGranted) {
            // Preserve cleanup ownership: when the retry above (or an
            // earlier teardown) left the cleanup unresolved, the binder
            // still owns the camera handle — keep CLEANUP_PENDING visible
            // instead of clobbering it with DENIED.
            state = if (binder.cleanupUnresolved) State.CLEANUP_PENDING else State.DENIED
            return state
        }
        // Own the attempt before entering the adapter: its supported synchronous
        // failure callback must be able to degrade this call. Never overwrite
        // that callback's resulting state when bind returns.
        captureCallId = callId
        videoError = null
        state = State.ACTIVE
        return when (binder.bind()) {
            BindResult.Started -> state
            BindResult.CleanupBlocked -> {
                // The binder refused: the old use case may still be bound.
                // Keep the refusal visible — never ACTIVE for video the
                // binder deliberately did not start — and record the waiting
                // call so its farewell can retry the pending cleanup.
                captureCallId = callId
                state = State.CLEANUP_PENDING
                state
            }
        }
    }

    /**
     * The camera failed while this call owned the capture (provider failure,
     * no back camera, or bind failure). Release the camera, keep the call
     * identity so [stopForCall] still ends the call exactly once, and report
     * honest audio-only: the call continues, video does not. No-op unless
     * the capture is ACTIVE.
     */
    @Synchronized
    fun degrade(error: VideoError): State {
        if (state == State.ACTIVE) {
            binder.unbind()
            state = if (binder.cleanupUnresolved) State.CLEANUP_PENDING else State.DEGRADED
            videoError = error
            cadence.reset()
            hub.clear()
        }
        return state
    }

    /**
     * Stop video capture, but only when [callId] owns it. A stale farewell
     * for an older call is a no-op: it must not end a newer call's capture.
     * While cleanup is pending, the owning call's farewell retries the
     * retained detach; only a confirmed detach returns to IDLE.
     */
    @Synchronized
    fun stopForCall(callId: String): State {
        if ((state == State.ACTIVE || state == State.DEGRADED || state == State.CLEANUP_PENDING)
            && captureCallId == callId) stopLocked()
        return state
    }

    /** Stop video capture and release everything. Safe to call when idle. */
    @Synchronized
    fun stop(): State {
        stopLocked()
        return state
    }

    private fun stopLocked() {
        if (state == State.ACTIVE || state == State.DEGRADED || state == State.CLEANUP_PENDING) binder.unbind()
        if (binder.cleanupUnresolved) {
            // The teardown's detach failed or timed out: the binder retains
            // the camera handle and the old use case may still be bound.
            // Report the unresolved cleanup honestly instead of IDLE, and
            // keep the owning call identity so its farewell can retry the
            // cleanup. A fresh capture stays blocked until cleanup confirms.
            state = State.CLEANUP_PENDING
        } else {
            state = State.IDLE
            captureCallId = null
            videoError = null
        }
        cadence.reset()
        hub.clear()
    }

    /** Temporarily raise the frame rate; only meaningful while ACTIVE. */
    fun requestBurst(durationMs: Long, fps: Double = FrameCadence.BURST_FPS) {
        if (state == State.ACTIVE) cadence.startBurst(durationMs, fps)
    }

    fun hub(): VisionFrameHub = hub
    fun cadence(): FrameCadence = cadence
}

/**
 * Honest one-line video status for the service notification, derived from
 * the controller state. A refused bind never reads "Video on": a pending
 * cleanup says the camera is still being released.
 */
fun videoStatusText(state: CallVisionController.State): String = when (state) {
    CallVisionController.State.ACTIVE -> "Video on"
    CallVisionController.State.DEGRADED -> "Camera unavailable — continuing audio-only"
    CallVisionController.State.CLEANUP_PENDING -> "Camera cleanup pending — video restarts after the camera releases"
    CallVisionController.State.DENIED -> "Camera unavailable — grant permission to enable video"
    CallVisionController.State.IDLE -> "Video idle — starts with your next call"
}
