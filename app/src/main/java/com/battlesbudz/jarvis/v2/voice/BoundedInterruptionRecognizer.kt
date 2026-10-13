package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** One optional native owner. Capture never waits for inference or queues more work. */
class BoundedInterruptionRecognizer(
    private val scope: CoroutineScope,
    private val create: () -> StreamingTranscriber,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    // Load has its own bound; only ASR accept/finalization consume the decode allowance.
    private val budgetMs: Long = InterruptionTiming.DECODE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
    private val hasBudget: () -> Boolean = { true },
    private val canContinue: () -> Boolean = hasBudget
) {
    data class Result(val revision: Long, val text: String, val audioAtMs: Long, val workMs: Long)
    private val result = AtomicReference<Result?>()
    @Volatile var unavailable: Boolean = false
        private set
    @Volatile var retryableFailure: Boolean = false
        private set
    private class PlaybackPressure : Exception()
    private class DecodeBudget : Exception()
    private class LoadBudget : Exception()
    private class StaleResult : Exception()
    @Volatile var retryReason: String = "none"
        private set
    private var job: Job? = null
    private var closed = false
    val busy: Boolean get() = job?.isCompleted == false

    fun submit(revision: Long, pcm: ByteArray, audioAtMs: Long): Boolean {
        if (closed || unavailable || busy) return false
        require(pcm.size in 2..128_000 && pcm.size % 2 == 0)
        val owned = pcm.copyOf()
        result.set(null)
        retryableFailure = false
        retryReason = "none"
        job = scope.launch(dispatcher) {
            val started = nowMs()
            var asr: StreamingTranscriber? = null
            var phase = "admission"
            var outcome = "cancelled"
            var loadMs = 0L
            var decodeMs = 0L
            var releaseMs = 0L
            var feedMs = 0L
            var finalizeMs = 0L
            var acceptCalls = 0
            var maxAcceptMs = 0L
            var decodeStage = "not_started"
            var decodeStarted: Long? = null
            fun release() {
                val ownedModel = asr ?: return
                asr = null // Never release a failed native owner twice.
                val releaseStarted = nowMs()
                try { ownedModel.close() } finally { releaseMs += (nowMs() - releaseStarted).coerceAtLeast(0) }
            }
            fun checkBudget(admitting: Boolean = false) {
                ensureActive()
                if (!(if (admitting) hasBudget() else canContinue())) throw PlaybackPressure()
                if (decodeStarted?.let { nowMs() - it > budgetMs } == true) throw DecodeBudget()
            }
            try {
                if (started - audioAtMs > InterruptionTiming.START_AGE_MS) {
                    retryableFailure = true
                    retryReason = "queued_audio_too_old"
                    outcome = retryReason
                    log("barge_probe_deferred reason=queued_audio_too_old retryable=true fallback=keyword")
                    return@launch
                }
                checkBudget(admitting = true)
                phase = "load"
                val loadStarted = nowMs()
                try { asr = create() } finally { loadMs = (nowMs() - loadStarted).coerceAtLeast(0) }
                ensureActive()
                if (loadMs > InterruptionTiming.LOAD_MS) throw LoadBudget()
                phase = "decode"
                decodeStarted = nowMs()
                checkBudget()
                val transcriber = requireNotNull(asr)
                decodeStage = "prepare"
                val mode = transcriber.prepareForBoundedProbe(4000)
                log("barge_probe_policy revision=$revision mode=$mode maxAudioMs=4000")
                checkBudget()
                transcriber.observeSpeech(true)
                // Bound work between cancellation/budget checks; native calls themselves
                // cannot be preempted. Never close their model from the capture thread.
                var offset = 0
                while (offset < owned.size) {
                    val end = minOf(offset + 8000, owned.size)
                    decodeStage = "accept"
                    val acceptStarted = nowMs()
                    acceptCalls++
                    try { transcriber.accept(owned.copyOfRange(offset, end)) } finally {
                        val elapsed = (nowMs() - acceptStarted).coerceAtLeast(0)
                        feedMs += elapsed
                        maxAcceptMs = maxOf(maxAcceptMs, elapsed)
                    }
                    offset = end
                    checkBudget()
                }
                decodeStage = "finish"
                val finishStarted = nowMs()
                val text = try { TranscriptContent.speech(transcriber.finish()) }
                    finally { finalizeMs = (nowMs() - finishStarted).coerceAtLeast(0) }
                checkBudget()
                decodeMs = (nowMs() - requireNotNull(decodeStarted)).coerceAtLeast(0)
                phase = "release"
                release()
                ensureActive()
                // Cleanup is not decode time, but a result must still belong to recent audio.
                if (nowMs() - audioAtMs > InterruptionTiming.RESULT_AGE_MS) throw StaleResult()
                val workMs = (nowMs() - started).coerceAtLeast(0)
                outcome = "result"
                result.set(Result(revision, text, audioAtMs, workMs))
                log("barge_probe_result revision=$revision audioMs=${owned.size / 32} workMs=$workMs chars=${text.length}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (load: LoadBudget) {
                retryableFailure = true; retryReason = "load_budget"; outcome = retryReason
                log("barge_probe_deferred reason=load_budget retryable=true fallback=keyword")
            }
            catch (stale: StaleResult) {
                retryableFailure = true; retryReason = "result_too_old"; outcome = retryReason
                log("barge_probe_deferred reason=result_too_old retryable=true fallback=keyword")
            }
            catch (pressure: PlaybackPressure) {
                retryableFailure = true
                retryReason = "playback_budget"
                outcome = retryReason
                log("barge_probe_deferred reason=playback_budget retryable=true fallback=keyword")
            }
            catch (budget: DecodeBudget) {
                retryableFailure = true
                retryReason = "decode_budget"
                outcome = retryReason
                log("barge_probe_deferred reason=decode_budget retryable=true fallback=keyword")
            }
            catch (error: Exception) {
                outcome = "native_failure"
                unavailable = true
                log("barge_natural_unavailable reason=${error.message} fallback=keyword")
            } finally {
                if (phase == "decode") decodeMs = (nowMs() - requireNotNull(decodeStarted)).coerceAtLeast(0)
                runCatching { release() }.onFailure {
                    outcome = "release_failure"
                    unavailable = true
                    log("barge_natural_unavailable reason=model_release fallback=keyword")
                }
                log("barge_probe_timing revision=$revision loadMs=$loadMs decodeMs=$decodeMs releaseMs=$releaseMs " +
                    "totalMs=${(nowMs() - started).coerceAtLeast(0)} phase=$phase outcome=$outcome " +
                    "loadLimitMs=${InterruptionTiming.LOAD_MS} decodeLimitMs=$budgetMs " +
                    "feedMs=$feedMs finalizeMs=$finalizeMs acceptCalls=$acceptCalls maxAcceptMs=$maxAcceptMs decodeStage=$decodeStage")
            }
        }
        return true
    }
    fun poll(): Result? = result.getAndSet(null)
    suspend fun close() {
        closed = true
        withContext(NonCancellable) { job?.cancelAndJoin() }
        result.set(null)
    }
}
