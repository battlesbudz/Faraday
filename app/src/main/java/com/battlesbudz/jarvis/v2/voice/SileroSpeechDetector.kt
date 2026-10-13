package com.battlesbudz.jarvis.v2.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Bundled local VAD: no speech transcription, network, or model download. */
object SileroSpeechDetector {
    fun create(assets: AssetManager): SpeechDetector {
        val model = Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "voice/silero_vad.onnx",
                    windowSize = 512
                ),
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu"
            )
        )
        return FrameSpeechDetector(model::compute, model::release)
    }
}
