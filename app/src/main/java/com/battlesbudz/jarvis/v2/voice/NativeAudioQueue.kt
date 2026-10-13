package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/** Bridges a synchronous JNI callback to suspending playback with bounded backpressure. */
internal class NativeAudioQueue<T>(capacity: Int = 2, private val durationMs: (T) -> Long = { 0 }) {
    @Volatile var productionFinished = false
        private set
    private val queuedMs = AtomicLong()
    val bufferedMs: Long get() = queuedMs.get().coerceAtLeast(0)
    fun consumed(value: T) { queuedMs.addAndGet(-durationMs(value)) }
    private val channel = Channel<T>(capacity)
    val chunks: ReceiveChannel<T> get() = channel

    fun sendFromNative(value: T) = runBlocking {
        val duration = durationMs(value)
        queuedMs.addAndGet(duration)
        try { channel.send(value) } catch (error: Throwable) {
            queuedMs.addAndGet(-duration)
            throw error
        }
    }
    fun close(cause: Throwable? = null) { channel.close(cause); productionFinished = true }
    // Cancel BEFORE joining the native producer, so a callback blocked on send can return.
    fun cancel() { channel.cancel() }
}
