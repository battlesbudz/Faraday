package com.battlesbudz.jarvis.v2.voice

import android.content.res.AssetManager
import java.nio.ByteBuffer

/** Both models are bundled: no download, language model or transcript decision. */
class MicroInterruptionKeywords(assets: AssetManager) : InterruptionKeywordDetector {
    private val detectors = mutableListOf<Pair<String, MicroWakeWord>>()
    init {
        try {
            fun add(keyword: String, file: String, step: Int, cutoff: Float, window: Int) {
                val bytes = assets.open("microwakeword/$file").use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                detectors += keyword to MicroWakeWord(buffer, step, cutoff, window)
            }
            add("Hey_Jarvis", "hey_jarvis.tflite", 10, 0.97f, 5)
            add("stop", "stop.tflite", 10, 0.5f, 5)
        } catch (error: Throwable) { close(); throw error }
    }
    override var lastHitEvidence: String = "none"
        private set
    override val diagnosticSummary get() = detectors.joinToString(" ") { (name, detector) ->
        "$name:score=${detector.probability},ready=${detector.ready},${detector.diagnostics}"
    }
    override val ready get() = detectors.all { it.second.ready }
    override fun accept(pcm: ByteArray): String? {
        require(pcm.size % 2 == 0)
        val samples = ShortArray(pcm.size / 2) { i ->
            ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }
        var match: String? = null
        for ((keyword, detector) in detectors) if (detector.processAudio(samples)) {
            if (match == null) {
                match = keyword // Hey Jarvis wins simultaneous hits.
                lastHitEvidence = "keyword=$keyword probability=${detector.probability} ${detector.diagnostics}"
            }
            // Verification may reject this hit. Do not return the latched event on every
            // subsequent frame and spend all bounded ASR probes on the same acoustic event.
            detector.reset()
        }
        return match
    }
    override fun close() { detectors.forEach { it.second.close() }; detectors.clear() }
}
