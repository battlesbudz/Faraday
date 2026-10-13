package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Why video capture degraded to audio-only. */
enum class VideoError { PROVIDER_FAILED, NO_BACK_CAMERA, BIND_FAILED }

/**
 * Real [CallVisionController.VideoBinder]: binds CameraX ImageAnalysis,
 * converts YUV frames to small JPEGs behind the cadence gate, and dispatches
 * to the [VisionFrameHub].
 *
 * Battery-conscious by construction: 640x480 analysis resolution,
 * STRATEGY_KEEP_ONLY_LATEST (a slow consumer never queues frames), and the
 * [FrameCadence] gate drops everything above the configured rate before any
 * conversion work happens.
 *
 * Cancellation safety: [bind] is asynchronous (the provider initializes off
 * the calling thread). Every bind attempt owns an [AttachHandle]; [unbind]
 * cancels a still-pending attempt so a stop during initialization can never
 * bind after teardown, and always shuts down the analyzer executor — even
 * when the provider never arrived — so no executor leaks. Provider or
 * camera failures never escape: they route to [onVideoError] and the call
 * continues audio-only. Analyzer callbacks carry the same bind generation;
 * conversion runs outside the lifecycle lock, and cache publication is
 * serialized with unbind. Observer delivery carries the hub epoch and runs
 * outside lifecycle locks, so callbacks can safely stop their own capture.
 *
 * Teardown safety: the CameraX detach runs on the main thread
 * ([MainThreadCameraHandle]) and [unbind] retains ownership of the camera
 * handle until the actual detach outcome is known — the handle stays
 * installed while detach runs (bounded), so a failed or still-pending
 * detach can never be mistaken for clean teardown. On a failed or timed-out
 * detach the handle is retained and [cleanupUnresolved] is set, blocking a
 * fresh capture until a later detach confirms cleanup. The outcome
 * (detached, failed, or timed out) is reported through [onDetachOutcome],
 * never silently swallowed as a success.
 */
