package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RetainedPcmEncoderWorkerTest {
    private class FakeEncoder(
        val entered: CountDownLatch? = null,
        val release: CountDownLatch? = null,
        val failAppend: Boolean = false,
        val closeSucceeds: Boolean = true,
        val wrongCount: Boolean = false,
    ) : RetainedPcmEncoderWorker.Encoder<String> {
        val chunks = mutableListOf<FloatArray>()
        val callerArrays = mutableListOf<FloatArray>()
        val threads = mutableListOf<Long>()
        val cancelled = AtomicInteger()
        val closes = AtomicInteger()
        val seals = AtomicInteger()
        override fun append(pcm: FloatArray) {
            threads += Thread.currentThread().id
            entered?.countDown()
            if (release != null) check(release.await(5, TimeUnit.SECONDS)) { "test release timeout" }
            check(!failAppend) { "injected_native_failure" }
            chunks += pcm.copyOf()
            callerArrays += pcm
        }
        override fun seal(): RetainedPcmEncoderWorker.Sealed<String> {
            threads += Thread.currentThread().id
            seals.incrementAndGet()
            return RetainedPcmEncoderWorker.Sealed(chunks.sumOf { it.size } + if (wrongCount) 1 else 0, "immutable_result")
        }
        override fun requestCancel() { cancelled.incrementAndGet() }
        override fun closeOnWorker(timeoutMs: Long): Boolean {
            threads += Thread.currentThread().id
            closes.incrementAndGet()
            return closeSucceeds
        }
    }
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2) { i ->
        if (i % 2 == 0) samples[i / 2].toByte() else (samples[i / 2] shr 8).toByte()
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

    @Test fun earlyPreparationQueueRetainsExistingFullPcmBudgetAndCancellationWipesIt() = runBlocking {
        val gate = EarlyGemmaPreparationGate { true }
        val entered = CountDownLatch(1)
        val created = AtomicInteger()
        val owner = RetainedPcmEncoderWorker({ _: Long ->
            entered.countDown()
            gate.awaitReady()
            created.incrementAndGet()
            FakeEncoder()
        }, { true }, maxQueuedBytes = GemmaStreamingAudioCapture.PREPARATION_QUEUED_BYTES,
            maxEvents = GemmaStreamingAudioCapture.PREPARATION_MAX_EVENTS)
        val packets = (1..300).map { ByteArray(3200) { 1 } }
        try {
            owner.onPcm(packets.first()); await(entered)
            // Actual microphone packets are 100 ms. The full existing 30 seconds
            // must fit while fixed prefill runs, rather than failing at packet258.
            packets.drop(1).forEach(owner::onPcm)
            assertEquals(0, created.get())
            assertEquals("native_audio_sample_budget",
                runCatching { owner.onPcm(pcm(1)) }.exceptionOrNull()?.message)
        } finally {
            gate.cancel(); owner.requestCancel()
            assertTrue(owner.closeAndDrain(3000))
        }
        assertEquals(0, created.get())
        assertTrue(packets.all { packet -> packet.all { it == 0.toByte() } })
    }

    @Test fun nativeWorkClocksAreSeparateAndObserverCannotBreakCapture() = runBlocking {
        var clock = 0L; var count = 0
        val snapshots = java.util.Collections.synchronizedList(mutableListOf<RetainedPcmEncoderWorker.WorkSnapshot>())
        val encoder = object : RetainedPcmEncoderWorker.Encoder<String> {
            override fun append(pcm: FloatArray) { count += pcm.size; clock += 3_000_000 }
            override fun seal(): RetainedPcmEncoderWorker.Sealed<String> {
                clock += 7_000_000; return RetainedPcmEncoderWorker.Sealed(count, "sealed")
            }
            override fun requestCancel() = Unit
            override fun closeOnWorker(timeoutMs: Long) = true
        }
        val owner = RetainedPcmEncoderWorker({ encoder }, { true }, nowNs = { clock }, onWork = {
            snapshots += it; error("diagnostics observer failure")
        })
        val original = pcm(1, 2, 3, 4); owner.onPcm(original.copyOf())
        assertEquals(4, withTimeout(3000) { owner.sealAfterCaptureJoined(original) }.pcmSampleCount)
        assertTrue(owner.closeAndDrain())
        val last = snapshots.last()
        assertEquals(1, last.appendCalls); assertEquals(3_000_000L, last.appendWorkNs)
        assertEquals(3_000_000L, last.maxAppendWorkNs); assertEquals(7_000_000L, last.sealWorkNs)
        assertFalse(last.busy)
        assertEquals(listOf("append", "append", "seal", "seal"), snapshots.map { it.phase })
        assertEquals(listOf(true, false, true, false), snapshots.map { it.busy })
    }
    @Test fun exactPcmConversionAndLargePreRollAreLosslesslySplitOnOneWorker() = runBlocking {
        val encoder = FakeEncoder()
        val owner = RetainedPcmEncoderWorker({ encoder }, { true })
        val original = pcm(*IntArray(19_200) { when (it % 4) { 0 -> -32768; 1 -> 32767; 2 -> -1; else -> 0 } })
        val transferred = original.copyOf()
        owner.onPcm(transferred)
        val result = withTimeout(3000) { owner.sealAfterCaptureJoined(original) }
        assertEquals(19_200, result.pcmSampleCount)
        assertEquals(listOf(16_000, 3200), encoder.chunks.map { it.size })
        assertEquals(-1f, encoder.chunks[0][0])
        assertEquals(32767f / 32768f, encoder.chunks[0][1])
        assertEquals(-1f / 32768f, encoder.chunks[0][2])
        assertTrue(transferred.all { it == 0.toByte() })
        assertTrue(encoder.callerArrays.all { it.all { sample -> sample == 0f } })
        assertEquals(1, encoder.threads.toSet().size)
        assertNotEquals(Thread.currentThread().id, encoder.threads.first())
        assertEquals(1, encoder.closes.get())
        assertTrue(owner.closeAndDrain())
    }

    @Test fun matchingCountButDifferentCompletePcmCannotSeal() = runBlocking {
        val encoder = FakeEncoder()
        val owner = RetainedPcmEncoderWorker({ encoder }, { true })
        owner.onPcm(pcm(1, 2, 3))
        val error = runCatching { withTimeout(3000) { owner.sealAfterCaptureJoined(pcm(1, 2, 4)) } }.exceptionOrNull()
        assertEquals("native_audio_complete_pcm_mismatch", error?.message)
        assertEquals(0, encoder.seals.get())
        assertTrue(owner.closeAndDrain())
    }

    @Test fun discardedInflightCandidateGetsFreshOwnerAndCannotPoisonReplacement() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = FakeEncoder(entered, release, failAppend = true)
        val second = FakeEncoder()
        val candidates = mutableListOf<Long>()
        val owner = RetainedPcmEncoderWorker({ candidate -> candidates += candidate; if (candidate == 1L) first else second }, { true })
        owner.onPcm(pcm(11)); await(entered)
        owner.onCandidateDiscarded()
        owner.onPcm(pcm(22, 33))
        release.countDown()
        val result = withTimeout(3000) { owner.sealAfterCaptureJoined(pcm(22, 33)) }
        assertEquals(2L, result.candidate)
        assertEquals(listOf(1L, 2L), candidates)
        assertTrue(first.cancelled.get() > 0)
        assertEquals(1, first.closes.get())
        assertEquals(0, first.seals.get())
        assertEquals(2, result.pcmSampleCount)
        assertTrue(owner.closeAndDrain())
    }

    @Test fun byteBackpressureFailsWholeCaptureAndWipesQueuedPackets() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val encoder = FakeEncoder(entered, release)
        val owner = RetainedPcmEncoderWorker({ encoder }, { true }, maxQueuedBytes = 38_400)
        owner.onPcm(pcm(1)); await(entered)
        val queued = ByteArray(38_400) { 1 }; val rejected = pcm(2)
        owner.onPcm(queued)
        assertEquals("native_audio_queue_capacity", runCatching { owner.onPcm(rejected) }.exceptionOrNull()?.message)
        assertTrue(rejected.all { it == 0.toByte() })
        assertTrue(encoder.cancelled.get() > 0)
        assertFalse(owner.closeAndDrain(1))
        release.countDown()
        assertTrue(owner.closeAndDrain(3000))
        assertTrue(queued.all { it == 0.toByte() })
        assertEquals(0, encoder.seals.get())
    }

    @Test fun cancellationDuringNativeAppendRetainsOwnerUntilRealDrain() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val encoder = FakeEncoder(entered, release)
        val owner = RetainedPcmEncoderWorker({ encoder }, { true })
        owner.onPcm(pcm(1)); await(entered)
        owner.requestCancel()
        assertFalse(owner.closeAndDrain(1))
        assertEquals(0, encoder.closes.get())
        release.countDown()
        assertTrue(owner.closeAndDrain(3000))
        assertEquals(1, encoder.closes.get())
        assertEquals(0, encoder.seals.get())
    }

    @Test fun nativeCountMismatchAndFailureNeverReturnSealedContent() = runBlocking {
        for (encoder in listOf(FakeEncoder(wrongCount = true), FakeEncoder(failAppend = true))) {
            val owner = RetainedPcmEncoderWorker({ encoder }, { true })
            owner.onPcm(pcm(1))
            assertNotNull(runCatching { withTimeout(3000) { owner.sealAfterCaptureJoined(pcm(1)) } }.exceptionOrNull())
            assertTrue(owner.closeAndDrain())
        }
    }

    @Test fun closeFailureQuarantinesInsteadOfReturningCompletedContent() = runBlocking {
        val encoder = FakeEncoder(closeSucceeds = false)
        val owner = RetainedPcmEncoderWorker({ encoder }, { true })
        owner.onPcm(pcm(1))
        assertNotNull(runCatching { withTimeout(3000) { owner.sealAfterCaptureJoined(pcm(1)) } }.exceptionOrNull())
        assertFalse(owner.closeAndDrain())
        assertEquals(1, encoder.closes.get())
    }

    @Test fun staleGenerationAndOddPcmRejectBeforeNativeCreation() = runBlocking {
        val creates = AtomicInteger()
        for (current in listOf(true, false)) {
            val owner = RetainedPcmEncoderWorker({ creates.incrementAndGet(); FakeEncoder() }, { current })
            assertNotNull(runCatching { owner.onPcm(if (current) byteArrayOf(1) else pcm(1)) }.exceptionOrNull())
            assertTrue(owner.closeAndDrain())
        }
        assertEquals(0, creates.get())
    }
    /**
     * Option A fake: rows are a pure function of completed steps — step s
     * owns rows [s*12, s*12+12) with values s*1000 + index — so a snapshot
     * and the sealed row set of the same pass must agree step for step.
     * One step completes per 4 appended samples.
     */
    private class StepFakeEncoder : RetainedPcmEncoderWorker.Encoder<String> {
        val chunks = mutableListOf<FloatArray>()
        val seals = AtomicInteger()
        fun totalRows(): Int = chunks.sumOf { it.size } / 4 * 12
        fun rows(fromRow: Int, totalRows: Int) =
            FloatArray((totalRows - fromRow) * 2) { (fromRow + it / 2) * 1000f + it % 2 }
        override fun append(pcm: FloatArray) { chunks += pcm.copyOf() }
        override fun provisionalRows(fromRow: Int): RetainedPcmEncoderWorker.ProvisionalRows? {
            val total = totalRows()
            if (fromRow >= total) return null
            return RetainedPcmEncoderWorker.ProvisionalRows(fromRow, total, rows(fromRow, total))
        }
        override fun seal(): RetainedPcmEncoderWorker.Sealed<String> {
            seals.incrementAndGet()
            return RetainedPcmEncoderWorker.Sealed(chunks.sumOf { it.size }, "sealed")
        }
        override fun requestCancel() = Unit
        override fun closeOnWorker(timeoutMs: Long) = true
    }

    @Test fun provisionalSnapshotsStreamCompletedStepsAndMatchTheSealedRowsStepForStep() = runBlocking {
        val encoder = StepFakeEncoder()
        val delivered = java.util.Collections.synchronizedList(
            mutableListOf<Pair<Long, RetainedPcmEncoderWorker.ProvisionalRows>>())
        val owner = RetainedPcmEncoderWorker({ encoder }, { true },
            onProvisionalRows = { candidate, snapshot -> delivered += candidate to snapshot })
        val packet = pcm(1, 2, 3, 4, 5, 6, 7, 8) // 8 samples = 2 steps = 24 rows
        owner.onPcm(packet.copyOf())
        owner.onPcm(packet.copyOf())
        val result = withTimeout(3000) { owner.sealAfterCaptureJoined(packet + packet) }
        assertEquals(16, result.pcmSampleCount)
        assertEquals(1, encoder.seals.get())
        // One snapshot per packet event, chained 0 -> 24 -> 48.
        assertEquals(listOf(1L to 0, 1L to 24), delivered.map { it.first to it.second.firstRow })
        assertEquals(listOf(24, 48), delivered.map { it.second.totalRows })
        // Step-for-step equality against the same pass's full row set.
        val full = encoder.rows(0, 48)
        for ((_, snapshot) in delivered) {
            assertTrue(snapshot.values.contentEquals(
                full.copyOfRange(snapshot.firstRow * 2, snapshot.totalRows * 2)))
        }
        assertTrue(owner.closeAndDrain())
    }

    @Test fun provisionalDeliveryRestartsWithAFreshCandidate() = runBlocking {
        val delivered = java.util.Collections.synchronizedList(
            mutableListOf<Pair<Long, RetainedPcmEncoderWorker.ProvisionalRows>>())
        val owner = RetainedPcmEncoderWorker({ StepFakeEncoder() }, { true },
            onProvisionalRows = { candidate, snapshot -> delivered += candidate to snapshot })
        val packet = pcm(1, 2, 3, 4)
        owner.onPcm(packet.copyOf())
        // The discard must land after candidate 1's packet was processed;
        // its snapshot delivery is the processing receipt.
        withTimeout(3000) { while (delivered.size < 1) { kotlinx.coroutines.delay(5) } }
        owner.onCandidateDiscarded()
        owner.onPcm(packet.copyOf())
        val result = withTimeout(3000) { owner.sealAfterCaptureJoined(packet) }
        assertEquals(2L, result.candidate)
        assertEquals(listOf(1L, 2L), delivered.map { it.first })
        assertEquals(listOf(0, 0), delivered.map { it.second.firstRow })
        assertTrue(owner.closeAndDrain())
    }

    @Test fun encoderWithoutProvisionalSurfaceDeliversNothingAndSealsAsBefore() = runBlocking {
        val encoder = FakeEncoder()
        val delivered = mutableListOf<RetainedPcmEncoderWorker.ProvisionalRows>()
        val owner = RetainedPcmEncoderWorker({ encoder }, { true },
            onProvisionalRows = { _, snapshot -> delivered += snapshot })
        val packet = pcm(1, 2, 3)
        owner.onPcm(packet.copyOf())
        val result = withTimeout(3000) { owner.sealAfterCaptureJoined(packet) }
        assertEquals(3, result.pcmSampleCount)
        assertTrue(delivered.isEmpty())
        assertTrue(owner.closeAndDrain())
    }

    @Test fun frozenCandidateUsesExactCountHashAndTerminatesEncoderBeforeHandoff() = runBlocking {
        val encoder = FakeEncoder()
        val owner = RetainedPcmEncoderWorker({ encoder }, { true })
        val prefix = pcm(1, 2, 3)
        owner.onPcm(prefix.copyOf())
        val result = owner.sealFrozenCandidate(prefix)
        assertEquals(3, result.pcmSampleCount); assertEquals(1, encoder.seals.get())
        assertEquals(1, encoder.closes.get())
        assertTrue(owner.closeAndDrain())
        assertTrue(runCatching { owner.onPcm(pcm(4)) }.isFailure)
    }

    @Test fun frozenCandidateCannotSealEqualCountWrongPcmOrAnIncompletePrefix() = runBlocking {
        for (candidate in listOf(pcm(1, 2, 4), pcm(1, 2))) {
            val encoder = FakeEncoder()
            val owner = RetainedPcmEncoderWorker({ encoder }, { true })
            owner.onPcm(pcm(1, 2, 3))
            assertTrue(runCatching { owner.sealFrozenCandidate(candidate) }.isFailure)
            assertEquals(0, encoder.seals.get()); assertTrue(owner.closeAndDrain())
        }
    }

}
