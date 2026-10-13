package com.battlesbudz.jarvis.v2.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Validated model output, before queuing, caching or writing any audio to Android. */
internal data class SynthesizedSpeechPcm(
    val samples: ShortArray,
    val rms: Double,
    val peak: Float,
    val clippedSamples: Int,
    val leadingSilenceFrames: Int,
    val trailingSilenceFrames: Int
) {
    companion object {
        /** Keep the existing signed PCM16 conversion and audible-frame threshold. */
        fun fromModel(samples: FloatArray, sampleRate: Int): SynthesizedSpeechPcm {
            check(sampleRate > 0) { "Voice model returned an invalid sample rate." }
            check(samples.all { it.isFinite() }) { "Voice model returned non-finite PCM." }
            val pcm = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            val leading = pcm.indexOfFirst { abs(it.toInt()) >= 64 }.let { if (it < 0) pcm.size else it }
            val trailing = pcm.indexOfLast { abs(it.toInt()) >= 64 }.let { pcm.size - it - 1 }
            return SynthesizedSpeechPcm(
                pcm,
                sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1)),
                samples.maxOfOrNull { abs(it) } ?: 0f,
                samples.count { abs(it) >= 1f },
                leading,
                trailing
            )
        }
    }
}
