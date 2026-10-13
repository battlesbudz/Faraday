package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * Converts text to validated PCM on the producer's native dispatcher.
 *
 * The caller exclusively owns [tts] and releases its lease only after generation returns.
 * This collaborator never acquires, releases, queues or plays the model's output. The
 * native whole-passage call returns before cancellation checks or lease release.
 */
internal class PiperSpeechSynthesizer(
    private val tts: OfflineTts,
    private val generation: GenerationConfig,
    private val owner: CoroutineContext,
    private val log: (String) -> Unit
) {
    fun synthesize(text: String): SpeechAudio {
        owner.ensureActive()
        val started = System.nanoTime()
        log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
        val generated = tts.generateWithConfig(text, generation)
        owner.ensureActive()
        val rate = generated.sampleRate
        val pcm = SynthesizedSpeechPcm.fromModel(generated.samples, rate)
        log("tts_pcm_level rms=${pcm.rms} peak=${pcm.peak} frames=${pcm.samples.size} nonFinite=0 clipped=${pcm.clippedSamples} " +
            "leadingSilenceMs=${pcm.leadingSilenceFrames * 1000L / rate} trailingSilenceMs=${pcm.trailingSilenceFrames * 1000L / rate}")
        return SpeechAudio(text, rate, pcm.samples, (System.nanoTime() - started) / 1_000_000)
    }
}
