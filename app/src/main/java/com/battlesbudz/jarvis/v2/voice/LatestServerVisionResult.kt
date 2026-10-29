package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.vision.NativeVisionSnapshot

/**
 * Where the latest Phone Inference server answer for the live video call
 * lives (video-call step 2), plus the proof counters that show the frame
 * loop actually ran.
 *
 * Written by [CallServerVisionObserver] (camera thread for the received /
 * sent / dropped counters, its own worker for success / failure), read by
 * the diagnostics export and, later, by step 4's prompt wiring. A new
 * result REPLACES the previous one wholesale — detections from two frames
 * are never merged, because each frame's scene stands alone.
 *
 * [clear] removes the current result when a call's capture ends, so a
 * stale scene can never be read as current after the call. The counters
 * are deliberately NOT reset by [clear]: they are the proof a finished
 * call ran the loop, and the diagnostics export is read after calls end.
 * They reset via [reset] when the next capture starts (and on observer
 * construction), so each call's numbers describe that call.
 *
 * Thread-safe: every mutation and read is under one lock. [changeListener]
 * fires after every mutation, outside the lock; the service uses it to
 * persist [diagnosticsText] for the Copy diagnostics export.
 */
class LatestServerVisionResult {
    /** One server answer: what the server saw, and which frame it came from. */
    data class Entry(
        val snapshot: NativeVisionSnapshot,
        val frameTimestampMs: Long,
        val receivedAtMs: Long,
    )

    /** Point-in-time copy of the proof counters. */
    data class Counters(
        val framesReceived: Long,
        val framesSent: Long,
        val framesDroppedInFlight: Long,
        val detectsSucceeded: Long,
        val detectsFailed: Long,
        val lastLatencyMs: Long?,
        val lastDetectionCount: Int?,
    )

    private val lock = Any()
    private var entry: Entry? = null
    private var lastError: String? = null
    private var framesReceived = 0L
    private var framesSent = 0L
    private var framesDroppedInFlight = 0L
    private var detectsSucceeded = 0L
    private var detectsFailed = 0L
    private var lastLatencyMs: Long? = null
    private var lastDetectionCount: Int? = null

    /** Invoked after every mutation, outside the lock. Set by the owner. */
    @Volatile
    var changeListener: (() -> Unit)? = null

    fun recordReceived() {
        synchronized(lock) { framesReceived++ }
        notifyChanged()
    }

    fun recordSent() {
        synchronized(lock) { framesSent++ }
        notifyChanged()
    }

    fun recordDroppedInFlight() {
        synchronized(lock) { framesDroppedInFlight++ }
        notifyChanged()
    }

    /** A detect came back: the new snapshot replaces the old one wholesale. */
    fun recordSuccess(snapshot: NativeVisionSnapshot, frameTimestampMs: Long, latencyMs: Long, receivedAtMs: Long) {
        synchronized(lock) {
            entry = Entry(snapshot, frameTimestampMs, receivedAtMs)
            detectsSucceeded++
            lastLatencyMs = latencyMs
            lastDetectionCount = snapshot.detections.size
            lastError = null
        }
        notifyChanged()
    }

    /**
     * A detect failed: the previous result (if any) stays exactly as it
     * was; only the failure counter, latency, and error text move. The
     * error text is truncated and must never contain the server token —
     * PhoneVisionClient's messages never include it by contract.
     */
    fun recordFailure(errorText: String, latencyMs: Long) {
        synchronized(lock) {
            detectsFailed++
            lastLatencyMs = latencyMs
            lastError = errorText.take(MAX_ERROR_CHARS)
        }
        notifyChanged()
    }

    /** The current server answer, or null when none (or cleared). */
    fun latest(): Entry? = synchronized(lock) { entry }

    /** The last failure's text, or null after a success / clear / reset. */
    fun lastError(): String? = synchronized(lock) { lastError }

    fun counters(): Counters = synchronized(lock) {
        Counters(
            framesReceived = framesReceived,
            framesSent = framesSent,
            framesDroppedInFlight = framesDroppedInFlight,
            detectsSucceeded = detectsSucceeded,
            detectsFailed = detectsFailed,
            lastLatencyMs = lastLatencyMs,
            lastDetectionCount = lastDetectionCount,
        )
    }

    /**
     * The current result is gone (capture ended): no snapshot and no error
     * can be read as current. Counters survive for the diagnostics export.
     */
    fun clear() {
        synchronized(lock) {
            entry = null
            lastError = null
        }
        notifyChanged()
    }

    /** Everything back to zero: a new capture starts with a clean slate. */
    fun reset() {
        synchronized(lock) {
            entry = null
            lastError = null
            framesReceived = 0L
            framesSent = 0L
            framesDroppedInFlight = 0L
            detectsSucceeded = 0L
            detectsFailed = 0L
            lastLatencyMs = null
            lastDetectionCount = null
        }
        notifyChanged()
    }

    /**
     * The proof block for the call diagnostics export: counters, the last
     * error, and what the latest result was (never the detections
     * themselves beyond a count — this is a proof surface, not a prompt).
     */
    fun diagnosticsText(): String {
        val counters = counters()
        val current = latest()
        val error = lastError()
        return buildString {
            append("SERVER VISION (video-call step 2: call frames sent to the Phone Inference server)\n")
            append("framesReceived=${counters.framesReceived} framesSent=${counters.framesSent} ")
            append("framesDroppedInFlight=${counters.framesDroppedInFlight} ")
            append("detectsSucceeded=${counters.detectsSucceeded} detectsFailed=${counters.detectsFailed}\n")
            append("lastLatencyMs=${counters.lastLatencyMs ?: "none"} ")
            append("lastDetectionCount=${counters.lastDetectionCount ?: "none"} ")
            append("lastError=${error ?: "none"}\n")
            if (current != null) {
                append(
                    "latestFrameTimestampMs=${current.frameTimestampMs} " +
                        "latestReceivedAtMs=${current.receivedAtMs} " +
                        "latestDetections=${current.snapshot.detections.size}"
                )
            } else {
                append("latestResult=none")
            }
        }
    }

    private fun notifyChanged() {
        runCatching { changeListener?.invoke() }
    }

    companion object {
        const val MAX_ERROR_CHARS = 200
    }
}

/**
 * Persistence for the step-2 proof block. The video service writes
 * [LatestServerVisionResult.diagnosticsText] here on every change and
 * MainActivity's Copy diagnostics reads it back, so the numbers survive
 * the service stopping — the in-memory holder alone would vanish with it,
 * and the runtime's DiagnosticRecorder cannot be shared safely (a second
 * instance over the same preferences would clobber its lists).
 */
object ServerVisionDiagnostics {
    const val PREFS_NAME = "server_vision_diagnostics"
    const val KEY_LATEST_TEXT = "latest_text"
}