class CameraXVideoBinder(
    private val hub: VisionFrameHub,
    private val cadence: FrameCadence,
    private val onVideoError: (VideoError) -> Unit = {},
    private val platform: CameraPlatform,
    private val executorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() },
    /**
     * Where the actual camera-teardown outcome is reported. Fires when a
     * detach completes (successfully or not); a failed or timed-out detach
     * is never silently treated as successful cleanup.
     */
    private val onDetachOutcome: (DetachOutcome) -> Unit = {},
    /** Injectable conversion/clock keep in-flight frame races deterministic in JVM tests. */
    private val jpegConverter: (ImageProxy) -> ByteArray? = ::yuvToJpeg,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : CallVisionController.VideoBinder {

    /** The actual outcome of a camera teardown detach. */
    sealed interface DetachOutcome {
        /** The old use case is actually unbound. */
        data object Detached : DetachOutcome
        /** The unbind threw or the executor rejected it: cleanup did NOT happen. */
        data class Failed(val cause: Throwable) : DetachOutcome
        /** The bounded wait expired: the detach may still complete late. */
        data object TimedOut : DetachOutcome
    }

    /** Production wiring: the real CameraX platform. Tests use the primary constructor with a fake. */
    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        hub: VisionFrameHub,
        cadence: FrameCadence,
        onVideoError: (VideoError) -> Unit = {},
        /**
         * Where a camera-detach failure or timeout is reported. Detach never
         * throws, but a cleanup that failed or never completed must not be
         * silently treated as success — the failure is delivered here instead
         * of being swallowed.
         */
        onDetachFailure: (Throwable) -> Unit = {},
    ) : this(
        hub,
        cadence,
        onVideoError,
        CameraXPlatform(context, lifecycleOwner),
        onDetachOutcome = { outcome -> reportDetachOutcome(outcome, onDetachFailure) },
    )

    /**
     * The camera-platform surface the binder drives. Production implements
     * it with CameraX ([CameraXPlatform]); tests substitute a fake. Narrow
     * by design so the bind lifecycle — cancellation, failure, degradation —
     * is unit-testable with no camera hardware.
     */
    interface CameraPlatform {
        /**
         * Attach camera analysis. [onResult] fires exactly once: [AttachResult.Attached]
         * with a live handle, or [AttachResult.Unavailable] when the camera
         * cannot be used. The returned handle cancels a still-pending attach.
         * If cancellation races an already-created camera handle, that Attached
         * result still transfers to the binder for retained cleanup ownership;
         * it must never be silently detached/discarded by the platform.
         */
        fun attach(
            analyzerExecutor: Executor,
            analyzer: (ImageProxy) -> Unit,
            onResult: (AttachResult) -> Unit,
        ): AttachHandle
    }

    sealed interface AttachResult {
        data class Attached(val handle: CameraHandle) : AttachResult
        data class Unavailable(val error: VideoError) : AttachResult
    }

    interface CameraHandle {
        /**
         * Detach; never throws. The returned outcome is the actual result —
         * teardown retains ownership until it is known, and a failure or
         * timeout is reported, never silently treated as success.
         */
        fun detach(): DetachOutcome
    }

    interface AttachHandle {
        /** Invalidate a still-pending attach; safe to call after delivery. */
        fun cancel()
    }

    private val lock = Any()
    private var binding = false
    private var generation = 0L
    private var attachHandle: AttachHandle? = null
    private var cameraHandle: CameraHandle? = null
    // Separate from the active handle: a canceled attach may finish after a
    // newer camera was installed. Retain each resource by identity until an
    // actual detach succeeds, and refuse more binds as soon as one arrives.
    // CameraX binds serially on main; revoked pending callbacks do not attach,
    // bounding real ownership to the active use case and the callback racing it.
    private val lateCleanupHandles = Collections.newSetFromMap(IdentityHashMap<CameraHandle, Boolean>())
    private val failedHandles = Collections.newSetFromMap(IdentityHashMap<CameraHandle, Boolean>())
    private val detachingHandles = Collections.newSetFromMap(IdentityHashMap<CameraHandle, Boolean>())
    private var teardownDepth = 0
    private var executor: ExecutorService? = null
    /**
     * Explicit unresolved-cleanup state (Jerry's review, build 1196): set
     * when a detach failed or timed out and the camera handle was retained
     * because the old CameraX use case may still be bound. While set,
     * [bind] refuses a fresh capture — reporting the failure alone does not
     * establish that the use case was actually released. Cleared only when
     * a detach is confirmed ([DetachOutcome.Detached]); a later [unbind]
     * retries the retained handle's detach.
     */
    @Volatile
    override var cleanupUnresolved = false
        private set

    override fun bind(): CallVisionController.BindResult {
        val attempt: Long
        val exec: ExecutorService
        synchronized(lock) {
            // A refused bind is explicit, not a silent no-op: the old use
            // case may still be bound, so no fresh capture may start on top
            // of it. The controller keeps this refusal visible instead of
            // reporting ACTIVE for video that was never started.
            if (cleanupUnresolved) return CallVisionController.BindResult.CleanupBlocked
            if (binding || cameraHandle != null) return CallVisionController.BindResult.Started
            binding = true
            generation++
            attempt = generation
            exec = executorFactory()
            executor = exec
        }
        val handle = platform.attach(exec, { image -> analyze(attempt, image) }) { result ->
            var error: VideoError? = null
            var lateHandle: CameraHandle? = null
            synchronized(lock) {
                if (attempt != generation || cleanupUnresolved) {
                    // A superseded or cleanup-blocked attempt may still own a
                    // real CameraX use case. Retain it before attempting detach;
                    // a failure must never lose the only handle to that resource.
                    if (attempt == generation) {
                        generation++
                        binding = false
                        attachHandle = null
                        shutdownExecutorLocked()
                    }
                    lateHandle = (result as? AttachResult.Attached)?.handle
                    lateHandle?.let { lateCleanupHandles += it }
                    if (lateHandle != null) hub.clear()
                    updateCleanupLocked()
                } else {
                    binding = false
                    attachHandle = null
                    when (result) {
                        is AttachResult.Attached -> cameraHandle = result.handle
                        is AttachResult.Unavailable -> {
                            error = result.error
                            shutdownExecutorLocked()
                        }
                    }
                }
            }
            lateHandle?.let(::detachOwnedHandle)
            error?.let { onVideoError(it) }
        }
        synchronized(lock) {
            // The platform may deliver synchronously; only retain the handle
            // while this attempt is still the live one.
            if (attempt == generation) attachHandle = handle else handle.cancel()
        }
        return synchronized(lock) {
            // A synchronous platform result can discover blocked cleanup
            // before bind returns; do not report that refused start as active.
            if (cleanupUnresolved) CallVisionController.BindResult.CleanupBlocked
            else CallVisionController.BindResult.Started
        }
    }

    override fun unbind() {
        val toCancel: AttachHandle?
        val exec: ExecutorService?
        synchronized(lock) {
            generation++
            hub.clear()
            teardownDepth++
            updateCleanupLocked()
            toCancel = attachHandle
            attachHandle = null
            exec = executor
            executor = null
            binding = false
        }
        try {
            toCancel?.cancel()
            // Late cleanup never overwrites ownership of a newer active camera.
            // Retry all retained resources; an in-flight detach is not duplicated.
            val handles = synchronized(lock) { listOfNotNull(cameraHandle) + lateCleanupHandles.toList() }
            handles.forEach(::detachOwnedHandle)
        } finally {
            exec?.shutdownNow()
            synchronized(lock) {
                teardownDepth--
                updateCleanupLocked()
            }
        }
    }

    private fun detachOwnedHandle(handle: CameraHandle) {
        synchronized(lock) {
            if (cameraHandle !== handle && handle !in lateCleanupHandles) return
            if (!detachingHandles.add(handle)) return
            updateCleanupLocked()
        }
        val outcome = runCatching { handle.detach() }.getOrElse { DetachOutcome.Failed(it) }
        synchronized(lock) {
            detachingHandles.remove(handle)
            if (outcome == DetachOutcome.Detached) {
                if (cameraHandle === handle) cameraHandle = null
                lateCleanupHandles.remove(handle)
                failedHandles.remove(handle)
            } else {
                // Both installed and late handles remain owned until confirmed.
                failedHandles.add(handle)
            }
            updateCleanupLocked()
        }
        onDetachOutcome(outcome)
    }

    private fun updateCleanupLocked() {
        cleanupUnresolved = teardownDepth > 0 || lateCleanupHandles.isNotEmpty() ||
            failedHandles.isNotEmpty() || detachingHandles.isNotEmpty()
    }

    private fun shutdownExecutorLocked() {
        hub.clear()
        executor?.shutdownNow()
        executor = null
    }

    private fun analyze(attempt: Long, image: ImageProxy) {
        try {
            val now = clockMs()
            synchronized(lock) {
                if (attempt != generation || executor == null || cleanupUnresolved || !cadence.shouldCapture(now)) return
            }
            // Conversion can outlive executor shutdown or CameraX unbinding.
            // Keep it outside the lock so teardown never waits on JPEG work.
            val jpeg = jpegConverter(image) ?: return
            if (jpeg.size > MAX_JPEG_BYTES) return // fail closed on absurd frames
            val delivery = synchronized(lock) {
                // Validate and cache under the same lock as unbind's epoch
                // invalidation. Only snapshot observer work here: calling
                // consumers while holding this lock would invert the
                // controller -> binder lock order when an observer stops a call.
                if (attempt != generation || executor == null || cleanupUnresolved) return
                cadence.markCaptured(now)
                hub.prepareDispatch(jpeg, now)
            }
            hub.deliver(delivery)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val JPEG_QUALITY = 70
        private const val MAX_JPEG_BYTES = 1024 * 1024

        /**
         * YUV_420_888 -> NV21 -> JPEG. Copies rows respecting each plane's
         * rowStride/pixelStride, so it works on devices with padded planes.
         */
        private fun yuvToJpeg(image: ImageProxy): ByteArray? {
            if (image.format != ImageFormat.YUV_420_888) return null
            if (image.width <= 0 || image.height <= 0) return null
            val width = image.width
            val height = image.height
            return try {
                val nv21 = ByteArray(width * height * 3 / 2)
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]
                val yBuf = yPlane.buffer
                var pos = 0
                for (row in 0 until height) {
                    yBuf.position(row * yPlane.rowStride)
                    yBuf.get(nv21, pos, width)
                    pos += width
                }
                val vBuf = vPlane.buffer
                val uBuf = uPlane.buffer
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        // NV21 order is VU interleaved.
                        nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                        nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                    }
                }
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                if (yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)) {
                    out.toByteArray()
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Single service-visible channel for teardown outcomes: a failed or
         * timed-out detach reaches [onDetachFailure]; a clean detach needs
         * no report. Detach never throws, so without this a cleanup failure
         * would be silently treated as success.
         */
        private fun reportDetachOutcome(
            outcome: DetachOutcome,
            onDetachFailure: (Throwable) -> Unit,
        ) {
            when (outcome) {
                is DetachOutcome.Failed -> onDetachFailure(outcome.cause)
                DetachOutcome.TimedOut -> onDetachFailure(
                    TimeoutException("CameraX detach timed out; the old use case may still be bound"))
                DetachOutcome.Detached -> Unit
            }
        }
    }
}

