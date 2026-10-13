package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream

/** Only the duplex probe uses this budget; ordinary dictation keeps its existing cadence. */
class PacedStreamingTranscriber(
    private val delegate: StreamingTranscriber,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : StreamingTranscriber {
    private val pending = ByteArrayOutputStream()
    private var nextPassAt = 0L
    private var text = ""
    override fun observeSpeech(speech: Boolean) = delegate.observeSpeech(speech)
    override fun accept(pcm: ByteArray): String {
        check(pending.size() + pcm.size <= 16_000 * 2 * 8) { "Interruption ASR exceeded its audio budget" }
        pending.write(pcm)
        if (pending.size() >= 16_000 && nowMs() >= nextPassAt) flush()
        return text
    }
    private fun flush() {
        if (pending.size() == 0) return
        val audio = pending.toByteArray()
        pending.reset()
        val started = nowMs()
        text = delegate.accept(audio)
        val workMs = (nowMs() - started).coerceAtLeast(0)
        val recoveryMs = workMs.coerceIn(250, 1500)
        nextPassAt = nowMs() + recoveryMs
        log("barge_asr_budget audioMs=${audio.size / 32} workMs=$workMs recoveryMs=$recoveryMs")
    }
    override fun finish(): String { flush(); return delegate.finish() }
    override fun recover(pcm: ByteArray) = delegate.recover(pcm)
    override fun close() { pending.reset(); delegate.close() }
}
