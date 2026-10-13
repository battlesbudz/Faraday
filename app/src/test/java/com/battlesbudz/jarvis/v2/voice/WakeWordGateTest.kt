package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test

class WakeWordGateTest {
    private class Input : AudioInput {
        val audio = Channel<ByteArray>(8)
        override val sampleRateHz = 16000
        override val channelCount = 1
        override fun chunks() = audio.receiveAsFlow()
        override suspend fun start() {}
        override suspend fun stop() { audio.close() }
    }
    @Test fun ambientSpeechDoesNotStartCallAndPostWakeAudioRemainsAvailable() = runBlocking {
        val input = Input()
        var callStarted = false
        val waiting = launch {
            WakeWordGate.await(input) { it[0] == 7.toByte() }
            callStarted = true
        }
        input.audio.send(byteArrayOf(1)); yield()
        assertFalse(callStarted)
        input.audio.send(byteArrayOf(7))
        input.audio.send(byteArrayOf(9))
        withTimeout(1000) { waiting.join() }
        assertTrue(callStarted)
        assertEquals(9.toByte(), input.audio.receive()[0])
    }
    @Test fun microphoneLossNeverStartsCall() = runBlocking {
        val input = Input()
        var callStarted = false
        val waiting = async {
            runCatching {
                WakeWordGate.await(input) { false }
                callStarted = true
            }.exceptionOrNull()
        }
        input.audio.close(MicrophoneBusyException())
        assertTrue(withTimeout(1000) { waiting.await() } is MicrophoneBusyException)
        assertFalse(callStarted)
    }
}
