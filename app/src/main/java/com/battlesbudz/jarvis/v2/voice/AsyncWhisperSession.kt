package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CancellationException

/** Single native worker. Live input never waits for decode; busy workers skip obsolete windows.
 * Windows overlap and grow with this bounded utterance, so partials do not duplicate boundary words. */
internal class AsyncWhisperSession(
    private val decode: (ByteArray) -> String,
    private val release: () -> Unit,
    private val log: (String) -> Unit = {},
    workClockNs: () -> Long = System::nanoTime,
    private val cancel: () -> Unit = {}
) : StreamingTranscriber {
    private val work = AsrRecognitionWorkAccumulator("whisper_batch_decode_callback_wall", workClockNs)
    override val recognitionWorkMetrics get() = work.snapshot()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "jarvis-whisper").apply { isDaemon = true } }
    private var audio = RollingAudioBuffer(AudioFormat(16000), maxDurationMs = 25000)
    private val speechGate = ExternalSpeechGate.completePhrase()
    private var active = false
    private var total = 0L
    private var receivedPcmBytes = 0L
    private var completedRevision = 0L
    private var scheduled = 0L
    private var inFlight: Future<*>? = null
    @Volatile private var latest = Result(-1, "")
    @Volatile private var stable = ""
    @Volatile private var failure: Throwable? = null
    private var closed = false
    private var sealed = false
    private var captionRetired = false
    private var closing: Future<*>? = null
    private data class Result(val end: Long, val text: String, val cue: CompletedEndpointCue? = null)
    override val completedEndpointCue: CompletedEndpointCue? get() = latest.cue
    override val noTextSilenceMs: Long get() = 900
    @Synchronized override fun observeSpeech(speech: Boolean) {
        speechGate.observe(speech)
        if (speech && !active) {
            val tail = audio.snapshot().takeLast(38400).toByteArray()
            audio.clear(); audio.append(tail); total = tail.size.toLong(); active = true
        }
    }
    override fun accept(pcm: ByteArray): String = accept(pcm, true)
    @Synchronized override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
        check(!closed && !sealed)
        failure?.let { throw IllegalStateException("Whisper background decoding failed", it) }
        receivedPcmBytes += pcm.size
        val qualified = speechGate.accept(pcm)
        audio.append(qualified); total += qualified.size
        if (allowPartial && active && total >= 48000 && total - scheduled >= 38400 && inFlight?.isDone != false) {
            val snapshot = audio.snapshot(); val end = total
            val coveredSamples = receivedPcmBytes / 2
            scheduled = end
            val queuedAt = System.nanoTime()
            inFlight = worker.submit {
                try {
                    val began = System.nanoTime()
                    val text = work.measure(AsrRecognitionWorkAccumulator.Phase.PARTIAL, snapshot.size / 2) { decode(snapshot) }
                    synchronized(this@AsyncWhisperSession) {
                        if (closed) throw CancellationException("Whisper session closed")
                        stable = agreeingPrefix(latest.text, text)
                        latest = Result(end, text, CompletedEndpointCue(++completedRevision, text, coveredSamples))
                    }
                    log("whisper_partial recognition=provisional queueWaitMs=${(began - queuedAt) / 1_000_000} audioMs=${snapshot.size / 32} decodeMs=${(System.nanoTime()-began)/1_000_000} stableChars=${stable.length} pendingWindows=0")
                } catch (error: Throwable) { failure = error }
            }
        }
        return stable
    }
    @Synchronized override fun retireIdleCaption(): Boolean {
        // This is the same admission lock as accept/finish/close. A completed Future
        // alone would race a new submission; sealing under the lock prevents that.
        if (closed || sealed || failure != null || inFlight?.isDone == false) return false
        sealed = true
        captionRetired = true
        log("whisper_caption_retired finalDecodeSkipped=true previousDecodeIdle=true finalTextAuthoritative=false")
        return true
    }
    @Synchronized override fun resumeRetiredCaption(): Boolean {
        if (closed || !captionRetired || failure != null) return false
        check(sealed && inFlight?.isDone != false)
        captionRetired = false
        sealed = false
        return true
    }
    override fun finish(): String {
        val final = synchronized(this) {
            check(!captionRetired) { "Retired display captions cannot become final recognition." }
            check(!closed); sealed = true
            val snapshot = audio.snapshot(); val end = total
            val queuedAt = System.nanoTime()
            worker.submit<String> {
                val queueWaitMs = (System.nanoTime() - queuedAt) / 1_000_000
                failure?.let { throw IllegalStateException("Whisper background decoding failed", it) }
                val reused = latest.end == end
                val began = System.nanoTime()
                val result = if (reused) latest.text else work.measure(AsrRecognitionWorkAccumulator.Phase.FINAL, snapshot.size / 2) { decode(snapshot) }
                synchronized(this@AsyncWhisperSession) { if (closed) throw CancellationException("Whisper session closed") }
                log("whisper_final recognition=committed reused=$reused queueWaitMs=$queueWaitMs audioMs=${snapshot.size / 32} decodeMs=${(System.nanoTime()-began)/1_000_000}")
                result
            }.also { inFlight = it }
        }
        val result = final.get()
        synchronized(this) { if (closed) throw CancellationException("Whisper session closed") }
        return result
    }
    override fun recover(pcm: ByteArray): String {
        val recovery = synchronized(this) {
            check(!closed && !captionRetired)
            sealed = true
            worker.submit<String> {
                work.measure(AsrRecognitionWorkAccumulator.Phase.RECOVERY, pcm.size / 2) { decode(pcm) }
            }.also { inFlight = it }
        }
        val result = recovery.get()
        synchronized(this) { if (closed) throw CancellationException("Whisper session closed") }
        return result
    }
    override fun close() {
        val releaseTask = synchronized(this) {
            closing ?: run {
                closed = true
                if (inFlight?.isDone == false) cancel()
                audio.clear(); speechGate.clear()
                // The same admission lock seals submission before queueing release.
                worker.submit { release() }.also { closing = it }
            }
        }
        // Never release a recognizer underneath JNI; no monitor is held while joining.
        try { releaseTask.get() } finally { worker.shutdown() }
    }
    companion object {
        fun agreeingPrefix(previous: String, current: String): String {
            val a = previous.trim().split(Regex("\\s+")); val b = current.trim().split(Regex("\\s+"))
            var count = 0
            while (count < minOf(a.size, b.size) && a[count].equals(b[count], ignoreCase = true)) count++
            return b.take(count).joinToString(" ").trim()
        }
    }
}
