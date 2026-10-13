package com.battlesbudz.jarvis.v2.voice.smartturn

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Raw model evidence. Only the current capture adapter may grant endpoint authority. */
internal data class SmartTurnShadowResult(
    val generation: SmartTurnGeneration,
    val captureSampleBoundary: Long,
    val capturedAtNanos: Long,
    val probability: Float?,
    val unknown: SmartTurnUnknown?,
    val timing: SmartTurnTiming,
)
internal data class SmartTurnTiming(
    val snapshotAgeNanos: Long,
    val initializationNanos: Long?,
    val frontendNanos: Long?,
    val inferenceNanos: Long?,
    val totalNanos: Long,
)
/** Metadata emitted after native work actually returns, including revoked requests. */
internal data class SmartTurnWorkCompletion(
    val generation: SmartTurnGeneration,
    val captureSampleBoundary: Long,
    val startedAtNanos: Long,
    val finishedAtNanos: Long,
    val cancelled: Boolean,
    val timing: SmartTurnTiming,
    // Actual backend call edges, separate from request admission/worker queue.
    // Includes frontend and inference, not isolated ORT CPU time. Null if never called.
    val backendCalledAtNanos: Long? = null,
    val backendReturnedAtNanos: Long? = null,
)
internal enum class SmartTurnUnknown { DEADLINE, INITIALIZATION_FAILED, INFERENCE_FAILED, INVALID_RESULT }
internal enum class SmartTurnOffer { DISABLED, STALE, BUSY, CLOSED, ACCEPTED }
internal data class SmartTurnInference(val probability: Float, val frontendNanos: Long, val inferenceNanos: Long)

/** All methods except cancel execute on the one owning worker; cancel is thread-safe. */
internal interface SmartTurnBackend : AutoCloseable {
    fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference
    fun cancel(requestId: Long)
}

/**
 * One retained inference worker, zero queued requests,
 * one expiring result slot. Timeout revokes publication and requests cooperative native stop;
 * it never frees/replaces a busy native owner or promises hard real-time cancellation.
 */
