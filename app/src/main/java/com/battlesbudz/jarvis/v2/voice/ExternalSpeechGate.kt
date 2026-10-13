package com.battlesbudz.jarvis.v2.voice

/** External VAD owns the decoder's acoustic window, not only its first onset.
 * Preserve 240 ms before confirmed speech and 320 ms after it. Long quiet spans
 * stay out of the ungated native decoder; original call audio is retained separately.
 * Call phrase mode instead keeps 1200 ms before onset and contiguous PCM
 * through finalization; its caller bounds segment duration. Short final-only
 * probes without observations retain their original samples.
 */
class ExternalSpeechGate(
    preRollMs: Long = 240,
    private val preservePhrase: Boolean = false
) {
    private val preRoll = RollingAudioBuffer(maxDurationMs = preRollMs)
    private var observed = false
    private var speech = false
    private var tailBytes = 0
    private var phraseStarted = false
    var acceptedBytes = 0L
        private set
    var receivedBytes = 0L
        private set
    fun observe(speech: Boolean) { observed = true; this.speech = speech }
    fun accept(pcm: ByteArray): ByteArray {
        receivedBytes += pcm.size
        val result = when {
            !observed -> pcm
            speech -> {
                phraseStarted = true
                tailBytes = 320 * 32
                (preRoll.snapshot() + pcm).also { preRoll.clear() }
            }
            // Final transcription needs contiguous phonetic context, including
            // pauses and quiet endings that external VAD may call non-speech.
            preservePhrase && phraseStarted -> pcm
            else -> {
                val count = minOf(tailBytes, pcm.size)
                tailBytes -= count
                if (count < pcm.size) preRoll.append(pcm.copyOfRange(count, pcm.size))
                pcm.copyOfRange(0, count)
            }
        }
        acceptedBytes += result.size
        return result
    }
    fun clear() { preRoll.clear(); observed = false; speech = false; tailBytes = 0; phraseStarted = false }

    companion object {
        fun completePhrase() = ExternalSpeechGate(preRollMs = 1200, preservePhrase = true)
    }
}
