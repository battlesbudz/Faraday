package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Production worker, with a latch-controlled fake native boundary. No model/device claim. */
class RetainedPcmEncoderLifecycleTest {
    private class Encoder(
        private val appendEntered: CountDownLatch? = null,
        private val appendRelease: CountDownLatch? = null,
        private val sealEntered: CountDownLatch? = null,
        private val sealRelease: CountDownLatch? = null,
        private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
        private val closeSucceeds: Boolean = true,
    ) : RetainedPcmEncoderWorker.Encoder<String> {
        val appends = AtomicInteger()
        val seals = AtomicInteger()
        val cancels = AtomicInteger()
        val closes = AtomicInteger()
        private var samples = 0
        private fun pause(entered: CountDownLatch?, release: CountDownLatch?) {
            entered?.countDown()
            check(release == null || release.await(5, TimeUnit.SECONDS)) { "test native latch timed out" }
        }
        override fun append(pcm: FloatArray) {
            appends.incrementAndGet()
            pause(appendEntered, appendRelease)
            samples += pcm.size
        }
        override fun seal(): RetainedPcmEncoderWorker.Sealed<String> {
            seals.incrementAndGet()
            pause(sealEntered, sealRelease)
            return RetainedPcmEncoderWorker.Sealed(samples, "completed fake native content")
        }
        override fun requestCancel() { cancels.incrementAndGet() }
        override fun closeOnWorker(timeoutMs: Long): Boolean {
            closes.incrementAndGet()
            pause(closeEntered, closeRelease)
            return closeSucceeds
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue("worker did not reach latch", latch.await(3, TimeUnit.SECONDS))

    @Test fun cancellationDuringFactoryRetainsPendingOwnerThenClosesItWithoutAppendOrSeal() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val encoder = Encoder()
        val packet = byteArrayOf(1, 0)
        val worker = RetainedPcmEncoderWorker({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            encoder
        }, { true })
        try {
            worker.onPcm(packet)
            await(entered)
            worker.requestCancel()
            assertFalse(worker.closeAndDrain(1))
            assertEquals(0, encoder.closes.get())
            release.countDown()
            assertTrue(worker.closeAndDrain(3000))
            assertEquals(0, encoder.appends.get())
            assertEquals(0, encoder.seals.get())
            assertTrue(encoder.cancels.get() > 0)
            assertEquals(1, encoder.closes.get())
            assertArrayEquals(byteArrayOf(0, 0), packet)
        } finally {
            release.countDown()
            worker.requestCancel()
            worker.closeAndDrain(3000)
        }
    }

    @Test fun cancellationDuringSealNeverPublishesEvenWhenNativeSealLaterReturnsSuccess() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val encoder = Encoder(sealEntered = entered, sealRelease = release)
        val worker = RetainedPcmEncoderWorker({ encoder }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { worker.sealAfterCaptureJoined(byteArrayOf(1, 0)) }
            }
            await(entered)
            worker.requestCancel()
            assertFalse(worker.closeAndDrain(1))
            assertEquals(0, encoder.closes.get())
            release.countDown()
            assertTrue(withTimeout(3000) { answer.await() }.isFailure)
            assertTrue(worker.closeAndDrain(3000))
            assertEquals(1, encoder.seals.get())
            assertEquals(1, encoder.closes.get())
        } finally {
            release.countDown()
            worker.requestCancel()
            worker.closeAndDrain(3000)
        }
    }

    @Test fun completedContentWaitsForCheckedCloseAndCancellationDuringCloseSuppressesIt() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val encoder = Encoder(closeEntered = entered, closeRelease = release)
        val worker = RetainedPcmEncoderWorker({ encoder }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { worker.sealAfterCaptureJoined(byteArrayOf(1, 0)) }
            }
            await(entered)
            assertEquals(1, encoder.seals.get())
            assertFalse("completed native rows are provisional until checked close", answer.isCompleted)
            worker.requestCancel()
            assertFalse(worker.closeAndDrain(1))
            release.countDown()
            assertTrue(withTimeout(3000) { answer.await() }.isFailure)
            assertTrue(worker.closeAndDrain(3000))
            assertEquals(1, encoder.closes.get())
        } finally {
            release.countDown()
            worker.requestCancel()
            worker.closeAndDrain(3000)
        }
    }

    @Test fun discardDuringFactoryCreatesReplacementOnlyAfterOldOwnerCloses() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Encoder()
        val replacement = Encoder()
        val creates = AtomicInteger()
        val worker = RetainedPcmEncoderWorker({ candidate ->
            creates.incrementAndGet()
            if (candidate == 1L) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                first
            } else {
                assertEquals(2L, candidate)
                assertEquals(1, first.closes.get())
                replacement
            }
        }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            await(entered)
            worker.onCandidateDiscarded()
            worker.onPcm(byteArrayOf(2, 0, 3, 0))
            release.countDown()
            val answer = withTimeout(3000) { worker.sealAfterCaptureJoined(byteArrayOf(2, 0, 3, 0)) }
            assertEquals(2L, answer.candidate)
            assertEquals(2, answer.pcmSampleCount)
            assertEquals(2, creates.get())
            assertEquals(0, first.appends.get())
            assertEquals(0, first.seals.get())
            assertTrue(first.cancels.get() > 0)
            assertEquals(1, replacement.seals.get())
            assertTrue(worker.closeAndDrain(3000))
        } finally {
            release.countDown()
            worker.requestCancel()
            worker.closeAndDrain(3000)
        }
    }

    @Test fun failedDiscardedOwnerClosePreventsReplacementCreationAndSealedPublication() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Encoder(appendEntered = entered, appendRelease = release, closeSucceeds = false)
        val creates = AtomicInteger()
        val queued = byteArrayOf(2, 0)
        val worker = RetainedPcmEncoderWorker({ creates.incrementAndGet(); first }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            await(entered)
            worker.onCandidateDiscarded()
            worker.onPcm(queued)
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { worker.sealAfterCaptureJoined(byteArrayOf(2, 0)) }
            }
            release.countDown()
            assertEquals("native_audio_discard_not_drained", withTimeout(3000) { answer.await() }.exceptionOrNull()?.message)
            assertFalse(worker.closeAndDrain(3000))
            assertFalse("a failed checked close remains quarantined", worker.closeAndDrain(3000))
            assertEquals(1, creates.get())
            assertEquals(0, first.seals.get())
            assertEquals(1, first.closes.get())
            assertArrayEquals(byteArrayOf(0, 0), queued)
        } finally {
            release.countDown()
            worker.requestCancel()
            worker.closeAndDrain(3000)
        }
    }
}
