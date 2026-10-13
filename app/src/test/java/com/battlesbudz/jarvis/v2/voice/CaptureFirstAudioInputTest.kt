package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Actual retained session and bridge; only the physical microphone is a fake. */
class CaptureFirstAudioInputTest {
    @Test fun sameTimestampBoundaryPreservesExactPrefixThenLiveTailAndSequence() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start()
            val frames = (1..7).map { value -> ByteArray(12_800) { (value + it % 97).toByte() } }
            frames.take(5).forEachIndexed { index, bytes -> rig.push(bytes, listOf(1000L, 1400L, 1800L, 1800L, 1800L)[index]) }
            rig.bridge.replyInput.start()
            // A gain/keyword consumer must never mutate the bridge's retained raw prefix.
            rig.bridge.replyInput.chunks().take(5).collect { it.fill(99) }
            val next = rig.bridge.beginFollowup(playbackEndedAtMs = 3000)
            rig.bridge.replyInput.stop()
            next.start()
            frames.drop(5).forEach { rig.push(it, 1800) }
            val actual = next.chunks().take(5).map {
                Triple(requireNotNull(next.lastChunkSequence), next.lastChunkCaptureTimeMs, it)
            }.toList()
            assertEquals(listOf(3L, 4L, 5L, 6L, 7L), actual.map { it.first })
            assertTrue(actual.all { it.second == 1800L })
            actual.zip(frames.drop(2)).forEach { (seen, expected) -> assertArrayEquals(expected, seen.third) }
            assertEquals(7L, rig.acknowledged)
            assertEquals(1, rig.hardware.starts)
            assertEquals(0, rig.hardware.stops)
        } finally { rig.close() }
        assertEquals(1, rig.hardware.stops)
    }

    @Test fun inFlightFrameCrossesTheTransferBoundaryExactlyOnce() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        val parked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            rig.start(); rig.push(byteArrayOf(1, 2), 10)
            rig.hardware.beforeNextEmit = { parked.complete(Unit); release.await() }
            val incoming = async { rig.push(byteArrayOf(3, 4), 10) }
            withTimeout(2000) { parked.await() }
            val next = rig.bridge.beginFollowup(); next.start()
            release.complete(Unit); incoming.await(); rig.push(byteArrayOf(5, 6), 10)
            val seen = next.chunks().take(3).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals(listOf(1L, 2L, 3L), seen.map { it.first })
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), seen.flatMap { it.second.asIterable() }.toByteArray())
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun moreThan64ShortFramesAreBoundedByBytesAndRemainComplete() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start()
            val frames = (1..160).map { value -> ByteArray(160) { (value + it).toByte() } }
            frames.forEach { rig.push(it) }
            val next = rig.bridge.beginFollowup(); next.start()
            val actual = next.chunks().take(frames.size).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals((1L..160L).toList(), actual.map { it.first })
            actual.zip(frames).forEach { (seen, expected) -> assertArrayEquals(expected, seen.second) }
            assertEquals(1, rig.hardware.starts)
            assertTrue(rig.session.usable)
        } finally { rig.close() }
    }

    @Test fun fullSixSecondQueueIsAcceptedButOneMoreSampleFailsClosed() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start()
            val next = rig.bridge.beginFollowup(); next.start()
            repeat(60) { rig.push(ByteArray(3200) { it.toByte() }) }
            assertEquals(6000L, next.bufferedAudioMs)
            rig.hardware.push(byteArrayOf(1, 2), 6001)
            yield() // Let the single-threaded raw producer process the rejected frame.
            // Failure must reject even the already queued prefix, never return a partial command.
            try { withTimeout(2000) { next.chunks().first() }; fail("Overflow returned partial PCM") }
            catch (_: AudioBacklogException) { }
            assertEquals(60L, rig.admitted)
            assertEquals(0L, rig.acknowledged)
            assertEquals(0, rig.hardware.stops)
            try { rig.bridge.beginFollowup(); fail("Failed owner was reused") }
            catch (_: AudioBacklogException) { }
        } finally { rig.close() }
    }

    @Test fun logicalOldReaderStopDoesNotStopTheRetainedSource() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.push(byteArrayOf(1, 2))
            val next = rig.bridge.beginFollowup(); next.start()
            rig.bridge.replyInput.stop(); rig.bridge.replyInput.stop()
            rig.push(byteArrayOf(3, 4))
            assertEquals(0, rig.hardware.stops)
            assertEquals(1, rig.hardware.starts)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), next.chunks().take(2).toList().flatMap { it.asIterable() }.toByteArray())
            rig.bridge.close(); rig.bridge.close()
            assertEquals(0, rig.hardware.stops) // The call owner, not its borrower, owns hardware stop.
            val ordinary = rig.session.borrow("command"); ordinary.start()
            rig.hardware.push(byteArrayOf(5, 6), 30)
            assertArrayEquals(byteArrayOf(5, 6), withTimeout(2000) { ordinary.chunks().first() })
            ordinary.stop()
        } finally { rig.close() }
        assertEquals(1, rig.hardware.stops)
    }

    @Test fun cancellingStructuredOwnerStopsHardwareExactlyOnce() = runBlocking {
        val started = CompletableDeferred<CaptureFirstTestRig>()
        val owner = launch {
            val rig = CaptureFirstTestRig(this)
            try { rig.start(); started.complete(rig); awaitCancellation() }
            finally { rig.close() }
        }
        val rig = withTimeout(2000) { started.await() }
        rig.push(byteArrayOf(7, 8))
        owner.cancelAndJoin(); rig.close()
        assertEquals(1, rig.hardware.starts)
        assertEquals(1, rig.hardware.stops)
        assertFalse(rig.session.usable)
    }

    @Test fun transferredReaderCannotBeCreatedTwiceOrStartedAfterOwnerClose() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); val next = rig.bridge.beginFollowup()
            try { rig.bridge.beginFollowup(); fail("Duplicate handoff accepted") }
            catch (error: IllegalStateException) { assertEquals("capture_handoff_already_transferred", error.message) }
            rig.bridge.close()
            try { next.start(); fail("Closed owner accepted a reader") }
            catch (_: IllegalStateException) { }
        } finally { rig.close() }
    }
    @Test fun deferredUnseenPrefetchSurvivesInTheNextActualSessionBorrower() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start()
            val next = rig.bridge.beginFollowup()
            next.deferConsumptionAcknowledgement(); next.start()
            repeat(3) { rig.push(byteArrayOf((it + 1).toByte(), 0)) }
            val prefetched = next.chunks().take(3).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals(listOf(1L, 2L, 3L), prefetched.map { it.first })
            assertEquals(0L, rig.acknowledged)
            next.acknowledgeConsumed(1)
            next.stop(); next.stop()
            assertEquals(1L, rig.acknowledged)
            assertEquals(0, rig.hardware.stops)
            rig.hardware.push(byteArrayOf(4, 0), 40)
            val ordinary = rig.session.borrow("command"); ordinary.start()
            val retained = ordinary.chunks().take(3).map { requireNotNull(ordinary.lastChunkSequence) to it }.toList()
            assertEquals(listOf(2L, 3L, 4L), retained.map { it.first })
            assertArrayEquals(byteArrayOf(2, 0, 3, 0, 4, 0), retained.flatMap { it.second.asIterable() }.toByteArray())
            ordinary.stop()
        } finally { rig.close() }
        assertEquals(1, rig.hardware.stops)
    }

    @Test fun retiredReplyCannotAcknowledgeAwayTheNextTurnsUnseenTail() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start()
            val old = rig.bridge.replyInput
            old.deferConsumptionAcknowledgement(); old.start()
            repeat(3) { rig.push(byteArrayOf((it + 1).toByte(), 0)) }
            old.chunks().take(3).collect()
            old.acknowledgeConsumed(1)
            val next = rig.bridge.beginFollowup()
            old.acknowledgeConsumed(3); old.stop()
            assertEquals(1L, rig.acknowledged)
            next.stop()
            val ordinary = rig.session.borrow("command"); ordinary.start()
            val retained = ordinary.chunks().take(2).map { requireNotNull(ordinary.lastChunkSequence) to it }.toList()
            assertEquals(listOf(2L, 3L), retained.map { it.first })
            assertArrayEquals(byteArrayOf(2, 0, 3, 0), retained.flatMap { it.second.asIterable() }.toByteArray())
            ordinary.stop()
        } finally { rig.close() }
    }

    @Test fun delayedPlaybackBoundaryRejectsAlreadyEvictedRequiredPrefix() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(61) { rig.push(ByteArray(3200) { value -> value.toByte() }, it * 100L) }
            try { rig.bridge.beginFollowup(playbackEndedAtMs = 1000); fail("Missing onset prefix must fail closed") }
            catch (_: AudioBacklogException) { }
            consuming.cancelAndJoin()
        } finally { rig.close() }
    }

    @Test fun delayedTransferIncludesPlaybackPrefixAndAllLiveTailWithoutDuplicates() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(50) { rig.push(byteArrayOf(it.toByte(), 0), it * 100L) }
            val next = rig.bridge.beginFollowup(playbackEndedAtMs = 3000)
            consuming.cancelAndJoin(); next.start()
            val actual = next.chunks().take(32).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals((19L..50L).toList(), actual.map { it.first })
            assertArrayEquals((18..49).flatMap { listOf(it.toByte(), 0.toByte()) }.toByteArray(),
                actual.flatMap { it.second.asIterable() }.toByteArray())
        } finally { rig.close() }
    }


    @Test fun retainedReplyCandidateOlderThanPlaybackPrefixTransfersEveryByteOnce() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            val frames = (1..40).map { value -> ByteArray(3200) { (value + it % 89).toByte() } }
            frames.forEachIndexed { index, pcm -> rig.push(pcm, index * 100L) }
            rig.bridge.retainCandidate(30L, 25 * 3200) // Source frames 6..30, beginning 2.5 seconds before end.
            val next = rig.bridge.beginFollowup(playbackEndedAtMs = 4000)
            rig.bridge.clearCandidate() // Old listener completion cannot alter the transferred prefix.
            consuming.cancelAndJoin(); next.start()
            rig.push(byteArrayOf(3, 4), 4000)
            val seen = next.chunks().take(36).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals((6L..41L).toList(), seen.map { it.first })
            assertArrayEquals(frames.drop(5).flatMap { it.asIterable() }.toByteArray() + byteArrayOf(3, 4),
                seen.flatMap { it.second.asIterable() }.toByteArray())
        } finally { rig.close() }
    }

    @Test fun rejectedReplyCandidateRestoresOnlyExistingPlaybackPrefix() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(40) { rig.push(ByteArray(3200) { it.toByte() }, it * 100L) }
            rig.bridge.retainCandidate(30L, 25 * 3200); rig.bridge.clearCandidate()
            val next = rig.bridge.beginFollowup(playbackEndedAtMs = 4000)
            consuming.cancelAndJoin(); next.start()
            val seen = next.chunks().take(12).map { requireNotNull(next.lastChunkSequence) }.toList()
            assertEquals((29L..40L).toList(), seen)
        } finally { rig.close() }
    }

    @Test fun expiredRetainedCandidateFailsEvenWhenRecentPlaybackPrefixStillExists() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(30) { rig.push(ByteArray(3200), it * 100L) }
            rig.bridge.retainCandidate(30L, 30 * 3200)
            repeat(31) { rig.push(ByteArray(3200), (it + 30) * 100L) }
            try { rig.bridge.beginFollowup(playbackEndedAtMs = 6100); fail("Expired candidate was silently truncated") }
            catch (_: AudioBacklogException) { }
            consuming.cancelAndJoin()
        } finally { rig.close() }
    }

    @Test fun candidateBeyondSixSecondOwnershipIsRejectedExplicitly() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(60) { rig.push(ByteArray(3200), it * 100L) }
            try { rig.bridge.retainCandidate(60L, 192002); fail("Missing candidate sample accepted") }
            catch (_: AudioBacklogException) { }
            try { rig.bridge.retainCandidate(61L, 2); fail("Unseen source sequence accepted") }
            catch (_: AudioBacklogException) { }
            consuming.cancelAndJoin()
        } finally { rig.close() }
    }

    @Test fun asrDisabledTransferUsesOnlyPostPlaybackBoundaryAndLiveTail() = runBlocking {
        val rig = CaptureFirstTestRig(this)
        try {
            rig.start(); rig.bridge.replyInput.start()
            val consuming = launch(start = CoroutineStart.UNDISPATCHED) { rig.bridge.replyInput.chunks().collect() }
            repeat(10) { rig.push(byteArrayOf(it.toByte(), 0), it * 100L) }
            rig.bridge.retainCandidate(10L, 20)
            val next = rig.bridge.beginFollowup(playbackEndedAtMs = 800, retainOverlap = false)
            consuming.cancelAndJoin(); next.start(); rig.push(byteArrayOf(10, 0), 1000)
            val seen = next.chunks().take(3).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals(listOf(9L, 10L, 11L), seen.map { it.first })
            assertArrayEquals(byteArrayOf(8, 0, 9, 0, 10, 0), seen.flatMap { it.second.asIterable() }.toByteArray())
        } finally { rig.close() }
    }


    @Test fun rawTransferAndBytesContinueWhileCaptionEffectClaimIsParked() = runBlocking {
        val fence = CaptionPublicationFence()
        val claimed = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val oldEffect = async(Dispatchers.Default) {
            fence.publishWhenResolved({ true }) { claim ->
                claimed.countDown(); release.await()
                fence.commit(claim, farewell = true)
            }
        }
        val rig = CaptureFirstTestRig(this, fence::rawOffered, fence::transferToCapture)
        try {
            assertTrue(claimed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            rig.start(); rig.push(byteArrayOf(1, 2), 100)
            val next = rig.bridge.beginFollowup(100); next.start()
            repeat(90) { rig.push(byteArrayOf((it + 3).toByte(), 0), it + 101L) }
            val seen = next.chunks().take(91).map { requireNotNull(next.lastChunkSequence) to it }.toList()
            assertEquals((1L..91L).toList(), seen.map { it.first })
            assertArrayEquals(byteArrayOf(1, 2) + (3..92).flatMap { listOf(it.toByte(), 0.toByte()) }.toByteArray(),
                seen.flatMap { it.second.asIterable() }.toByteArray())
            // Accepted speech invalidates the parked claim before any destructive effect.
            assertTrue(fence.revoke { }); release.countDown()
            assertFalse(withTimeout(2000) { oldEffect.await() })
            assertEquals(0, rig.hardware.stops)
        } finally { release.countDown(); oldEffect.cancelAndJoin(); rig.close() }
        assertEquals(1, rig.hardware.stops)
    }

}

