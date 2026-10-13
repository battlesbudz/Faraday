package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** No real weights: exercises the actual worker, admission lock and exclusive call slot. */
class IdleWhisperCaptionTest {
    @Test fun idleRetirementDoesNotDecodeTailOrInventFinalText() {
        val decodes = AtomicInteger()
        val releases = AtomicInteger()
        val session = AsyncWhisperSession({ decodes.incrementAndGet(); "Stop listening" }, { releases.incrementAndGet() })
        session.observeSpeech(true)
        session.accept(ByteArray(16_000), false)
        assertTrue(session.retireIdleCaption())
        assertFalse(session.retireIdleCaption())
        assertThrows(IllegalStateException::class.java) { session.accept(ByteArray(3200)) }
        assertThrows(IllegalStateException::class.java) { session.finish() }
        assertThrows(IllegalStateException::class.java) { session.recover(ByteArray(3200)) }
        session.close(); session.close()
        assertEquals(0, decodes.get())
        assertEquals(1, releases.get())
        assertEquals(0, session.recognitionWorkMetrics.invocations)
    }

    @Test fun invalidatedIdleEndpointResumesTheSameCompletePcmWithoutAFakeSegment() {
        val clips = mutableListOf<ByteArray>()
        val session = AsyncWhisperSession({ clips += it; "Whole continued request" }, {})
        try {
            val original = ByteArray(9600) { 1 }; val continued = ByteArray(6400) { 2 }
            session.observeSpeech(true); session.accept(original, false)
            assertTrue(session.retireIdleCaption())
            assertTrue(session.resumeRetiredCaption())
            assertFalse(session.resumeRetiredCaption())
            session.accept(continued, false)
            assertEquals("Whole continued request", session.finish())
            assertArrayEquals(original + continued, clips.single())
            assertFalse(session.resumeRetiredCaption()) // A real final decode cannot be undone.
        } finally { session.close() }
        assertFalse(session.resumeRetiredCaption())
    }

    @Test fun closedIdleCaptionCannotResumeForANewCall() {
        val session = AsyncWhisperSession({ error("No decode permitted") }, {})
        assertTrue(session.retireIdleCaption()); session.close()
        assertFalse(session.resumeRetiredCaption())
        assertThrows(IllegalStateException::class.java) { session.accept(ByteArray(3200)) }
    }

    @Test fun completedPartialCanRetireWithoutDecodingUnseenTail() {
        val completed = CountDownLatch(1)
        val decodes = AtomicInteger()
        val session = AsyncWhisperSession({ decodes.incrementAndGet(); "Live preview" }, {}, log = {
            if (it.startsWith("whisper_partial")) completed.countDown()
        })
        try {
            session.observeSpeech(true); session.accept(ByteArray(48_000))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            session.accept(ByteArray(3200), false)
            awaitIdleRetirement(session)
            assertEquals(1, decodes.get())
            assertEquals(0L, session.recognitionWorkMetrics.finalDecodeWorkNs)
        } finally { session.close() }
    }

