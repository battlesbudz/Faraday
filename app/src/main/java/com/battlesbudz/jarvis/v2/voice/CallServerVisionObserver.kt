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
import java.util.concurrent.atomic.AtomicBoolean
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
 * Capture fencing: every detect is tagged with the capture generation
 * current when its frame was sent. [onCaptureStarted], [onCaptureEnded]
 * and [close] each advance the generation, so a detect that completes
 * after its capture ended (or a new one began) is discarded instead of
 * republishing a dead call's scene into the holder or feeding the next
 * call's tracker. The lifecycle transitions also release the
 * single-flight flag themselves: a stale request must never keep the
 * new capture's frames dropped while it finishes, and its completion
 * must never clear the flag a newer request is holding.
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
    private val inFlight = AtomicBoolean(false)

    /** Bumped at every capture lifecycle transition; see the class doc. */
    private val captureGeneration = AtomicLong(0L)

    @Volatile
    private var closed = false

    override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
        // The hub already swallows observer exceptions, but this observer
        // is on the call's camera path: nothing here may ever throw.
        runCatching {
            if (closed) return
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
            if (!inFlight.compareAndSet(false, true)) {
                holder.recordDroppedInFlight()
                return
            }
            holder.recordSent()
            val token = config.token
            val startedMs = clockMs()
            // The generation this request belongs to, and the exact
            // frame it runs on: the completion is fenced on the first
            // and the result is paired with the second.
            val generation = captureGeneration.get()
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
                        // flag; after a lifecycle transition the flag is
                        // already reset (or held by a newer request).
                        if (captureGeneration.get() == generation) {
                            inFlight.set(false)
                        }
                    }
                }
            } catch (error: Exception) {
                // The scope was cancelled under us (teardown raced a frame):
                // never leave the single-flight flag set or the send counted
                // with nobody able to complete it.
                inFlight.set(false)
                holder.recordFailure(
                    errorText = error.message ?: error.javaClass.simpleName,
                    latencyMs = clockMs() - startedMs,
                )
            }
        }
    }

    /** A new call's capture is live: the previous call's proof is wiped. */
    fun onCaptureStarted() {
        captureGeneration.incrementAndGet()
        inFlight.set(false)
        holder.reset()
    }

    /** The owning call's capture ended: no result may read as current. */
    fun onCaptureEnded() {
        captureGeneration.incrementAndGet()
        inFlight.set(false)
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
        captureGeneration.incrementAndGet()
        scope.cancel()
    }
}
