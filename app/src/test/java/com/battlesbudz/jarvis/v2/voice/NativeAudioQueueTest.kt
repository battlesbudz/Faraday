package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeAudioQueueTest {
    @Test fun backpressurePreservesOrderWithoutDroppingAudio() = runBlocking {
        val queue = NativeAudioQueue<Int>(2)
        val producer = launch(Dispatchers.Default) {
            repeat(20) { queue.sendFromNative(it) }
            queue.close()
        }
        val received = mutableListOf<Int>()
        withTimeout(2000) {
            for (item in queue.chunks) received += item
            producer.join()
        }
        assertEquals((0..19).toList(), received)
    }

    @Test fun cancellingFullQueueUnblocksNativeCallbackBeforeRelease() = runBlocking {
        val queue = NativeAudioQueue<Int>(1)
        queue.sendFromNative(1)
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val producer = launch(Dispatchers.Default) {
            entered.complete(Unit)
            try { queue.sendFromNative(2) }
            catch (_: CancellationException) { /* JNI adapter returns stop instead of throwing. */ }
            finally { exited.complete(Unit) }
        }
        entered.await()
        assertFalse(exited.isCompleted)
        queue.cancel()
        withTimeout(2000) { producer.join() }
        assertTrue(exited.isCompleted)
    }
    @Test fun normalAnswerRetainsBoundedGenerationHeadroomWithoutReordering() = runBlocking {
        val queue = NativeAudioQueue<Int>(8) { 400 }
        val producer = launch(Dispatchers.Default) {
            repeat(12) { queue.sendFromNative(it) }; queue.close()
        }
        withTimeout(1000) { while (queue.bufferedMs < 3200) delay(1) }
        assertFalse(producer.isCompleted)
        val result = mutableListOf<Int>()
        for (item in queue.chunks) { result += item; queue.consumed(item) }
        producer.join()
        assertEquals((0..11).toList(), result); assertEquals(0L, queue.bufferedMs)
    }
}
