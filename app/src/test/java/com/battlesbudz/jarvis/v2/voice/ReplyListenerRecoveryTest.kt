package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyListenerRecoveryTest {
    @Test fun failureRestartsOnlyAfterOldMicrophoneCleanup() = runBlocking {
        var open = false
        var attempts = 0
        val result = recoverReplyListener {
            assertFalse(open); open = true
            try { if (++attempts == 1) error("buffer overflow"); "heard keyword" }
            finally { open = false }
        }
        assertEquals("heard keyword", result)
        assertEquals(2, attempts)
        assertFalse(open)
    }
    @Test fun explicitStopDuringRecoveryDoesNotRestart() = runBlocking {
        var attempts = 0
        val failed = CompletableDeferred<Unit>()
        val job = launch {
            recoverReplyListener(log = { failed.complete(Unit) }) { attempts++; error("overflow") }
        }
        failed.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