    @Test fun busyPartialRefusesImmediatelyAndKeepsLegacyFinalDecodeAndLeaseBarrier() {
        val began = CountDownLatch(1); val unblock = CountDownLatch(1)
        val releases = AtomicInteger(); val decodes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val slot = CallModelSlot<Any>({ releases.incrementAndGet() })
        val lease = slot.acquire("whisper") { Any() }
        val session = AsyncWhisperSession({
            if (decodes.incrementAndGet() == 1) { began.countDown(); check(unblock.await(2, TimeUnit.SECONDS)) }
            "Verified final"
        }, { lease.finish() })
        try {
            session.observeSpeech(true); session.accept(ByteArray(48_000))
            assertTrue(began.await(2, TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { session.retireIdleCaption() }.get(1, TimeUnit.SECONDS))
            session.accept(ByteArray(3200), false) // Refusal must leave ordinary admission legal.
            val final = executor.submit<String> { session.finish() }
            assertFalse(final.isDone)
            assertFalse(slot.canReuse("whisper", Int.MAX_VALUE))
            unblock.countDown()
            assertEquals("Verified final", final.get(2, TimeUnit.SECONDS))
            session.close()
            assertEquals(2, decodes.get())
            val probe = slot.acquireWarm("whisper")
            assertSame(lease.value, probe.value)
            probe.finish()
        } finally { unblock.countDown(); session.close(); slot.close(); executor.shutdownNow() }
        assertEquals(1, releases.get())
    }

    @Test fun idleClaimDoesNotWaitBehindAnAlreadyStartedFinalDecode() {
        val began = CountDownLatch(1); val unblock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val session = AsyncWhisperSession({ began.countDown(); check(unblock.await(2, TimeUnit.SECONDS)); "final" }, {})
        try {
            session.observeSpeech(true); session.accept(ByteArray(3200), false)
            val final = executor.submit<String> { session.finish() }
            assertTrue(began.await(2, TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { session.retireIdleCaption() }.get(1, TimeUnit.SECONDS))
            unblock.countDown(); assertEquals("final", final.get(2, TimeUnit.SECONDS))
        } finally { unblock.countDown(); session.close(); executor.shutdownNow() }
    }

    @Test fun failedWorkerCannotBeReclassifiedAsSuccessfulIdleCaption() {
        val began = CountDownLatch(1)
        val session = AsyncWhisperSession({ began.countDown(); error("decode failed") }, {})
        try {
            session.observeSpeech(true); session.accept(ByteArray(48_000))
            assertTrue(began.await(2, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.ExecutionException::class.java) { session.finish() }
            assertFalse(session.retireIdleCaption())
        } finally { session.close() }
    }

    @Test fun closeDuringBusyDecodeRetainsOwnerUntilJniReturns() {
        val began = CountDownLatch(1); val unblock = CountDownLatch(1)
        val inDecode = AtomicInteger(); val releases = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val slot = CallModelSlot<Any>({ assertEquals(0, inDecode.get()); releases.incrementAndGet() })
        val lease = slot.acquire("whisper") { Any() }
        val session = AsyncWhisperSession({
            inDecode.incrementAndGet(); began.countDown()
            try { check(unblock.await(2, TimeUnit.SECONDS)); "late caption" }
            finally { inDecode.decrementAndGet() }
        }, { lease.finish() })
        try {
            session.observeSpeech(true); session.accept(ByteArray(48_000))
            assertTrue(began.await(2, TimeUnit.SECONDS))
            slot.close() // Call replacement/end cannot free the borrowed native handle.
            val closing = executor.submit { session.close() }
            assertFalse(session.retireIdleCaption())
            assertEquals(0, releases.get())
            unblock.countDown(); closing.get(2, TimeUnit.SECONDS)
            assertEquals(1, releases.get())
            assertFalse(session.retireIdleCaption())
        } finally { unblock.countDown(); session.close(); executor.shutdownNow() }
    }

    @Test fun completedIdleLeaseIsWarmBeforeFollowingProbeAndFollowingTurn() {
        val releases = AtomicInteger(); val slot = CallModelSlot<Any>({ releases.incrementAndGet() })
        val first = slot.acquire("whisper") { Any() }
        val caption = AsyncWhisperSession({ error("No final decode permitted") }, { first.finish() })
        caption.observeSpeech(true); caption.accept(ByteArray(9600), false)
        assertTrue(caption.retireIdleCaption())
        assertFalse(slot.canReuse("whisper", Int.MAX_VALUE))
        caption.close()
        slot.acquireWarm("whisper").also { assertSame(first.value, it.value); it.finish() }
        val following = slot.acquire("whisper") { error("Must reuse the one model") }
        assertSame(first.value, following.value)
        following.finish(); slot.close()
        assertEquals(1, releases.get())
    }

    @Test fun retirementRacingAcceptNeverSchedulesAfterSuccessfulClaim() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(60) {
                val start = CountDownLatch(1); val decodes = AtomicInteger(); val releases = AtomicInteger()
                val session = AsyncWhisperSession({ decodes.incrementAndGet(); "preview" }, { releases.incrementAndGet() })
                session.observeSpeech(true)
                val accepting = executor.submit {
                    start.await()
                    try { session.accept(ByteArray(48_000)) } catch (_: IllegalStateException) { }
                }
                val retiring = executor.submit<Boolean> { start.await(); session.retireIdleCaption() }
                start.countDown(); accepting.get(2, TimeUnit.SECONDS)
                if (retiring.get(2, TimeUnit.SECONDS)) {
                    val count = decodes.get()
                    assertThrows(IllegalStateException::class.java) { session.accept(ByteArray(48_000)) }
                    session.close(); assertEquals(count, decodes.get())
                } else session.close()
                assertEquals(1, releases.get())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun retirementRacingCloseReturnsOneLeaseAndCannotReopenStream() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(60) {
                val start = CountDownLatch(1); val releases = AtomicInteger()
                val session = AsyncWhisperSession({ error("Unexpected decode") }, { releases.incrementAndGet() })
                val retiring = executor.submit<Boolean> { start.await(); session.retireIdleCaption() }
                val closing = executor.submit { start.await(); session.close() }
                start.countDown(); retiring.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS)
                session.close()
                assertFalse(session.retireIdleCaption())
                assertThrows(IllegalStateException::class.java) { session.accept(ByteArray(3200)) }
                assertEquals(1, releases.get())
            }
        } finally { executor.shutdownNow() }
    }

    private fun awaitIdleRetirement(session: AsyncWhisperSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!session.retireIdleCaption()) {
            check(System.nanoTime() < deadline) { "Worker did not become idle" }
            Thread.yield()
        }
    }
}
