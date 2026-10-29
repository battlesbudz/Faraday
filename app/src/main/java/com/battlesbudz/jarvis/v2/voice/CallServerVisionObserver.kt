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

/**
 * Video-call step 2: frames instead of photos.
 *
 * The pipeline already captures a downscaled JPEG about once a second
 * during a video call (FrameCadence + CameraXVideoBinder into
 * [VisionFrameHub]). This observer plugs into that hub and sends each
 * frame to the Phone Inference server through the existing
 * [PhoneVisionClient] — the chat-photo path, on repeat. Each answer lands
 * in [holder]; nothing here tracks across frames (step 3), feeds a prompt
 * (step 4), or draws anything (step 5).
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
) : VisionObserver {
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var closed = false

    override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
        // The hub already swallows observer exceptions, but this observer
        // is on the call's camera path: nothing here may ever throw.
        runCatching {
            if (closed) return
            holder.recordReceived()
            val config = runCatching { settings() }.getOrNull() ?: return
            if (!config.enabled || config.token.isBlank()) return
            if (!inFlight.compareAndSet(false, true)) {
                holder.recordDroppedInFlight()
                return
            }
            holder.recordSent()
            val token = config.token
            val startedMs = clockMs()
            try {
                scope.launch {
                    try {
                        val snapshot = detect(jpegBytes, token)
                        holder.recordSuccess(
                            snapshot = snapshot,
                            frameTimestampMs = timestampMs,
                            latencyMs = clockMs() - startedMs,
                            receivedAtMs = clockMs(),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        holder.recordFailure(
                            errorText = error.message ?: error.javaClass.simpleName,
                            latencyMs = clockMs() - startedMs,
                        )
                    } finally {
                        inFlight.set(false)
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
        holder.reset()
    }

    /** The owning call's capture ended: no result may read as current. */
    fun onCaptureEnded() {
        holder.clear()
    }

    /**
     * Terminal teardown (service closing): no frame is accepted and the
     * worker scope is cancelled. A detect still in flight is abandoned;
     * its result, if it ever lands, goes to a holder nobody reads.
     */
    fun close() {
        closed = true
        scope.cancel()
    }
}
