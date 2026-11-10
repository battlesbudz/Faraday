package com.battlesbudz.jarvis.v2.memory

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingSyncSchedulerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @After fun tearDown() {
        dispatcher.close()
    }

    /**
     * The scheduler's happy path never suspends on the single executor thread,
     * so a probe task queued behind it runs only after the scheduler coroutine
     * has fully drained. Bounded so a runaway loop fails instead of hanging.
     */
    private fun awaitQuiescence() {
        val probe = CountDownLatch(1)
        executor.execute { probe.countDown() }
        assertTrue("scheduler did not quiesce", probe.await(5, TimeUnit.SECONDS))
    }

    @Test fun requestRunsOnePassWhenIdle() {
        val runs = AtomicInteger(0)
        val ran = CountDownLatch(1)
        val scheduler = MemoryEmbeddingSyncScheduler(scope = scope) {
            runs.incrementAndGet()
            ran.countDown()
        }
        scheduler.request()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        awaitQuiescence()
        assertEquals(1, runs.get())
    }

    @Test fun requestDuringAPassSchedulesOneFollowUpPass() {
        val runs = AtomicInteger(0)
        val firstPassEntered = CountDownLatch(1)
        val releaseFirstPass = CountDownLatch(1)
        val followUpRan = CountDownLatch(1)
        val scheduler = MemoryEmbeddingSyncScheduler(scope = scope) {
            when (runs.incrementAndGet()) {
                1 -> {
                    firstPassEntered.countDown()
                    // Hold the first pass open, as a long ledger sync would.
                    releaseFirstPass.await(5, TimeUnit.SECONDS)
                }
                2 -> followUpRan.countDown()
            }
        }
        scheduler.request()
        assertTrue(firstPassEntered.await(5, TimeUnit.SECONDS))
        // This request lands while the first pass is still running. Before the
        // rerun flag it was silently discarded, so a change the first pass had
        // already read past was only picked up by an unrelated later trigger.
        scheduler.request()
        releaseFirstPass.countDown()
        assertTrue("mid-pass request never got its follow-up pass", followUpRan.await(5, TimeUnit.SECONDS))
        awaitQuiescence()
        assertEquals(2, runs.get())
    }

    @Test fun requestNeverForcesASyncWhileHostBusy() {
        val idle = AtomicBoolean(false)
        val runs = AtomicInteger(0)
        val ran = CountDownLatch(1)
        val scheduler = MemoryEmbeddingSyncScheduler(
            scope = scope,
            idleProbe = { idle.get() },
            pollIntervalMs = 10,
            maxPolls = 3,
        ) {
            runs.incrementAndGet()
            ran.countDown()
        }
        scheduler.request()
        // Far beyond the 3 x 10 ms polling window: a dropped pass must not sync.
        Thread.sleep(500)
        awaitQuiescence()
        assertEquals(0, runs.get())
        // The documented catch-up still holds: once the host is idle, the next
        // trigger runs the pass.
        idle.set(true)
        scheduler.request()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        awaitQuiescence()
        assertEquals(1, runs.get())
    }

    @Test fun syncNowRunsImmediately() {
        val runs = AtomicInteger(0)
        val scheduler = MemoryEmbeddingSyncScheduler(scope = scope) {
            runs.incrementAndGet()
        }
        runBlocking { scheduler.syncNow() }
        assertEquals(1, runs.get())
    }
}
