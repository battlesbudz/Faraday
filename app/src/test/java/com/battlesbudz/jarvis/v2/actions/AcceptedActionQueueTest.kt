package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.voice.VoiceActionControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedActionQueueTest {
    @Test fun admissionIsFifoBoundedAndOnlySameUtteranceDeduplicates() = runBlocking {
        val queue = AcceptedActionQueue<Int>(capacity = 3)
        val first = requireNotNull(queue.admit("first", "utterance-1", 1))
        assertEquals(first, queue.admit("replayed-id", "utterance-1", 99))
        requireNotNull(queue.admit("second", "utterance-2", 1)) // Same words with a new utterance are deliberate.
        requireNotNull(queue.admit("third", "utterance-3", 3))
        assertNull(queue.admit("overflow", "utterance-4", 4))

        val ran = mutableListOf<Int>()
        queue.drain { task -> ran += task.value; true }
        assertEquals(listOf(1, 1, 3), ran)
        assertEquals(listOf(AcceptedActionState.COMPLETED, AcceptedActionState.COMPLETED, AcceptedActionState.COMPLETED),
            queue.tasks.value.map { it.state })
        queue.close()
    }

    @Test fun admissionAndCancelRemainResponsiveWhileNativeWorkIsBlocked() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        queue.admit("a", "utterance-a", "A")
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val worker = queue.start { task ->
            ran += task.value
            if (task.value == "A") {
                enteredA.complete(Unit)
                releaseA.await()
            }
            true
        }
        enteredA.await()
        assertNotNull(queue.admit("b", "utterance-b", "B"))
        assertEquals(1, queue.pendingCount())
        assertEquals("a", queue.activeTask()?.id)
        releaseA.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertEquals(listOf("A", "B"), ran)
        queue.close()
    }

    @Test fun explicitCancellationCancelsNativeJobAndNeverStartsQueuedWork() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        queue.admit("a", "utterance-a", "A")
        queue.admit("b", "utterance-b", "B")
        val started = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupReleased = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val worker = queue.start { task ->
            ran += task.value
            started.complete(Unit)
            try {
                neverReleased.await()
                true
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanupReleased.await()
                }
            }
        }
        started.await()
        queue.cancel(VoiceActionControl.CancelAll)
        // Cancellation publishes its requested terminal state first, but native cleanup still
        // owns the queue/model lease until the child job joins.
        cleanupStarted.await()
        assertFalse(queue.idle.value)
        cleanupReleased.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertTrue(queue.idle.value)
        assertEquals(listOf("A"), ran)
        assertEquals(listOf(AcceptedActionState.CANCELLED, AcceptedActionState.CANCELLED), queue.tasks.value.map { it.state })
        assertFalse(queue.hasUnfinished())
        queue.close()
    }

    @Test fun concurrentStartCallsShareOneWorker() = runBlocking {
        val queue = AcceptedActionQueue<Int>()
        queue.admit("one", "u1", 1)
        val gate = CompletableDeferred<Unit>()
        var concurrent = 0
        var maximum = 0
        val first = async { queue.start { concurrent++; maximum = maxOf(maximum, concurrent); gate.await(); concurrent--; true }.join() }
        while (queue.activeTask() == null) kotlinx.coroutines.yield()
        val second = async { queue.start { error("second worker must not run") }.join() }
        gate.complete(Unit)
        first.await(); second.await()
        assertEquals(1, maximum)
        queue.close()
    }
    @Test fun staleIdleLeaseReleaseCannotClearLaterAcceptedBatch() {
        val lease = AcceptedActionLease()
        var acquisitions = 0
        val first = requireNotNull(lease.retain { acquisitions++; true })
        val second = requireNotNull(lease.retain { acquisitions++; true })
        assertTrue(lease.active())
        assertFalse(lease.releaseIfCurrent(first, idle = true))
        assertTrue(lease.active())
        assertTrue(lease.releaseIfCurrent(second, idle = true))
        assertFalse(lease.active())
        assertEquals(1, acquisitions)
    }

}
