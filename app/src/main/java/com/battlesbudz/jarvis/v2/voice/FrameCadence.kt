package com.battlesbudz.jarvis.v2.voice

/**
 * Decides whether a frame should be captured right now. Pure logic, no
 * Android dependencies; the clock is injectable for deterministic tests.
 *
 * Default is 1 frame per second: enough for scene awareness, kind to the
 * battery. [startBurst] temporarily raises the rate (e.g. the user asked
 * "what's happening right now?") and expires on its own — no timer needed.
 */
class FrameCadence(
    var framesPerSecond: Double = DEFAULT_FPS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var lastCaptureMs: Long = Long.MIN_VALUE
    private var burstUntilMs: Long = Long.MIN_VALUE
    private var burstFps: Double = DEFAULT_FPS

    /** Raise the capture rate for [durationMs]; expires automatically. */
    fun startBurst(durationMs: Long, fps: Double = BURST_FPS) {
        require(durationMs > 0) { "burst duration must be positive" }
        require(fps > 0) { "burst fps must be positive" }
        burstFps = fps
        burstUntilMs = clockMs() + durationMs
    }

    fun stopBurst() {
        burstUntilMs = Long.MIN_VALUE
    }

    /** True when enough time has elapsed since the last captured frame. */
    fun shouldCapture(nowMs: Long = clockMs()): Boolean {
        val fps = if (nowMs < burstUntilMs) burstFps else framesPerSecond
        if (fps <= 0) return false
        if (lastCaptureMs == Long.MIN_VALUE) return true
        return nowMs - lastCaptureMs >= 1000.0 / fps
    }

    /** Record that a frame was actually captured. */
    fun markCaptured(nowMs: Long = clockMs()) {
        lastCaptureMs = nowMs
    }

    fun reset() {
        // Do not revert to MIN_VALUE: that means "never captured", which makes
        // shouldCapture() return true immediately. After a reset (degrade/stop)
        // the next capture must wait a full interval.
        lastCaptureMs = clockMs()
        stopBurst()
    }

    companion object {
        const val DEFAULT_FPS = 1.0
        const val BURST_FPS = 5.0
    }
}
