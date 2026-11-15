package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.vision.NativeVisionSnapshot
import com.battlesbudz.jarvis.v2.vision.PhoneVisionClient
import com.battlesbudz.jarvis.v2.vision.VisionServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Video-call step 2: frames instead of photos.
 *
 * The pipeline already captures a downscaled JPEG about once a second
 * during a video call (FrameCadence + CameraXVideoBinder into
 * [VisionFrameHub]). This observer plugs into that hub and sends each
 * frame to the Phone Inference server through the existing
 * [PhoneVisionClient] — the chat-photo path, on repeat. Each answer lands
 * in [holder], which feeds it to the step-3 tracker; nothing here feeds a
 * prompt (step 4) or draws anything (step 5).
 *
 * Contract with the hub: [onFrame] runs on the camera analysis thread and
 * returns immediately. The detect runs on this observer's own single
 * scope. Single-flight: while a detect is in flight, further frames are
 * dropped and counted, never queued — at 1 fps against the server's
 * proven ~250-330 ms the loop normally keeps up, and a hung detect (up to
 * the client's 30 s read timeout) pauses sending instead of piling up.
 *
 * Gating is identical to the chat-photo path: send only when the vision
 * server setting is enabled AND a token is saved. Otherwise the observer
 * counts the frames it saw and makes zero calls. There is no native
 * fallback: a down server records failures and keeps the previous result,
 * and can never crash or stall the call.
 *
 * [detect] and [settings] are injected so unit tests never touch the
 * network or SharedPreferences; production defaults read the real
 * settings and call the real client, constructed per detect exactly as
 * the chat-photo path constructs it per turn.
 *
 * Capture fencing: a frame callback belongs to the capture generation
 * sampled when the callback STARTS, before any bookkeeping, and a
 * callback that crosses a lifecycle transition while it is still being
 * booked in is rejected outright — its detect is never admitted under
 * a later capture's generation. Validation and flight admission are
 * atomic: the epoch check and the single-flight claim happen in one
 * critical section, and [onCaptureStarted], [onCaptureEnded] and
 * [close] advance the generation and release the flag under the same
 * lock, so a stale callback can never claim the flag after a
 * transition reset it (its completion is generation-fenced and could
 * not release the flag again, which would wedge the new capture). An
 * admitted detect's completion may publish only while its generation
 * is still current, so a detect that completes after its capture
 * ended (or a new one began) is discarded instead of republishing a
 * dead call's scene into the holder or feeding the next call's
 * tracker, and its completion never clears the flag a newer request
 * is holding.
 *
 * Internal, not public: the injected settings type is
 * [VisionServerSettings.Config], whose declaring object is internal, so a
 * public constructor could not expose it (compileReleaseKotlin error on
 * the first step-2 CI run). Same-module consumers (the service, tests)
 * are unaffected.
 */
internal class CallServerVisionObserver(
    private val holder: LatestServerVisionResult,
    private val settings: () -> VisionServerSettings.Config,
    private val detect: suspend (imageBytes: ByteArray, token: String) -> NativeVisionSnapshot =
        { imageBytes, token -> PhoneVisionClient().detect(imageBytes, token) },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Snapshot synthesis: the pixel geometry of the frame currently
     * being delivered. The binder notes each frame's geometry on the hub
     * immediately before dispatching it (same analysis thread), so the
     * hub's latest geometry at [onFrame] time is this frame's. Injected
     * so unit tests never touch a real hub.
     */
    private val frameGeometry: () -> FrameGeometry? = { null },
) : VisionObserver {
    private enum class Admission { ADMITTED, BUSY, REJECTED }

    /**
     * Guards [inFlight] together with the capture generation: admission,
     * flight release and every lifecycle transition take this lock, so
     * validating a callback's epoch and claiming the flight slot are
     * atomic with respect to a transition (see the class doc).
     */
    private val captureLock = Any()
    private var inFlight = false

    /** Bumped at every capture lifecycle transition; see the class doc. */
    private val captureGeneration = AtomicLong(0L)

    @Volatile
    private var closed = false

    override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
        // The hub already swallows observer exceptions, but this observer
        // is on the call's camera path: nothing here may ever throw.
        runCatching {
            if (closed) return
            // The capture generation this callback belongs to, sampled
            // before any bookkeeping: a callback that began in a capture
            // which has since ended must never tag its detect with the
            // next capture's generation (the completion fence can only
            // reject generations older than the one sampled).
            val epoch = captureGeneration.get()
            holder.recordReceived()
            // Snapshot synthesis: retain the frame itself for Gemma's
            // per-turn snapshot. Deliberately BEFORE the server gating
            // below: the snapshot serves the local model, not the server,
            // so a disabled/unconfigured server must not starve it (the
            // tracks it is paired with still come only from real detects).
            val geometry = runCatching { frameGeometry() }.getOrNull()
            holder.recordFrame(jpegBytes, timestampMs, geometry)
            val config = runCatching { settings() }.getOrNull() ?: return
            if (!config.enabled || config.token.isBlank()) return
            // Admission is atomic: the epoch validation and the
            // single-flight claim happen in one critical section, and
            // every lifecycle transition (which advances the epoch and
            // releases the flag) takes the same lock. A callback that
            // crossed a transition while it was being booked in is
            // rejected; it can never claim the flag after a transition
            // already reset it — its completion is generation-fenced
            // and could not release a flag it no longer owns, so the
            // new capture would drop frames until its next transition.
            val admission = synchronized(captureLock) {
                when {
                    closed || captureGeneration.get() != epoch -> Admission.REJECTED
                    inFlight -> Admission.BUSY
                    else -> {
                        inFlight = true
                        Admission.ADMITTED
                    }
                }
            }
            when (admission) {
                Admission.REJECTED -> return
                Admission.BUSY -> {
                    holder.recordDroppedInFlight()
                    return
                }
                Admission.ADMITTED -> Unit
            }
            holder.recordSent()
            val token = config.token
            val startedMs = clockMs()
            // The epoch this request belongs to, and the exact frame it
            // runs on: the completion is fenced on the first and the
            // result is paired with the second.
            val generation = epoch
            val detectFrame = LatestServerVisionResult.Frame(jpegBytes, timestampMs, geometry)
            try {
                scope.launch {
                    try {
                        val snapshot = detect(jpegBytes, token)
                        if (captureGeneration.get() == generation) {
                            holder.recordSuccess(
                                snapshot = snapshot,
                                frameTimestampMs = timestampMs,
                                latencyMs = clockMs() - startedMs,
                                receivedAtMs = clockMs(),
                                frame = detectFrame,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (captureGeneration.get() == generation) {
                            holder.recordFailure(
                                errorText = error.message ?: error.javaClass.simpleName,
                                latencyMs = clockMs() - startedMs,
                            )
                        }
                    } finally {
                        // Only this generation's request may release the
                        // flag, under the same lock as admission and the
                        // transitions; after a lifecycle transition the
                        // flag is already reset (or held by a newer
                        // request).
                        synchronized(captureLock) {
                            if (captureGeneration.get() == generation) {
                                inFlight = false
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                // The scope was cancelled under us (teardown raced a frame):
                // never leave the single-flight flag set or the send counted
                // with nobody able to complete it.
                synchronized(captureLock) {
                    if (captureGeneration.get() == generation) inFlight = false
                }
                holder.recordFailure(
                    errorText = error.message ?: error.javaClass.simpleName,
                    latencyMs = clockMs() - startedMs,
                )
            }
        }
    }

    /** A new call's capture is live: the previous call's proof is wiped. */
    fun onCaptureStarted() {
        synchronized(captureLock) {
            captureGeneration.incrementAndGet()
            inFlight = false
        }
        holder.reset()
    }

    /** The owning call's capture ended: no result may read as current. */
    fun onCaptureEnded() {
        synchronized(captureLock) {
            captureGeneration.incrementAndGet()
            inFlight = false
        }
        holder.clear()
    }

    /**
     * Terminal teardown (service closing): no frame is accepted and the
     * worker scope is cancelled. A detect still in flight is abandoned;
     * its result, if it ever lands, is fenced out by the generation bump
     * and goes to a holder nobody reads.
     */
    fun close() {
        closed = true
        synchronized(captureLock) {
            captureGeneration.incrementAndGet()
            inFlight = false
        }
        scope.cancel()
    }
}
