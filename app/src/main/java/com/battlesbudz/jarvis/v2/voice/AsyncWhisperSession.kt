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
    // Speech-end boundary decode: when speech stops, the worker decodes the
    // complete speech span at once, during the endpoint window, so finish()
    // can reuse that exact decode instead of starting a fresh full decode
    // after the seal. The decode input is the same full snapshot finish()
    // would use; only post-speech frames (trailing silence the phrase gate
    // appends while the endpoint is judged) are absent from it, and those
    // carry no words. Any resumed speech invalidates the boundary coverage.
    @Volatile private var boundaryCoveredEnd = -1L
    @Volatile private var boundaryValid = false
    private var speechEpoch = 0L
    private var wasSpeech = false
    private data class Result(val end: Long, val text: String, val cue: CompletedEndpointCue? = null)
    override val completedEndpointCue: CompletedEndpointCue? get() = latest.cue
    override val noTextSilenceMs: Long get() = 900
    @Synchronized override fun observeSpeech(speech: Boolean) {
        speechGate.observe(speech)
        if (speech) {
            speechEpoch++
            boundaryValid = false
            if (!active) {
                val tail = audio.snapshot().takeLast(38400).toByteArray()
                audio.clear(); audio.append(tail); total = tail.size.toLong(); active = true
            }
        } else if (wasSpeech) scheduleBoundaryDecode()
        wasSpeech = speech
    }
    /** Runs on the session lock from observeSpeech; never queues behind a busy worker. */
    private fun scheduleBoundaryDecode() {
        if (!active || closed || sealed || captionRetired || failure != null) return
        if (inFlight?.isDone == false) return // Busy: finish() keeps today's fresh decode.
        if (total <= 0 || total == latest.end) return // Nothing uncovered.
        val snapshot = audio.snapshot(); val end = total
        scheduled = end
        submitPartialDecode(snapshot, end, receivedPcmBytes / 2, boundaryEpoch = speechEpoch)
        log("whisper_boundary_scheduled audioMs=${snapshot.size / 32}")
    }
    private fun submitPartialDecode(snapshot: ByteArray, end: Long, coveredSamples: Long, boundaryEpoch: Long?) {
        val queuedAt = System.nanoTime()
        inFlight = worker.submit {
            try {
                val began = System.nanoTime()
                val text = work.measure(AsrRecognitionWorkAccumulator.Phase.PARTIAL, snapshot.size / 2) { decode(snapshot) }
                synchronized(this@AsyncWhisperSession) {
                    if (closed) throw CancellationException("Whisper session closed")
                    stable = agreeingPrefix(latest.text, text)
                    latest = Result(end, text, CompletedEndpointCue(++completedRevision, text, coveredSamples))
                    if (boundaryEpoch != null && boundaryEpoch == speechEpoch) {
                        boundaryCoveredEnd = end
                        boundaryValid = true
                    }
                }
                log("whisper_partial recognition=provisional queueWaitMs=${(began - queuedAt) / 1_000_000} audioMs=${snapshot.size / 32} decodeMs=${(System.nanoTime()-began)/1_000_000} stableChars=${stable.length} pendingWindows=0")
            } catch (error: Throwable) { failure = error }
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
            submitPartialDecode(snapshot, end, coveredSamples, boundaryEpoch = null)
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
                // Reuse the speech-end boundary decode when it is still the
                // latest result: every byte past its coverage arrived after
                // speech stopped (any resumption invalidates it), so its text
                // is the final transcript of the complete speech span.
                val reused = latest.end == end ||
                    (boundaryValid && boundaryCoveredEnd == latest.end)
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