/** Shared test fixture keeps all recorder, session and bridge code real. */
internal class CaptureFirstTestRig(scope: CoroutineScope,
    onRawFrame: (Long) -> Unit = {}, onTransfer: (Long?, Long?) -> Unit = { _, _ -> }) {
    val hardware = CaptureFirstTestHardware()
    val session = VoiceAudioSession(hardware, scope)
    private val retained = session.borrow("command")
    @Volatile var admitted = 0L
        private set
    @Volatile var acknowledged = 0L
        private set
    private val observed = object : AudioInput by retained {
        override fun chunks() = flow {
            retained.chunks().collect { bytes ->
                emit(bytes)
                admitted = requireNotNull(retained.lastChunkSequence)
            }
        }
        override fun acknowledgeConsumed(sequence: Long) {
            retained.acknowledgeConsumed(sequence)
            acknowledged = sequence
        }
    }
    val bridge = CaptureFirstAudioInput(observed, scope, onRawFrame = onRawFrame, onTransfer = onTransfer)
    private var sent = 0L
    suspend fun start() { bridge.start() }
    suspend fun push(pcm: ByteArray, atMs: Long = sent * 5) {
        val expected = ++sent
        hardware.push(pcm, atMs)
        withTimeout(2000) { while (admitted < expected) yield() }
    }
    suspend fun close() = withContext(NonCancellable) {
        try { bridge.close() } finally { session.close() }
    }
}

internal class CaptureFirstTestHardware : AudioInput {
    private data class Packet(val pcm: ByteArray, val atMs: Long, val emitted: CompletableDeferred<Unit>)
    private val packets = Channel<Packet>(Channel.UNLIMITED)
    var starts = 0
    var stops = 0
    var beforeNextEmit: (suspend () -> Unit)? = null
    override val sampleRateHz = 16_000
    override val channelCount = 1
    override var lastChunkCaptureTimeMs: Long? = null
    override suspend fun start() { starts++ }
    override suspend fun stop() { stops++; packets.cancel() }
    override fun chunks() = flow {
        for (packet in packets) {
            val barrier = beforeNextEmit; beforeNextEmit = null; barrier?.invoke()
            lastChunkCaptureTimeMs = packet.atMs
            emit(packet.pcm)
            packet.emitted.complete(Unit)
        }
    }
    suspend fun push(pcm: ByteArray, atMs: Long) {
        val emitted = CompletableDeferred<Unit>()
        packets.send(Packet(pcm, atMs, emitted))
        withTimeout(2000) { emitted.await() }
    }
}
