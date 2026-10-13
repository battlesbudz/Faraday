package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Schedules only transient text. Explicit saves and deletes remain ordered boundaries. */
class CoalescingVoiceCallStore(
    private val delegate: VoiceCallStore,
    private val delayMs: Long = 500,
    private val schedule: (Long, () -> Unit) -> AutoCloseable = CheckpointScheduler::schedule,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (Exception) -> Unit = {}
) : VoiceCallStore {
    data class Metrics(val progressUpdates: Long, val coalescedUpdates: Long,
                       val writes: Long, val writeMs: Long, val failures: Long)
    private data class Pending(val revision: Long, val call: VoiceCallRecord)
    private val stateLock = Any()
    private val writeLock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val revisions = mutableMapOf<String, Long>()
    private var nextRevision = 0L
    private var timerRevision = 0L
    private var timer: AutoCloseable? = null
    private var progressUpdates = 0L
    private var coalescedUpdates = 0L
    private var writes = 0L
    private var writeMs = 0L
    private var failures = 0L

    init { require(delayMs > 0) }

    override fun list(): List<VoiceCallRecord> = synchronized(writeLock) { delegate.list() }

    override fun saveProgress(call: VoiceCallRecord) {
        synchronized(stateLock) {
            progressUpdates++
            if (pending.containsKey(call.id)) coalescedUpdates++
            pending[call.id] = revision(call)
            ensureTimer()
        }
    }

    override fun save(call: VoiceCallRecord) {
        val entry = synchronized(stateLock) {
            pending.remove(call.id)
            cancelEmptyTimer()
            revision(call)
        }
        write(entry) // The final snapshot supersedes even an already dequeued partial.
    }

    override fun delete(callId: String) {
        synchronized(writeLock) {
            synchronized(stateLock) {
                revisions[callId] = ++nextRevision
                pending.remove(callId)
                cancelEmptyTimer()
            }
            delegate.delete(callId)
        }
    }

    fun metrics(): Metrics = synchronized(stateLock) {
        Metrics(progressUpdates, coalescedUpdates, writes, writeMs, failures)
    }

    // Called only with stateLock held. Timer cancellation does not interrupt a writer.
    private fun revision(call: VoiceCallRecord): Pending {
        val revision = ++nextRevision
        revisions[call.id] = revision
        return Pending(revision, call)
    }

    private fun ensureTimer() {
        if (timer != null) return
        val expected = ++timerRevision
        timer = schedule(delayMs) { drain(expected) }
    }

    private fun cancelEmptyTimer() {
        if (pending.isEmpty()) {
            timer?.close()
            timer = null
            timerRevision++
        }
    }

    private fun drain(expected: Long) {
        val entries = synchronized(stateLock) {
            if (expected != timerRevision) return
            timer = null
            pending.values.toList().also { pending.clear() }
        }
        entries.forEach { entry ->
            try { write(entry) }
            catch (error: Exception) {
                // A transient persistence failure must not kill the scheduling worker.
                // Retain the newest snapshot for the next progress or explicit boundary save.
                synchronized(stateLock) {
                    if (revisions[entry.call.id] == entry.revision) pending[entry.call.id] = entry
                }
                runCatching { onFailure(error) }
            }
        }
    }

    private fun write(entry: Pending) = synchronized(writeLock) {
        if (synchronized(stateLock) { revisions[entry.call.id] != entry.revision }) return@synchronized
        val started = nowMs()
        try {
            delegate.save(entry.call)
            synchronized(stateLock) { writes++ }
        } catch (error: Exception) {
            synchronized(stateLock) { failures++ }
            throw error
        } finally {
            synchronized(stateLock) { writeMs += (nowMs() - started).coerceAtLeast(0) }
        }
    }
}

/** One lazy daemon for the process; no thread or timer is created per token or per turn. */
private object CheckpointScheduler {
    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "jarvis-checkpoint").apply { isDaemon = true }
        }
    }
    fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
        val future = executor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