internal class SmartTurnShadow(
    private val backendFactory: () -> SmartTurnBackend,
    private val enabled: Boolean = false,
    private val deadlineNanos: Long = TimeUnit.MILLISECONDS.toNanos(250),
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    init { require(deadlineNanos > 0 && deadlineNanos <= TimeUnit.SECONDS.toNanos(2)) }

    private val lock = Object()
    private var generation: SmartTurnGeneration? = null
    private var closed = false
    private var sequence = 0L
    private var active: Request? = null
    private var result: SmartTurnShadowResult? = null
    private var backend: SmartTurnBackend? = null
    private val timer = if (enabled) Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "jarvis-smart-turn-deadline").apply { isDaemon = true }
    } else null
    private val worker = if (enabled) Thread(::runWorker, "jarvis-smart-turn-shadow").apply {
        isDaemon = true
        start()
    } else null

    private class Request(val id: Long, val snapshot: SmartTurnAudioSnapshot, val started: Long,
                          val completed: (SmartTurnWorkCompletion) -> Unit) {
        val cancelled = AtomicBoolean(false)
        var consumed = false
        var deadline: ScheduledFuture<*>? = null
    }

    /** Capture owner must advance revision before resumed audio can invalidate the pause. */
    fun updateGeneration(current: SmartTurnGeneration) = synchronized(lock) {
        if (closed || generation == current) return@synchronized
        generation = current
        result = null
        active?.let(::cancelLocked)
    }

    fun offer(snapshot: SmartTurnAudioSnapshot, completed: (SmartTurnWorkCompletion) -> Unit = {}): SmartTurnOffer = synchronized(lock) {
        if (closed) return@synchronized SmartTurnOffer.CLOSED
        if (!enabled) return@synchronized SmartTurnOffer.DISABLED
        if (snapshot.generation != generation) return@synchronized SmartTurnOffer.STALE
        if (active != null) return@synchronized SmartTurnOffer.BUSY
        val now = clock()
        val age = now - snapshot.capturedAtNanos
        if (age < 0 || age >= deadlineNanos) return@synchronized SmartTurnOffer.STALE
        val request = Request(++sequence, snapshot, now, completed)
        active = request
        result = null
        request.deadline = timer!!.schedule({ expire(request) }, deadlineNanos - age, TimeUnit.NANOSECONDS)
        lock.notifyAll()
        SmartTurnOffer.ACCEPTED
    }

    fun isBusy(): Boolean = synchronized(lock) { active != null }

    /** Consume only on the capture owner's serialized lane, immediately before recording telemetry. */
    fun takeResult(current: SmartTurnGeneration): SmartTurnShadowResult? = synchronized(lock) {
        if (closed || current != generation) return@synchronized null
        val value = result
        result = null
        value?.takeIf {
            it.generation == current && (it.probability == null ||
                (clock() - it.capturedAtNanos) in 0 until deadlineNanos)
        }
    }

    private fun cancelLocked(request: Request) {
        request.cancelled.set(true)
        request.deadline?.cancel(false)
        backend?.cancel(request.id)
    }

    private fun expire(request: Request) = synchronized(lock) {
        if (active !== request || request.cancelled.get() || closed) return@synchronized
        request.cancelled.set(true)
        backend?.cancel(request.id)
        if (generation == request.snapshot.generation) {
            result = observation(request, null, SmartTurnUnknown.DEADLINE, null, null, null)
        }
    }

    private fun observation(request: Request, prediction: SmartTurnInference?, unknown: SmartTurnUnknown?,
                            initialization: Long?, frontend: Long?, inference: Long?) = SmartTurnShadowResult(
        request.snapshot.generation, request.snapshot.captureSampleBoundary, request.snapshot.capturedAtNanos,
        prediction?.probability, unknown,
        SmartTurnTiming((request.started - request.snapshot.capturedAtNanos).coerceAtLeast(0), initialization,
            frontend, inference, (clock() - request.started).coerceAtLeast(0)),
    )

    private fun runWorker() {
        try {
            while (true) {
                val request = synchronized(lock) {
                    while (!closed && (active == null || active!!.consumed)) lock.wait()
                    if (closed) return
                    active!!.also { it.consumed = true }
                }
                var initNanos: Long? = null
                var prediction: SmartTurnInference? = null
                var backendCalledAt: Long? = null
                var backendReturnedAt: Long? = null
                var unknown: SmartTurnUnknown? = null
                try {
                    if (!request.cancelled.get() && clock() - request.snapshot.capturedAtNanos < deadlineNanos) {
                        if (backend == null) {
                            val start = clock()
                            try {
                                val created = backendFactory()
                                synchronized(lock) { backend = created }
                            } finally {
                                initNanos = (clock() - start).coerceAtLeast(0)
                            }
                        } else {
                            initNanos = 0L // Existing worker-owned session reused; no setup performed.
                        }
                        if (!request.cancelled.get() && clock() - request.snapshot.capturedAtNanos < deadlineNanos) {
                            val samples = request.snapshot.copySamples()
                            backendCalledAt = clock()
                            try { prediction = backend!!.infer(samples, request.id, request.cancelled) }
                            finally { backendReturnedAt = clock() }
                        }
                        prediction?.let {
                            if (!it.probability.isFinite() || it.probability !in 0f..1f ||
                                it.frontendNanos < 0 || it.inferenceNanos < 0) {
                                prediction = null
                                unknown = SmartTurnUnknown.INVALID_RESULT
                            }
                        }
                    }
                } catch (_: Exception) {
                    unknown = if (backend == null) SmartTurnUnknown.INITIALIZATION_FAILED else SmartTurnUnknown.INFERENCE_FAILED
                } catch (_: LinkageError) {
                    unknown = SmartTurnUnknown.INITIALIZATION_FAILED
                } finally {
                    val finishedAt = clock()
                    synchronized(lock) {
                        request.deadline?.cancel(false)
                        if (!closed && !request.cancelled.get() && generation == request.snapshot.generation) {
                            val overdue = clock() - request.snapshot.capturedAtNanos >= deadlineNanos
                            result = observation(request, if (overdue) null else prediction,
                                if (overdue) SmartTurnUnknown.DEADLINE else unknown, initNanos,
                                prediction?.frontendNanos, prediction?.inferenceNanos)
                        }
                        active = null
                        lock.notifyAll()
                    }
                    // No audio or probability publication here. This is the exact completed
                    // borrow's metadata, even if its generation was revoked in the meantime.
                    runCatching { request.completed(SmartTurnWorkCompletion(request.snapshot.generation,
                        request.snapshot.captureSampleBoundary, request.started, finishedAt, request.cancelled.get(),
                        SmartTurnTiming((request.started - request.snapshot.capturedAtNanos).coerceAtLeast(0),
                            initNanos, prediction?.frontendNanos, prediction?.inferenceNanos,
                            (finishedAt - request.started).coerceAtLeast(0)),
                        backendCalledAt, backendReturnedAt)) }
                }
            }
        } finally {
            // Never close a native session from the caller/timer or while Run is borrowing it.
            try { backend?.close() } finally { synchronized(lock) { backend = null; lock.notifyAll() } }
        }
    }

    /** Nonblocking revoke. Owner may use awaitClosed off capture/UI before releasing budgets. */
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        result = null
        active?.let(::cancelLocked)
        timer?.shutdownNow()
        lock.notifyAll()
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val target = worker ?: return true
        if (Thread.currentThread() === target) return false
        if (timeoutMillis > 0) target.join(timeoutMillis)
        return !target.isAlive
    }
}
