package com.battlesbudz.jarvis.v2.vision

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.battlesbudz.phoneinference.ClassPrediction
import com.battlesbudz.phoneinference.Detection
import com.battlesbudz.phoneinference.InferenceEngine
import com.battlesbudz.phoneinference.PoseResult
import com.battlesbudz.phoneinference.SegResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Typed result of running all four native ONNX models over one image.
 * Coordinates are in original-image pixels, same contract as the
 * :phoneinference module.
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
 * In-process vision backed by the :phoneinference module (native ONNX
 * Runtime). No network, no Termux server — this replaced PhoneVisionClient.
 *
 * The engine is created once per process; the first call pays ~0.5 s of
 * model load. Models are bundled at build time (see phoneinference module),
 * so there is nothing to download at runtime.
 */
object NativeVision {
    private val mutex = Mutex()
    private var engine: InferenceEngine? = null

    private suspend fun engine(assets: AssetManager): InferenceEngine {
        engine?.let { return it }
        return mutex.withLock {
            engine ?: withContext(Dispatchers.IO) { InferenceEngine(assets).also { engine = it } }
        }
    }

    /**
     * Runs detection, segmentation, pose and classification on [imageBytes]
     * (any BitmapFactory-decodable format) on a background thread.
     */
    suspend fun analyze(imageBytes: ByteArray, assets: AssetManager): NativeVisionSnapshot =
        withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw IllegalStateException("Could not decode the attached image.")
            try {
                val eng = engine(assets)
                val (detections, detMs) = eng.detector.detect(bitmap, maxDetections = 5)
                val (segments, segMs) = eng.segmenter.segment(bitmap, maxDetections = 3)
                val (poses, poseMs) = eng.poseDetector.pose(bitmap, maxDetections = 3)
                val (classes, clsMs) = eng.classifier.classify(bitmap)
                NativeVisionSnapshot(
                    detections, detMs,
                    segments, segMs,
                    poses, poseMs,
                    classes.take(3), clsMs,
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
}
