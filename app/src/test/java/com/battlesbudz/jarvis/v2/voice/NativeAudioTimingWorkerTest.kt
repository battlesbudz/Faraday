package com.battlesbudz.jarvis.v2.voice

import com.google.ai.edge.litertlm.NativeAudioTimingPhase
import com.google.ai.edge.litertlm.NativeAudioTimingReceipt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Fake scalar receipt verifies the real worker's publication boundary, not inference. */
class NativeAudioTimingWorkerTest {
    private class Encoder(private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
        private val wrongTimingCount: Boolean = false,
        private val phaseClock: AtomicLong? = null) : RetainedPcmEncoderWorker.Encoder<String> {
        override fun append(pcm: FloatArray) = Unit
        override fun requestCancel() = Unit
        override fun closeOnWorker(timeoutMs: Long): Boolean {
            closeEntered?.countDown()
            val closed = closeRelease?.await(2, TimeUnit.SECONDS) ?: true
            if (closed) phaseClock?.set(120)
            return closed
        }
        override fun seal() = RetainedPcmEncoderWorker.Sealed(1, "sealed", NativeAudioCaptureTiming(
            NativeAudioTimingReceipt(NativeAudioTimingPhase.SEALED, 99, if (wrongTimingCount) 2 else 1,
                1, 1, true, false, false, null, null, null, null, null), 1, 2)).also { phaseClock?.set(110) }
    }

    @Test fun timingIsPublishedOnlyWithSuccessfulCheckedCloseStamps() = runBlocking {
        val clock = AtomicLong(100)
        // Advance at semantic native boundaries, not on observation. Additional append/seal
        // diagnostic reads must not change the expected checked-close interval.
        val worker = RetainedPcmEncoderWorker({ Encoder(phaseClock = clock) }, { true }, nowNs = clock::get)
        try {
            worker.onPcm(byteArrayOf(1, 0))
            val result = withTimeout(3000) { worker.sealAfterCaptureJoined(byteArrayOf(1, 0)) }
            assertEquals(110L, result.timing!!.checkedCloseCalledAtNs)
            assertEquals(120L, result.timing!!.checkedCloseAtNs)
            assertEquals(99L, result.timing!!.receipt.producerInstance)
            assertNotNull(result.timing!!.metrics())
        } finally { assertTrue(worker.closeAndDrain(3000)) }
    }

    @Test fun cancellationDuringCheckedCloseSuppressesTimingAndContentTogether() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = RetainedPcmEncoderWorker({ Encoder(entered, release) }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { worker.sealAfterCaptureJoined(byteArrayOf(1, 0)) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(result.isCompleted)
            worker.requestCancel()
            release.countDown()
            assertTrue(withTimeout(3000) { result.await() }.isFailure)
        } finally { release.countDown(); assertTrue(worker.closeAndDrain(3000)) }
    }

    @Test fun unrelatedReceiptCountCannotBeAttachedToCompletedContent() = runBlocking {
        val worker = RetainedPcmEncoderWorker({ Encoder(wrongTimingCount = true) }, { true })
        try {
            worker.onPcm(byteArrayOf(1, 0))
            val result = withTimeout(3000) { worker.sealAfterCaptureJoined(byteArrayOf(1, 0)) }
            assertNull(result.timing)
            assertEquals("sealed", result.content)
        } finally { assertTrue(worker.closeAndDrain(3000)) }
    }
}
