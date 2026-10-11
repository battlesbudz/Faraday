package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WhisperCancellationTest {
    @Test fun closeCancelsBusyDecodeBeforeJoiningAndNeverReleasesUnderneathIt() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val finished = AtomicBoolean(false); var releases = 0
        val session = AsyncWhisperSession(decode = {
            entered.countDown(); check(cancelled.await(2, TimeUnit.SECONDS)); finished.set(true); ""
        }, release = { assertTrue(finished.get()); releases++ }, cancel = { cancelled.countDown() })
        session.observeSpeech(true); session.accept(ByteArray(48000))
        assertTrue(entered.await(2, TimeUnit.SECONDS)); session.close(); session.close()
        assertEquals(1, releases); assertNull(session.completedEndpointCue)
    }
    @Test fun closeRevokesFinalResultWhileKeepingNativeReleaseOrdered() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val finished = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<String?>(); val failed = AtomicBoolean(false)
        val session = AsyncWhisperSession(decode = {
            entered.countDown(); check(cancelled.await(2, TimeUnit.SECONDS)); "stale final"
        }, release = {}, cancel = { cancelled.countDown() })
        session.observeSpeech(true); session.accept(ByteArray(3200), false)
        val waiter = Thread {
            try { result.set(session.finish()) } catch (_: Exception) { failed.set(true) } finally { finished.countDown() }
        }
        waiter.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); session.close()
        assertTrue(finished.await(2, TimeUnit.SECONDS)); waiter.join(); assertTrue(failed.get()); assertNull(result.get())
    }
    @Test fun idleCloseReturnsHealthyLeaseWithoutCancellingIt() {
        var cancelled = 0; var released = 0
        val session = AsyncWhisperSession({ "" }, { released++ }, cancel = { cancelled++ })
        session.close(); assertEquals(0, cancelled); assertEquals(1, released)
    }
}
