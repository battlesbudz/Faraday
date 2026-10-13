package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.voice.VoiceActionControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** States are terminal once published; an accepted side effect is never replayed. */
enum class AcceptedActionState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

data class AcceptedActionTask<T>(
    val id: String,
    val utteranceId: String,
    val value: T,
    val state: AcceptedActionState = AcceptedActionState.QUEUED
)

data class AcceptedActionEvent<T>(val task: AcceptedActionTask<T>)

/**
 * Runtime-owned FIFO for already-authorized action turns.  It schedules work only; native
 * decoding and Android execution stay in ConversationRuntime/ActionTurnRunner.
 *
 * Calls which admit or control work take a short monitor section.  The single worker runs the
 * supplied native invocation outside that monitor so capture may enqueue a follow-up while an
 * earlier model call is suspended.
 */
class AcceptedActionQueue<T>(
    private val capacity: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AutoCloseable {
    private data class Record<T>(
        val id: String,
        val utteranceId: String,
        val value: T,
        var state: AcceptedActionState = AcceptedActionState.QUEUED
    ) {
        fun snapshot() = AcceptedActionTask(id, utteranceId, value, state)
    }

    private val lock = Any()
    private val pending = ArrayDeque<Record<T>>()
    private val byUtterance = linkedMapOf<String, Record<T>>()
    private var active: Record<T>? = null
    private var workerJob: Job? = null
    private var executionJob: Job? = null
    private var closed = false

    private val _tasks = MutableStateFlow<List<AcceptedActionTask<T>>>(emptyList())
    val tasks: StateFlow<List<AcceptedActionTask<T>>> = _tasks.asStateFlow()
    private val _idle = MutableStateFlow(true)
    /** False until a cancelled native child has actually joined and cleanup is complete. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()
    private val _events = MutableSharedFlow<AcceptedActionEvent<T>>(extraBufferCapacity = 32)
    val events: SharedFlow<AcceptedActionEvent<T>> = _events.asSharedFlow()

    /** A repeated ASR callback returns the original task; equal spoken text with a new ID is new. */
    fun admit(id: String, utteranceId: String, value: T): AcceptedActionTask<T>? = synchronized(lock) {
        if (closed) return null
        byUtterance[utteranceId]?.let { return it.snapshot() }
        if (unfinishedLocked() >= capacity) return null
        val record = Record(id, utteranceId, value)
        pending += record
        byUtterance[utteranceId] = record
        _idle.value = false
        publishLocked(record)
        record.snapshot()
    }

    /** Starts at most one serial worker. It is safe to call again after a previous drain completed. */
    fun start(execute: suspend (AcceptedActionTask<T>) -> Boolean): Job = synchronized(lock) {
        workerJob?.takeUnless { it.isCompleted } ?: scope.launchWorker(execute).also { workerJob = it }
    }

    /** Compatibility bridge for callers that need to await the currently admitted batch. */
    suspend fun drain(execute: suspend (AcceptedActionTask<T>) -> Boolean) {
        val job = start(execute)
        job.join()
    }

    /**
     * Cancels only explicit requested scope.  Active native work receives a Job cancellation;
     * queued work is never started.  An operation that already crossed Android's synchronous
     * executor boundary may still publish its receipt before cancellation reaches this worker.
     */
    fun cancel(
        control: VoiceActionControl,
        matches: (AcceptedActionTask<T>) -> Boolean = { true }
    ) {
        val activeToCancel: Job?
        synchronized(lock) {
            val activeMatches = active?.let { matches(it.snapshot()) } == true
            when (control) {
                VoiceActionControl.CancelAll -> {
                    if (activeMatches) active?.let { transitionLocked(it, AcceptedActionState.CANCELLED) }
                    cancelQueuedLocked(matches)
                }
                VoiceActionControl.CancelQueued -> cancelQueuedLocked(matches)
                VoiceActionControl.CancelCurrent -> if (activeMatches) active?.let { transitionLocked(it, AcceptedActionState.CANCELLED) }
                VoiceActionControl.CancelNewest -> {
                    val newest = pending.lastOrNull { matches(it.snapshot()) } ?: active?.takeIf { activeMatches }
                    newest?.let { record ->
                        transitionLocked(record, AcceptedActionState.CANCELLED)
                        if (record !== active) pending.remove(record)
                    }
                }
                VoiceActionControl.SpeechOnly, VoiceActionControl.None -> Unit
            }
            activeToCancel = if (active?.state == AcceptedActionState.CANCELLED && activeMatches) executionJob else null
        }
        activeToCancel?.cancel(ActionTaskCancellation())
    }

    fun pendingCount(): Int = synchronized(lock) { pending.count { it.state == AcceptedActionState.QUEUED } }
    fun hasUnfinished(): Boolean = synchronized(lock) { unfinishedLocked() > 0 }
    fun activeTask(): AcceptedActionTask<T>? = synchronized(lock) { active?.snapshot() }
    suspend fun awaitIdle() { idle.first { it } }

    /** Stops future work on process/service shutdown and marks any unfinished evidence interrupted. */
    override fun close() {
        val worker: Job?
        val running: Job?
        synchronized(lock) {
            active?.takeIf { it.state == AcceptedActionState.RUNNING }?.let { transitionLocked(it, AcceptedActionState.INTERRUPTED) }
            pending.toList().forEach { transitionLocked(it, AcceptedActionState.INTERRUPTED) }
            pending.clear()
            closed = true
            worker = workerJob
            running = executionJob
        }
        running?.cancel(CancellationException("Accepted action runtime closed"))
        worker?.cancel(CancellationException("Accepted action runtime closed"))
    }

    private fun CoroutineScope.launchWorker(execute: suspend (AcceptedActionTask<T>) -> Boolean): Job =
        launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                val record = synchronized(lock) { nextLocked() }
                if (record == null) {
                    // Clear the worker identity under the same lock used by admission. If a task was
                    // admitted in the tiny gap, keep draining; otherwise the next admission starts a
                    // new worker. This prevents a stranded task at an empty-queue transition.
                    val done = synchronized(lock) {
                        if (pending.isEmpty()) {
                            workerJob = null
                            _idle.value = active == null
                            true
                        } else false
                    }
                    if (done) return@launch else continue
                }
                val runnable = synchronized(lock) {
                    if (record.state == AcceptedActionState.QUEUED) {
                        transitionLocked(record, AcceptedActionState.RUNNING)
                        true
                    } else false
                }
                if (!runnable) {
                    synchronized(lock) { finishLocked(record, record.state) }
                    continue
                }
                val result = supervisorScope {
                    // LAZY closes the gap between assigning the cancellable native Job and starting
                    // it. A CancelCurrent issued there observes and cancels this exact invocation.
                    val native = async(start = CoroutineStart.LAZY) { execute(record.snapshot()) }
                    val startNative = synchronized(lock) {
                        executionJob = native
                        record.state == AcceptedActionState.RUNNING
                    }
                    if (!startNative) native.cancel(ActionTaskCancellation()) else native.start()
                    try {
                        if (native.await()) AcceptedActionState.COMPLETED else AcceptedActionState.FAILED
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) {
                            if (record.state == AcceptedActionState.CANCELLED) AcceptedActionState.CANCELLED
                            else AcceptedActionState.INTERRUPTED
                        }
                    } catch (_: Throwable) {
                        AcceptedActionState.FAILED
                    }
                }
                synchronized(lock) { finishLocked(record, result) }
            }
        }

    private fun nextLocked(): Record<T>? {
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            active = next
            return next
        }
        return null
    }

    private fun finishLocked(record: Record<T>, final: AcceptedActionState) {
        if (record.state == AcceptedActionState.CANCELLED) {
            transitionLocked(record, AcceptedActionState.CANCELLED)
        } else if (record.state == AcceptedActionState.INTERRUPTED) {
            transitionLocked(record, AcceptedActionState.INTERRUPTED)
        } else {
            transitionLocked(record, final)
        }
        if (active === record) active = null
        executionJob = null
        if (pending.isEmpty()) _idle.value = true
    }

    private fun cancelQueuedLocked(matches: (AcceptedActionTask<T>) -> Boolean) {
        pending.toList().filter { matches(it.snapshot()) }.forEach {
            transitionLocked(it, AcceptedActionState.CANCELLED)
            pending.remove(it)
        }
    }

    private fun unfinishedLocked(): Int = pending.count { it.state == AcceptedActionState.QUEUED } +
        // A cancellation is terminal evidence, but its native child still owns the engine until
        // finishLocked joins it. Treat it as unfinished for handoff and model-lease guards.
        if (active != null) 1 else 0

    private fun transitionLocked(record: Record<T>, state: AcceptedActionState) {
        if (record.state == state) return
        record.state = state
        publishLocked(record)
    }

    private fun publishLocked(changed: Record<T>) {
        _tasks.value = byUtterance.values.map { it.snapshot() }
        _events.tryEmit(AcceptedActionEvent(changed.snapshot()))
    }
}

private class ActionTaskCancellation : CancellationException("Accepted action explicitly cancelled")

/**
 * Generation-fenced ownership for a model lease held across one accepted FIFO batch.  A stale
 * queue-idle observer cannot release a lease after a later admission has advanced [generation].
 */
internal class AcceptedActionLease {
    private val lock = Any()
    private var owned = false
    private var generation = 0L

    fun retain(acquire: () -> Boolean): Long? = synchronized(lock) {
        if (!owned) {
            if (!acquire()) return@synchronized null
            owned = true
        }
        ++generation
    }

    fun transfer(): Long = synchronized(lock) {
        check(!owned) { "accepted_action_lease_already_owned" }
        owned = true
        ++generation
    }

    fun active(): Boolean = synchronized(lock) { owned }

    /** True exactly once when this generation is still current and the FIFO is actually idle. */
    fun releaseIfCurrent(expectedGeneration: Long, idle: Boolean): Boolean = synchronized(lock) {
        if (owned && generation == expectedGeneration && idle) {
            owned = false
            true
        } else false
    }

    fun generation(): Long = synchronized(lock) { generation }
}
