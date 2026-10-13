package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exercise the actual call input gates on saved audio, without simulating turn endpointing. */
internal suspend fun recognizeCallAdapterClip(transcriber: StreamingTranscriber, pcm: ByteArray,
                                             detector: SpeechDetector, log: (String) -> Unit): String {
    try {
        require(pcm.isNotEmpty() && pcm.size <= 12 * 32000 && pcm.size % 2 == 0)
        val gate = CaptureSpeechGate()
        var previous = ""
        for (offset in pcm.indices step 3200) {
            currentCoroutineContext().ensureActive()
            val chunk = pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size))
            val decision = gate.accept(detector.accept(chunk), Pcm16Signal.measure(chunk).rms, (offset + chunk.size) / 32L)
            transcriber.observeSpeech(decision.isSpeech)
            val partial = transcriber.accept(chunk, allowPartial = decision.probability >= .15f)
            if (partial != previous) {
                log("partial inputMs=${(offset + chunk.size) / 32} text=${partial.take(1000)}")
                previous = partial
            }
        }
        currentCoroutineContext().ensureActive()
        return transcriber.finish()
    } finally { transcriber.close() }
}
