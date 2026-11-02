package com.battlesbudz.jarvis.v2.vision

import android.content.res.AssetManager
import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.ClassPrediction
import com.battlesbudz.phoneinference.Detection
import com.battlesbudz.phoneinference.InferenceEngine
import com.battlesbudz.phoneinference.PoseResult
import com.battlesbudz.phoneinference.SegResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Typed result of running all four native ONNX models over one image.
 * Coordinates are in original-image pixels, same contract as the
 * :phoneinference module. The decode is sampled (see [SampledImageDecode]),
 * so the engine reports coordinates in the decoded bitmap's pixels and
 * NativeVision rescales them back to original-image pixels before building
 * this snapshot — the contract holds regardless of the source image's size.
 */
data class NativeVisionSnapshot(
    val detections: List<Detection>,
    val detectionMs: Double,
    val segments: List<SegResult>,
    val segmentationMs: Double,
    val poses: List<PoseResult>,
    val poseMs: Double,
    val classifications: List<ClassPrediction>,
    val classificationMs: Double,
) {
    val totalMs: Double get() = detectionMs + segmentationMs + poseMs + classificationMs
}

/**
 * Result of the detection + classification subset of [NativeVision.analyze]:
 * the only two model outputs launch verification records. Segmentation and
 * pose are not run for this subset — the verifier discards them.
 */
data class DetectionClassificationSnapshot(
    val detections: List<Detection>,
    val detectionMs: Double,
    val classifications: List<ClassPrediction>,
    val classificationMs: Double,
) {
    val totalMs: Double get() = detectionMs + classificationMs
}

/**
 * In-process vision backed by the :phoneinference module (native ONNX
 * Runtime). No network, no Termux server — this replaced PhoneVisionClient.
 *
 * The engine is created once per process; the first call pays ~0.5 s of
 * model load. Models are bundled at build time (see phoneinference module),
 * so there is nothing to download at runtime.
 *
 * Dispatcher isolation: all vision work runs on a dedicated vision
 * dispatcher, never Dispatchers.Default. The CI emulators have 2 cores, so
 * Default has exactly 2 threads — and launch verification used to occupy
 * both of them (the queue worker blocked in runBlocking plus NativeVision's
 * withContext(Default)), so an analysis that outlived its caller's timeout
 * left an orphan starving the next test's queue worker. Vision threads are
 * the only threads a stuck native call can occupy now.
 */
object NativeVision {
    private val visionThreadCount = AtomicInteger(0)
    private val visionDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "NativeVision-${visionThreadCount.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    /** Process-lifetime scope for vision work; never closed, like the engine. */
    private val visionScope = CoroutineScope(visionDispatcher + SupervisorJob())

    private val mutex = Mutex()
    private var engine: InferenceEngine? = null

    private suspend fun engine(assets: AssetManager): InferenceEngine {
        engine?.let { return it }
        return mutex.withLock {
            engine ?: withContext(Dispatchers.IO) { InferenceEngine(assets).also { engine = it } }
        }
    }

    private fun decodeOrThrow(imageBytes: ByteArray): SampledImageDecode.Decoded =
        SampledImageDecode.decode(imageBytes)
            ?: throw IllegalStateException("Could not decode the attached image.")

    /**
     * Axis ratios from decoded-bitmap pixels back to original-image
     * pixels, computed from the decoded bitmap's ACTUAL size (the
     * platform decoder's rounding is its own; the ratio absorbs it).
     */
    private fun axisScales(decoded: SampledImageDecode.Decoded): Pair<Float, Float> =
        decoded.sourceWidth.toFloat() / decoded.bitmap.width to
            decoded.sourceHeight.toFloat() / decoded.bitmap.height

    private fun Box.scaled(scaleX: Float, scaleY: Float): Box =
        copy(x1 = x1 * scaleX, y1 = y1 * scaleY, x2 = x2 * scaleX, y2 = y2 * scaleY)

    private fun List<Detection>.rescaleDetections(scaleX: Float, scaleY: Float): List<Detection> =
        if (scaleX == 1f && scaleY == 1f) this
        else map { it.copy(box = it.box.scaled(scaleX, scaleY)) }

    private fun List<SegResult>.rescaleSegments(scaleX: Float, scaleY: Float): List<SegResult> =
        if (scaleX == 1f && scaleY == 1f) this
        else map { segment ->
            segment.copy(
                box = segment.box.scaled(scaleX, scaleY),
                points = segment.points.map { point ->
                    point.copy(x = point.x * scaleX, y = point.y * scaleY)
                },
            )
        }

