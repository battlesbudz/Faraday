package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One bounded capture-to-native worker. No tool dispatch, microphone ownership or
 * LLM prefill. The turn must retain its model/artifact lease until close returns
 * true; false means quarantine, never permission to release or reuse resources.
 *
 * The independent worker is deliberately not cancelled with the caller: a
 * coroutine cancellation cannot join a synchronous JNI borrower. All native
 * operations and destruction remain ordered on the worker, while cancel is a
 * fast signal only. T is the immutable sealed SDK content, not provisional rows.
 */
internal class RetainedPcmEncoderWorker<T : Any>(
    private val createEncoder: (candidate: Long) -> Encoder<T>,
    private val isGenerationCurrent: () -> Boolean,
    private val maxQueuedBytes: Int = 64_000,
    private val maxEvents: Int = 64,
    private val nativeCloseTimeoutMs: Long = 10_000,
    private val nowNs: () -> Long = System::nanoTime,
    private val onWork: (WorkSnapshot) -> Unit = {},
) : RetainedPcmObserver {
    /** API-wall work, not individual graph steps or LLM multimodal prefill. */
    data class WorkSnapshot(val phase: String, val busy: Boolean, val appendCalls: Int,
        val appendWorkNs: Long, val maxAppendWorkNs: Long, val sealWorkNs: Long,
        val lastWorkNs: Long, val queuedBytes: Int, val peakQueuedBytes: Int)
    interface Encoder<T> {
        fun append(pcm: FloatArray)
        fun seal(): Sealed<T>
        /** Must not wait for inference or hold the blocking native-operation lock. */
        fun requestCancel()
        fun closeOnWorker(timeoutMs: Long): Boolean
    }
    data class Sealed<T>(val pcmSampleCount: Int, val content: T, val timing: NativeAudioCaptureTiming? = null)
    data class Completed<T>(val candidate: Long, val pcmSampleCount: Int, val content: T, val timing: NativeAudioCaptureTiming? = null)
    private enum class Phase { ACCEPTING, SEAL_QUEUED, SEALED, INVALID, CLOSED }
    private sealed interface Event {
        data class Pcm(val candidate: Long, val bytes: ByteArray) : Event
        data class Reset(val candidate: Long) : Event
        data class Seal(val candidate: Long, val bytes: Int, val digest: ByteArray) : Event
    }
    private class Active<T>(val candidate: Long) { @Volatile var encoder: Encoder<T>? = null }
    private val gate = Any()
    private var phase = Phase.ACCEPTING
    private var candidate = 1L
    private var acceptedBytes = 0
    private var queuedBytes = 0
    private var peakQueuedBytes = 0
    private var failure: Throwable? = null
    private val active = AtomicReference<Active<T>?>(null)
    private val completed = CompletableDeferred<Completed<T>>()
    private val drained = CompletableDeferred<Boolean>()
    private val queue = Channel<Event>(maxEvents)
    private val workerJob = SupervisorJob()
    private val workerDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvisNativeAudioEncoder").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    init {
        require(maxQueuedBytes in 38_400..960_000 && maxQueuedBytes % 2 == 0)
        require(maxEvents in 1..(maxQueuedBytes / 2 + 2))
        require(nativeCloseTimeoutMs in 0..300_000)
        CoroutineScope(workerJob + workerDispatcher).launch { runWorker() }
    }

    override fun onPcm(retainedPcm16: ByteArray) {
        var error: Throwable? = null
        synchronized(gate) {
            try {
                checkAccepting()
                require(retainedPcm16.isNotEmpty() && retainedPcm16.size % 2 == 0) { "invalid_retained_pcm16" }
                check(retainedPcm16.size <= maxQueuedBytes - queuedBytes) { "native_audio_queue_capacity" }
                check(retainedPcm16.size <= 960_000 - acceptedBytes) { "native_audio_sample_budget" }
                // Capture transferred a private array. The worker now owns and wipes it.
                check(queue.trySend(Event.Pcm(candidate, retainedPcm16)).isSuccess) { "native_audio_queue_capacity" }
                queuedBytes += retainedPcm16.size
                peakQueuedBytes = maxOf(peakQueuedBytes, queuedBytes)
                acceptedBytes += retainedPcm16.size
            } catch (caught: Throwable) { error = caught }
        }
        error?.let {
            retainedPcm16.fill(0)
            invalidate(it)
            throw it
        }
    }

    override fun onCandidateDiscarded() {
        var discarded = 0L
        try {
            synchronized(gate) {
                checkAccepting()
                discarded = candidate
                check(candidate < Long.MAX_VALUE) { "native_audio_candidate_overflow" }
                candidate++
                acceptedBytes = 0
                check(queue.trySend(Event.Reset(candidate)).isSuccess) { "native_audio_queue_capacity" }
            }
            active.get()?.takeIf { it.candidate == discarded }?.encoder?.requestCancel()
        } catch (error: Throwable) { invalidate(error); throw error }
    }

    override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) {
        invalidate(IllegalStateException("native_audio_capture_${reason.name.lowercase()}"))
    }

    /** Call only after capture.stop has joined and the complete PCM has passed admission. */
    suspend fun sealAfterCaptureJoined(completePcm16: ByteArray): Completed<T> = sealCompleteCandidate(completePcm16)

    /** Explicit capture-owned frozen boundary; no further PCM may reach this worker.
     * The caller must separately certify the retained tail before answer promotion.
     * This does not weaken count/hash/EOA or native checked-close validation. */
    suspend fun sealFrozenCandidate(frozenPcm16: ByteArray): Completed<T> = sealCompleteCandidate(frozenPcm16)

    private suspend fun sealCompleteCandidate(completePcm16: ByteArray): Completed<T> {
        try {
            synchronized(gate) {
                checkAccepting()
                check(completePcm16.isNotEmpty() && completePcm16.size == acceptedBytes) { "native_audio_complete_pcm_count" }
                check(completePcm16.size % 2 == 0) { "invalid_retained_pcm16" }
                val digest = MessageDigest.getInstance("SHA-256").digest(completePcm16)
                check(queue.trySend(Event.Seal(candidate, acceptedBytes, digest)).isSuccess) { "native_audio_queue_capacity" }
                phase = Phase.SEAL_QUEUED
            }
            val result = completed.await()
            synchronized(gate) {
                check(phase == Phase.SEALED && isGenerationCurrent()) { "native_audio_stale_generation" }
            }
            return result
        } catch (error: Throwable) { invalidate(error); throw error }
    }

    /** Fast terminal signal. No native work, joins, or model/lease release here. */
    fun requestCancel() = invalidate(IllegalStateException("native_audio_cancelled"))

    /** False retains native owners and must retain the encompassing model lease. */
    suspend fun closeAndDrain(timeoutMs: Long = 10_000): Boolean {
        require(timeoutMs in 0..300_000)
        synchronized(gate) {
            if (phase != Phase.SEALED && phase != Phase.CLOSED) {
                failure = failure ?: IllegalStateException("native_audio_closed_before_seal")
                phase = Phase.INVALID
                completed.completeExceptionally(requireNotNull(failure))
            }
            queue.close()
        }
        active.get()?.encoder?.let { runCatching { it.requestCancel() } }
        val result = if (drained.isCompleted) drained.await() else withTimeoutOrNull(timeoutMs) { drained.await() } ?: false
        if (result) synchronized(gate) { phase = Phase.CLOSED }
        return result
    }

    private fun checkAccepting() {
        check(phase == Phase.ACCEPTING) { "native_audio_not_accepting" }
        check(isGenerationCurrent()) { "native_audio_stale_generation" }
    }

    private fun invalidate(error: Throwable) {
        synchronized(gate) {
            if (phase != Phase.CLOSED) {
                failure = failure ?: error
                phase = Phase.INVALID
                completed.completeExceptionally(requireNotNull(failure))
                queue.close()
            }
        }
        active.get()?.encoder?.let { runCatching { it.requestCancel() } }
    }

    private fun isWanted(epoch: Long): Boolean = synchronized(gate) {
        phase != Phase.INVALID && phase != Phase.CLOSED && candidate == epoch && isGenerationCurrent()
    }

    private suspend fun runWorker() {
        var current: Active<T>? = null
        var digest = MessageDigest.getInstance("SHA-256")
        var consumedBytes = 0
        var safeToRelease = true
        var closeFailed = false
        var appendCalls = 0
        var appendWorkNs = 0L
        var maxAppendWorkNs = 0L
        var sealWorkNs = 0L
        fun work(phase: String, busy: Boolean, elapsedNs: Long = 0) {
            val snapshot = synchronized(gate) {
                WorkSnapshot(phase, busy, appendCalls, appendWorkNs, maxAppendWorkNs,
                    sealWorkNs, elapsedNs, queuedBytes, peakQueuedBytes)
            }
            runCatching { onWork(snapshot) }
        }
        fun closeCurrent(): Boolean {
            if (closeFailed) return false
            val old = current ?: return true
            val ok = try { old.encoder?.closeOnWorker(nativeCloseTimeoutMs) ?: true } catch (_: Throwable) { false }
            if (ok) { active.compareAndSet(old, null); current = null }
            else closeFailed = true
            return ok
        }
        try {
            for (event in queue) {
                if (event is Event.Pcm) synchronized(gate) { queuedBytes -= event.bytes.size }
                when (event) {
                    is Event.Pcm -> try {
                        if (!isWanted(event.candidate)) continue
                        if (current?.candidate != event.candidate) {
                            check(closeCurrent()) { "native_audio_previous_owner_not_drained" }
                            val created = Active<T>(event.candidate)
                            current = created
                            active.set(created)
                            created.encoder = createEncoder(event.candidate)
                            digest = MessageDigest.getInstance("SHA-256")
                            consumedBytes = 0
                        }
                        if (!isWanted(event.candidate)) { current?.encoder?.requestCancel(); continue }
                        digest.update(event.bytes)
                        var offset = 0
                        while (offset < event.bytes.size) {
                            check(isWanted(event.candidate)) { "native_audio_cancelled_during_packet" }
                            val samples = minOf(16_000, (event.bytes.size - offset) / 2)
                            val floats = FloatArray(samples) { index ->
                                val at = offset + index * 2
                                (((event.bytes[at].toInt() and 255) or (event.bytes[at + 1].toInt() shl 8)).toShort().toInt()) / 32768.0f
                            }
                            work("append", true)
                            val appendStarted = nowNs()
                            try { requireNotNull(current?.encoder).append(floats) } finally {
                                val elapsed = (nowNs() - appendStarted).coerceAtLeast(0)
                                appendCalls++; appendWorkNs += elapsed
                                maxAppendWorkNs = maxOf(maxAppendWorkNs, elapsed)
                                floats.fill(0f)
                                work("append", false, elapsed)
                            }
                            offset += samples * 2
                        }
                        consumedBytes += event.bytes.size
                    } catch (error: Throwable) {
                        // A discarded candidate may finish a synchronous native
                        // call with cancellation/error. Only its fresh replacement
                        // remains eligible; never poison that replacement by reuse.
                        if (isWanted(event.candidate)) throw error
                    } finally { event.bytes.fill(0) }
                    is Event.Reset -> {
                        if (!closeCurrent()) error("native_audio_discard_not_drained")
                        digest = MessageDigest.getInstance("SHA-256")
                        consumedBytes = 0
                    }
                    is Event.Seal -> {
                        check(isWanted(event.candidate)) { "native_audio_stale_seal" }
                        check(current?.candidate == event.candidate && consumedBytes == event.bytes) { "native_audio_consumed_pcm_count" }
                        check(MessageDigest.isEqual(digest.digest(), event.digest)) { "native_audio_complete_pcm_mismatch" }
                        work("seal", true)
                        val sealStarted = nowNs()
                        val result = try { requireNotNull(current?.encoder).seal() } finally {
                            val elapsed = (nowNs() - sealStarted).coerceAtLeast(0)
                            sealWorkNs += elapsed
                            work("seal", false, elapsed)
                        }
                        check(result.pcmSampleCount == consumedBytes / 2) { "native_audio_native_pcm_count" }
                        // Release the separate encoder before admitting LLM prefill.
                        val checkedCloseCalledAtNs = nowNs()
                        check(closeCurrent()) { "native_audio_sealed_owner_not_drained" }
                        val checkedCloseAtNs = nowNs()
                        val answer = synchronized(gate) {
                            check(phase == Phase.SEAL_QUEUED && candidate == event.candidate && isGenerationCurrent()) { "native_audio_cancelled_before_publication" }
                            phase = Phase.SEALED
                            Completed(event.candidate, result.pcmSampleCount, result.content,
                                result.timing?.takeIf { it.receipt.acceptedPcmSamples == result.pcmSampleCount }
                                    ?.copy(checkedCloseCalledAtNs = checkedCloseCalledAtNs, checkedCloseAtNs = checkedCloseAtNs))
                        }
                        completed.complete(answer)
                        queue.close()
                    }
                }
            }
        } catch (error: Throwable) {
            invalidate(error)
        } finally {
            // Drain transferred arrays even after a failed native operation.
            while (true) {
                val pending = queue.tryReceive().getOrNull() ?: break
                if (pending is Event.Pcm) { pending.bytes.fill(0); synchronized(gate) { queuedBytes -= pending.bytes.size } }
            }
            safeToRelease = closeCurrent()
            if (!safeToRelease) invalidate(IllegalStateException("native_audio_owner_quarantined"))
            drained.complete(safeToRelease)
            // An undrained current owner remains held in active, with its native
            // registry lease. The containing turn must quarantine this worker.
            workerJob.complete()
            // Success has released every thread-affine native resource. A failed
            // close deliberately retains its worker/registry for quarantine.
            if (safeToRelease) workerDispatcher.close()
        }
    }
}
