package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream

/** Opt-in diagnostic only. Streaming appends; a batch/recovery replaces the decode window. */
class RecognitionAudioEvidence(private val maxBytes: Int = 25 * 32000) {
    private val audio = ByteArrayOutputStream()
    var mode: String = "none"
        private set
    var truncated: Boolean = false
        private set
    @Synchronized fun record(pcm: ByteArray, replace: Boolean) {
        if (replace) { audio.reset(); truncated = false }
        mode = if (replace) "latest_batch_window" else "streamed_decoder_input"
        val count = minOf(pcm.size, (maxBytes - audio.size()).coerceAtLeast(0))
        audio.write(pcm, 0, count)
        if (count < pcm.size) truncated = true
    }
    @Synchronized fun snapshot(): ByteArray = audio.toByteArray()
}
