package com.battlesbudz.jarvis.v2.voice.speaker

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

class SherpaSpeakerEmbedder(model: File) : SpeakerEmbedder {
    private val modelPath = model.absolutePath
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    @Synchronized private fun extractor(): SpeakerEmbeddingExtractor = extractor ?: run {
        val config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = 2, debug = false, provider = "cpu")
        SpeakerEmbeddingExtractor(assetManager = null, config = config).also { extractor = it }
    }

    override fun embed(samples: FloatArray, sampleRate: Int): FloatArray? = runCatching {
        require(sampleRate == 16_000 && samples.isNotEmpty())
        val e = extractor()
        val stream = e.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            check(e.isReady(stream))
            e.compute(stream)
        } finally { stream.release() }
    }.getOrNull()

    @Synchronized override fun close() { extractor?.release(); extractor = null }
}

class SpeakerGate(
    private val profiles: SpeakerProfileStore,
    private val embedder: SpeakerEmbedder?
) {
    fun isEnrolled(): Boolean = profiles.load() != null

    fun evaluate(wav: ByteArray): SpeakerDecision {
        val profile = profiles.load() ?: return SpeakerGatePolicy.decide(false, null, 0)
        val decoded = WavPcm.decode(wav) ?: return SpeakerGatePolicy.decide(true, null, 0)
        val (samples, rate) = decoded
        val duration = samples.size * 1000L / rate
        val embedding = runCatching { embedder?.embed(samples, rate) }.getOrNull()
        val score = embedding?.let { SpeakerGatePolicy.cosine(profile.embedding, it) }
        return SpeakerGatePolicy.decide(true, score, duration)
    }

    companion object {
        @Volatile private var instance: SpeakerGate? = null
        fun get(context: Context): SpeakerGate = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val model = File(app.filesDir, "voice-models/speaker-campplus-en/model.onnx")
                SpeakerGate(SpeakerProfileStore(File(app.filesDir, "speaker-gate")),
                    if (model.isFile) runCatching { SherpaSpeakerEmbedder(model) }.getOrNull() else null)
                    .also { instance = it }
            }
        }
        internal fun setForTests(gate: SpeakerGate?) { instance = gate }
        internal fun refresh(context: Context) { instance = null; get(context) }
    }
}