/**
 * The CameraX unbind operation, detached from the provider type so the
 * detach path is unit-testable with real threads and no camera hardware.
 */
internal fun interface CameraXUnbinder {
    fun unbind()
}

/**
 * [CameraXVideoBinder.CameraHandle] that performs the CameraX detach on the
 * main thread. CameraX requires unbind on the main thread; calling it from
 * whatever thread happens to run teardown is a wrong-thread unbind.
 *
 * Teardown retains ownership until the detach outcome is known: when the
 * caller is not already on the main thread, detach posts the unbind to the
 * main executor and waits (bounded by [detachTimeoutMs]) for it to complete,
 * so the caller does not observe "detached" while the old use case is still
 * bound. A caller already on the main thread unbinds directly (posting
 * would deadlock the bounded wait).
 *
 * Detach never throws: the returned [CameraXVideoBinder.DetachOutcome] is
 * the actual result — a cleanup failure or timeout is reported there, never
 * silently swallowed as a success.
 */
internal class MainThreadCameraHandle(
    private val unbinder: CameraXUnbinder,
    private val mainExecutor: Executor,
    private val isMainThread: () -> Boolean =
        { Looper.getMainLooper()?.thread == Thread.currentThread() },
    private val detachTimeoutMs: Long = DETACH_TIMEOUT_MS,
) : CameraXVideoBinder.CameraHandle {

    override fun detach(): CameraXVideoBinder.DetachOutcome {
        if (isMainThread()) return runUnbind()
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        try {
            mainExecutor.execute {
                try {
                    unbinder.unbind()
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }
        } catch (t: Throwable) {
            // The executor itself rejected the task: the unbind never ran.
            // Report it; do not treat it as detached.
            return CameraXVideoBinder.DetachOutcome.Failed(t)
        }
        val completed = done.await(detachTimeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) return CameraXVideoBinder.DetachOutcome.TimedOut
        val cause = failure.get()
        return if (cause != null) CameraXVideoBinder.DetachOutcome.Failed(cause)
        else CameraXVideoBinder.DetachOutcome.Detached
    }

    private fun runUnbind(): CameraXVideoBinder.DetachOutcome {
        return try {
            unbinder.unbind()
            CameraXVideoBinder.DetachOutcome.Detached
        } catch (t: Throwable) {
            CameraXVideoBinder.DetachOutcome.Failed(t)
        }
    }

    companion object {
        /** Bounded wait for the main-thread unbind: teardown must never wedge. */
        const val DETACH_TIMEOUT_MS = 2_000L
    }
}

/**
 * [CameraXVideoBinder.CameraPlatform] over the real CameraX provider.
 * Provider initialization, the back-camera check, and binding all run off
 * the caller's thread; the result posts on the main executor. Failures
 * become [VideoError] — never an escaped exception. A handle built while
 * cancellation races is transferred to the binder, which owns its cleanup
 * and retains it if detach fails or times out.
 */
private class CameraXPlatform(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraXVideoBinder.CameraPlatform {
    override fun attach(
        analyzerExecutor: Executor,
        analyzer: (ImageProxy) -> Unit,
        onResult: (CameraXVideoBinder.AttachResult) -> Unit,
    ): CameraXVideoBinder.AttachHandle {
        val cancelled = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (cancelled.get()) return@addListener
            val result: CameraXVideoBinder.AttachResult = try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraXVideoBinder.AttachResult.Unavailable(VideoError.NO_BACK_CAMERA)
                } else {
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { image -> analyzer(image) }
                    val bound = try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            analysis,
                        )
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (!bound) {
                        CameraXVideoBinder.AttachResult.Unavailable(VideoError.BIND_FAILED)
                    } else {
                        CameraXVideoBinder.AttachResult.Attached(
                            MainThreadCameraHandle(
                                unbinder = CameraXUnbinder { provider.unbind(analysis) },
                                mainExecutor = ContextCompat.getMainExecutor(context),
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                CameraXVideoBinder.AttachResult.Unavailable(VideoError.PROVIDER_FAILED)
            }
            deliverCameraAttachResult(cancelled.get(), result, onResult)
        }, ContextCompat.getMainExecutor(context))
        return object : CameraXVideoBinder.AttachHandle {
            override fun cancel() { cancelled.set(true) }
        }
    }

}

/** Cancellation cannot discard an already-created resource, even after a failed detach. */
internal fun deliverCameraAttachResult(
    cancelled: Boolean,
    result: CameraXVideoBinder.AttachResult,
    onResult: (CameraXVideoBinder.AttachResult) -> Unit,
) {
    if (!cancelled || result is CameraXVideoBinder.AttachResult.Attached) onResult(result)
}
