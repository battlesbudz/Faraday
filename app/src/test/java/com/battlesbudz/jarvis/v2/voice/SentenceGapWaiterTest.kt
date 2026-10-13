package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SentenceGapWaiterTest {
    private fun waiter() = SentenceGapWaiter(pollMs = 1, prolongedStallMs = 40, maxRefillMs = 80)
    @Test fun routineShortageRefillsSilently() = runBlocking {
        val channel = Channel<Int>(1); channel.send(7)
        var buffer = 240L; var calls = 0; val transitions = mutableListOf<Boolean>()
        val receive = async { waiter().receive(channel, { 0 }, { buffer },
            onRefill = transitions::add) { calls++ } }
        delay(10); assertFalse(receive.isCompleted); buffer = 1000
        assertEquals(7, withTimeout(1000) { receive.await() }.getOrThrow())
        assertEquals(listOf(true, false), transitions); assertEquals(0, calls)
    }
    @Test fun completedShortTailNeverWaitsForUnreachableHeadroom() = runBlocking {
        val channel = Channel<Int>(1); channel.send(8); channel.close()
        assertEquals(8, waiter().receive(channel, { 0 }, { 80 }, productionFinished = { true }) {
            fail("cue after completed generation")
        }.getOrThrow())
    }
    @Test fun bufferedAnswerAndClosedStreamNeverStartCue() = runBlocking {
        val channel = Channel<Int>(1); channel.send(7); channel.close()
        var calls = 0
        assertEquals(7, waiter().receive(channel, { 0 }, { 1000 }) { calls++ }.getOrThrow())
        assertTrue(waiter().receive(channel, { 0 }, { 0 }) { calls++ }.isClosed)
        assertEquals(0, calls)
    }
    @Test fun pausedPlaybackNeverStartsCue() = runBlocking {
        val channel = Channel<Int>(); var calls = 0
        val receive = async { waiter().receive(channel, { 0 }, { 0 }, { false }) { calls++ } }
        channel.send(9)
        assertEquals(9, receive.await().getOrThrow()); assertEquals(0, calls)
    }
    @Test fun answerArrivalDuringCueIsPreservedAndDoesNotTruncateCue() = runBlocking {
        val channel = Channel<Int>(2); val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>(); var released = false
        val receive = async { waiter().receive(channel, { 0 }, { 0 }) {
            try { started.complete(Unit); finish.await() } finally { released = true }
        } }
        withTimeout(1000) { started.await() }; channel.send(3); channel.send(4); yield()
        assertFalse(receive.isCompleted); assertFalse(released)
        finish.complete(Unit)
        assertEquals(3, receive.await().getOrThrow()); assertTrue(released)
        assertEquals(4, channel.receive())
    }
    @Test fun previousSentenceMustDrainBeforeCue() = runBlocking {
        val channel = Channel<Int>(1); channel.send(3)
        var remaining = 100L; val started = CompletableDeferred<Unit>()
        val receive = async { waiter().receive(channel, { remaining }, { 240 }) { started.complete(Unit) } }
        delay(20); assertFalse(started.isCompleted); remaining = 0
        assertEquals(3, withTimeout(1000) { receive.await() }.getOrThrow())
        assertTrue(started.isCompleted)
    }
    @Test fun generationCatchingUpBeforeBoundarySkipsCue() = runBlocking {
        val channel = Channel<Int>(1); channel.send(3)
        var buffer = 240L; var calls = 0
        val receive = async { waiter().receive(channel, { 100 }, { buffer }) { calls++ } }
        delay(20); buffer = 1000
        assertEquals(3, withTimeout(1000) { receive.await() }.getOrThrow()); assertEquals(0, calls)
    }
    @Test fun laterBoundariesNeverRepeatRecovery() = runBlocking {
        val channel = Channel<Int>(1); val gap = waiter(); var calls = 0
        channel.send(1)
        assertEquals(1, gap.receive(channel, { 0 }, { 240 }) { calls++ }.getOrThrow())
        channel.send(2)
        assertEquals(2, gap.receive(channel, { 0 }, { 240 }) { calls++ }.getOrThrow())
        assertEquals(1, calls)
    }
    @Test fun cancellationReleasesActiveCue() = runBlocking {
        val channel = Channel<Int>(); val started = CompletableDeferred<Unit>(); var released = false
        val receive = async { waiter().receive(channel, { 0 }, { 0 }) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        } }
        withTimeout(1000) { started.await() }; receive.cancelAndJoin(); assertTrue(released)
    }
    @Test fun closedProducerFailureIsPreservedWithoutFiller() = runBlocking {
        val channel = Channel<Int>(); val failure = IllegalStateException("native failure")
        channel.close(failure)
        assertSame(failure, waiter().receive(channel, { 0 }, { 0 }) { fail("cue after failure") }.exceptionOrNull())
    }
}
