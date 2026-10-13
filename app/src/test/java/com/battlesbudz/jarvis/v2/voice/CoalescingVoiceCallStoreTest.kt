package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CoalescingVoiceCallStoreTest {
    private class Scheduler {
        data class Task(val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): AutoCloseable {
            assertEquals(500L, delay)
            val task = Task(action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }
        fun runAllIncludingCancelled() { tasks.toList().also { tasks.clear() }.forEach { it.action() } }
    }
    private open class MemoryStore : VoiceCallStore {
        val calls = linkedMapOf<String, VoiceCallRecord>()
        var saves = 0
        override fun list() = calls.values.toList()
        override fun save(call: VoiceCallRecord) { saves++; calls[call.id] = call }
        override fun delete(callId: String) { calls.remove(callId) }
    }
    private fun call(text: String, complete: Boolean = false) = VoiceCallRecord("call", 1,
        transcript = listOf(TranscriptEntry("Jarvis", text, 1, complete)))

    @Test fun tokenBurstIsVisibleInMemoryButWritesOnlyLatestPartialOnTimer() {
        val disk = MemoryStore(); val scheduler = Scheduler()
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        val session = VoiceSessionController(store)
        session.beginCall()
        session.setState(VoiceSessionState.SPEAKING)
        val before = disk.saves
        repeat(100) {
            session.setState(VoiceSessionState.SPEAKING)
            session.appendTranscript("Jarvis", "token $it", complete = false)
        }
        assertEquals("token 99", session.currentTranscript().last().text)
        assertEquals(before, disk.saves)
        assertEquals(1, scheduler.tasks.size)
        scheduler.runAllIncludingCancelled()
        assertEquals(before + 1, disk.saves)
        assertEquals("token 99", disk.list().single().transcript.last().text)
        assertEquals(99L, store.metrics().coalescedUpdates)
    }

    @Test fun finalSnapshotCannotBeOverwrittenByCancelledTimer() {
        val disk = MemoryStore(); val scheduler = Scheduler()
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        store.saveProgress(call("partial"))
        val final = call("complete", true).copy(endedAtMs = 10)
        store.save(final)
        scheduler.runAllIncludingCancelled()
        assertEquals(listOf(final), store.list())
        assertEquals(1, disk.saves)
    }

    @Test fun oldTimerCannotDrainNewCallProgressEarly() {
        val disk = MemoryStore(); val scheduler = Scheduler()
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        store.saveProgress(call("old"))
        val old = scheduler.tasks.single()
        store.save(call("finished", true))
        store.saveProgress(call("new").copy(id = "second"))
        old.action()
        assertEquals(1, disk.saves)
        scheduler.runAllIncludingCancelled()
        assertEquals(2, disk.saves)
        assertEquals("new", store.list().last().transcript.last().text)
    }

    @Test fun deletingCallCancelsPendingSaveWithoutResurrection() {
        val disk = MemoryStore(); val scheduler = Scheduler()
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        store.save(call("saved", true))
        store.saveProgress(call("later"))
        store.delete("call")
        scheduler.runAllIncludingCancelled()
        assertTrue(store.list().isEmpty())
    }

    @Test fun boundaryWaitsForInFlightWriterAndWins() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scheduler = Scheduler()
        val disk = object : MemoryStore() {
            override fun save(call: VoiceCallRecord) {
                if (!call.transcript.last().complete) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                super.save(call)
            }
        }
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        store.saveProgress(call("in flight"))
        val writer = thread { scheduler.tasks.single().action() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val final = call("final", true)
        val boundary = thread { store.save(final); done.countDown() }
        release.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        writer.join(2000); boundary.join(2000)
        assertEquals(listOf(final), store.list())
    }

    @Test fun failedBackgroundSaveIsObservableAndNextBoundaryRetriesNewestSnapshot() {
        val scheduler = Scheduler(); val failures = mutableListOf<Exception>()
        val disk = object : MemoryStore() {
            var fail = true
            override fun save(call: VoiceCallRecord) {
                if (fail) { fail = false; error("disk unavailable") }
                super.save(call)
            }
        }
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule, onFailure = failures::add)
        store.saveProgress(call("partial"))
        scheduler.runAllIncludingCancelled()
        assertEquals(1, failures.size)
        assertEquals(1L, store.metrics().failures)
        store.save(call("final", true))
        assertEquals("final", store.list().single().transcript.last().text)
    }

    @Test fun flushAndEndSaveLatestPartialWithoutWaitingForTimer() {
        val disk = MemoryStore(); val scheduler = Scheduler()
        val store = CoalescingVoiceCallStore(disk, schedule = scheduler::schedule)
        val session = VoiceSessionController(store)
        session.beginCall()
        session.appendTranscript("Jarvis", "before pause", complete = false)
        session.flushCheckpoint()
        assertEquals("before pause", store.list().single().transcript.last().text)
        session.appendTranscript("Jarvis", "before stop", complete = false)
        session.end()
        scheduler.runAllIncludingCancelled()
        assertEquals("before stop", store.list().single().transcript.last().text)
        assertNotNull(store.list().single().endedAtMs)
    }

    @Test fun persistenceWorkIsMeasuredWithMonotonicClock() {
        var now = 0L
        val disk = object : MemoryStore() {
            override fun save(call: VoiceCallRecord) { now += 7; super.save(call) }
        }
        val store = CoalescingVoiceCallStore(disk, nowMs = { now })
        store.save(call("final", true))
        assertEquals(7L, store.metrics().writeMs)
        assertEquals(1L, store.metrics().writes)
    }
}
