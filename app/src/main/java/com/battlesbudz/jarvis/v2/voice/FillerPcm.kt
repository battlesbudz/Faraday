package com.battlesbudz.jarvis.v2.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Reject silence/corrupt cache entries and make short acknowledgements consistently audible. */
internal object FillerPcm {
    fun prepare(audio: SpeechAudio): SpeechAudio {
        require(audio.sampleRate in 8000..48000 && audio.pcm.size in 1..audio.sampleRate * 4)
        val peak = audio.pcm.maxOf { abs(it.toInt()) }
        val first = audio.pcm.indexOfFirst { abs(it.toInt()) >= 64 }
        val last = audio.pcm.indexOfLast { abs(it.toInt()) >= 64 }
        require(first >= 0 && last - first >= audio.sampleRate / 20) { "Filler contains no usable speech." }
        // Keep a short margin; remove padded silence that hides an acknowledgement's onset.
        val start = (first - audio.sampleRate / 50).coerceAtLeast(0)
        val end = (last + audio.sampleRate / 20 + 1).coerceAtMost(audio.pcm.size)
        val pcm = audio.pcm.copyOfRange(start, end)
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        require(rms >= 32) { "Filler audio is too quiet." }
        val gain = minOf(4.0, 0.10 * Short.MAX_VALUE / rms, 0.95 * Short.MAX_VALUE / peak).coerceAtLeast(1.0)
        return audio.copy(pcm = ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32767, 32767).toShort() })
    }
    fun firstSpeechFrame(pcm: ShortArray) = pcm.indexOfFirst { abs(it.toInt()) >= 64 }
    fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size.coerceAtLeast(1)) / Short.MAX_VALUE
}