    private fun List<PoseResult>.rescalePoses(scaleX: Float, scaleY: Float): List<PoseResult> =
        if (scaleX == 1f && scaleY == 1f) this
        else map { pose ->
            pose.copy(
                box = pose.box.scaled(scaleX, scaleY),
                keypoints = pose.keypoints.map { keypoint ->
                    keypoint.copy(x = keypoint.x * scaleX, y = keypoint.y * scaleY)
                },
            )
        }

    /**
     * Runs detection, segmentation, pose and classification on [imageBytes]
     * (any BitmapFactory-decodable format) on the vision dispatcher.
     */
    suspend fun analyze(imageBytes: ByteArray, assets: AssetManager): NativeVisionSnapshot =
        withContext(visionDispatcher) {
            val decoded = decodeOrThrow(imageBytes)
            val bitmap = decoded.bitmap
            try {
                val eng = engine(assets)
                val (detections, detMs) = eng.detector.detect(bitmap, maxDetections = 5)
                val (segments, segMs) = eng.segmenter.segment(bitmap, maxDetections = 3)
                val (poses, poseMs) = eng.poseDetector.pose(bitmap, maxDetections = 3)
                val (classes, clsMs) = eng.classifier.classify(bitmap)
                val (scaleX, scaleY) = axisScales(decoded)
                NativeVisionSnapshot(
                    detections.rescaleDetections(scaleX, scaleY), detMs,
                    segments.rescaleSegments(scaleX, scaleY), segMs,
                    poses.rescalePoses(scaleX, scaleY), poseMs,
                    classes.take(3), clsMs,
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    /**
     * Runs ONLY detection + classification on [imageBytes] on the vision
     * dispatcher. Launch verification records detections + the first
     * classification and discards segmentation/pose, so running those two
     * extra 640-input YOLO models was pure cost for that caller. The full
     * [analyze] stays for callers that consume all four model outputs.
     */
    suspend fun analyzeDetectionAndClassification(
        imageBytes: ByteArray,
        assets: AssetManager
    ): DetectionClassificationSnapshot =
        withContext(visionDispatcher) {
            val decoded = decodeOrThrow(imageBytes)
            val bitmap = decoded.bitmap
            try {
                val eng = engine(assets)
                val (detections, detMs) = eng.detector.detect(bitmap, maxDetections = 5)
                val (classes, clsMs) = eng.classifier.classify(bitmap)
                val (scaleX, scaleY) = axisScales(decoded)
                DetectionClassificationSnapshot(
                    detections.rescaleDetections(scaleX, scaleY), detMs, classes.take(3), clsMs
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    /**
     * Blocking entry for the launch verifier: runs the detection +
     * classification subset on the vision dispatcher and joins it with a
     * REAL wall-clock bound of [timeoutMs].
     *
     * withTimeout is cooperative: a blocking JNI call has no suspension
     * point, so its cancellation is only delivered after the native calls
     * return — the 90s timeout used to be a suggestion, not a bound.
     * Future.get bounds the CALLER even when native hangs. On timeout the
     * vision job is cancelled (best-effort: delivered at the next
     * suspension point) and any orphaned native work stays confined to the
     * vision dispatcher; it can never occupy a Default worker thread.
     * Throws java.util.concurrent.TimeoutException on timeout (the verifier
     * maps it to an Unconfirmed "analysis timed out" receipt, never success).
     */
    fun analyzeDetectionAndClassificationBlocking(
        imageBytes: ByteArray,
        assets: AssetManager,
        timeoutMs: Long
    ): DetectionClassificationSnapshot {
        val result = CompletableFuture<DetectionClassificationSnapshot>()
        val job = visionScope.launch {
            try {
                result.complete(analyzeDetectionAndClassification(imageBytes, assets))
            } catch (cancelled: CancellationException) {
                result.completeExceptionally(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // TimeoutCancellationException's constructors are internal in
            // pinned coroutines 1.9.0, so it cannot be constructed here.
            // Throw the java.util.concurrent timeout instead; the verifier
            // maps it explicitly to an Unconfirmed "analysis timed out"
            // receipt, never success.
            val failure = TimeoutException("Screenshot vision analysis exceeded ${timeoutMs}ms")
            result.completeExceptionally(failure)
            job.cancel()
            throw failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            result.completeExceptionally(interrupted)
            job.cancel()
            throw interrupted
        } catch (execution: ExecutionException) {
            throw execution.cause ?: execution
        }
    }
}
