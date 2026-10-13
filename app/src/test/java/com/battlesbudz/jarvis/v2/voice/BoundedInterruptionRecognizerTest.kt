package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedInterruptionRecognizerTest {
    @Test fun probeModePrecedesAllAudioAndFinalizationPublishesAfterRelease() = runBlocking {
        var clock = 0L
        var prepared = false
        var finishes = 0
        var closed = false
        val fed = java.io.ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        val pcm = ByteArray(35200) { (it % 251).toByte() }
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun prepareForBoundedProbe(maxAudioMs: Long): String {
                    assertEquals(4000L, maxAudioMs); prepared = true; return "final_only_test"
                }
                override fun accept(pcm: ByteArray): String {
                    check(prepared); fed.write(pcm); clock += 10; return ""
                }
                override fun finish(): String { finishes++; clock += 600; return "Actually tell me about dogs" }
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined, log = logs::add)
        worker.submit(1, pcm, 0)
        assertArrayEquals(pcm, fed.toByteArray())
        assertEquals(1, finishes); assertTrue(closed)
        assertEquals("Actually tell me about dogs", worker.poll()?.text)
        assertTrue(logs.any { "feedMs=50 finalizeMs=600 acceptCalls=5 maxAcceptMs=10 decodeStage=finish" in it })
        worker.close()
    }
    @Test fun slowFinalPassIsStillRejectedAndIdentified() = runBlocking {
        var clock = 0L
        var closes = 0
        val logs = mutableListOf<String>()
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = ""
                override fun finish(): String { clock += 2700; return "Actually open settings" }
                override fun close() { closes++ }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined, log = logs::add)
        worker.submit(1, ByteArray(32000), 0)
        assertNull(worker.poll()); assertEquals("decode_budget", worker.retryReason)
        assertEquals(1, closes)
        assertTrue(logs.any { "finalizeMs=2700" in it && "decodeStage=finish" in it && "outcome=decode_budget" in it })
        worker.close()
    }
    @Test fun loadAndReleaseDoNotConsumeDecodeAllowance() = runBlocking {
        var clock = 0L
        var closed = false
        val logs = mutableListOf<String>()
        val worker = BoundedInterruptionRecognizer(this, {
            clock += 800
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { clock += 300; return "" }
                override fun finish() = "Actually tell me what two plus two is"
                override fun close() { clock += 700; closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined, log = logs::add)
        worker.submit(1, ByteArray(32000), 0)
        assertTrue(closed)
        assertEquals(2700L, worker.poll()?.workMs)
        assertFalse(worker.retryableFailure)
        assertTrue(logs.any { "loadMs=800 decodeMs=1200 releaseMs=700" in it && "outcome=result" in it })
        worker.close()
    }
    @Test fun excessiveLoadClosesOwnerWithoutStartingDecode() = runBlocking {
        var clock = 0L
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            clock += InterruptionTiming.LOAD_MS + 1
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String = error("Must not decode after excessive load")
                override fun finish(): String = error("Must not finalize after excessive load")
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertTrue(closed); assertNull(worker.poll())
        assertTrue(worker.retryableFailure); assertFalse(worker.unavailable)
        assertEquals("load_budget", worker.retryReason)
        worker.close()
    }
    @Test fun slowReleaseCannotPublishAnOldResultOrCloseTwice() = runBlocking {
        var clock = 0L
        var closes = 0
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = ""
                override fun finish() = "Actually open settings"
                override fun close() { closes++; clock += InterruptionTiming.RESULT_AGE_MS + 1 }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertNull(worker.poll()); assertEquals("result_too_old", worker.retryReason)
        worker.close()
        assertEquals(1, closes)
    }
    @Test fun oneOwnerPublishesResultOnlyAfterRelease() = runBlocking {
        var clock = 100L
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { clock += 10; return "actually" }
                override fun finish() = "Actually use the other one"
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        assertTrue(worker.submit(7, ByteArray(32000), 100))
        val result = worker.poll()!!
        assertTrue(closed)
        assertEquals(7L, result.revision)
        assertEquals(40L, result.workMs)
        assertEquals("Actually use the other one", result.text)
        worker.close()
        assertFalse(worker.submit(8, ByteArray(2), 100))
    }
    @Test fun overBudgetDecodeClosesOnItsOwnerAndSuppressesResult() = runBlocking {
        var clock = 0L
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { clock += 800; return "open settings" }
                override fun finish(): String = error("Budget must stop before finalization")
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertFalse(worker.unavailable); assertTrue(worker.retryableFailure); assertTrue(closed); assertNull(worker.poll())
        assertEquals("decode_budget", worker.retryReason)
        assertTrue(worker.submit(2, ByteArray(32000), clock))
        worker.close()
    }
    @Test fun nativeFailureAndCloseFailureDoNotEscapeOptionalWorker() = runBlocking {
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String = error("native failure")
                override fun finish() = ""
                override fun close() { error("release failed") }
            }
        }, nowMs = { 0L }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertTrue(worker.unavailable); assertNull(worker.poll()); worker.close()
    }
    @Test fun busyWorkerRefusesAnotherWindowWithoutBlockingCaller() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String {
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return "actually stop"
                }
                override fun finish() = "actually stop"
                override fun close() { closed = true }
            }
        }, nowMs = { 0L })
        try {
            assertTrue(worker.submit(1, ByteArray(8000), 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.submit(2, ByteArray(8000), 0))
        } finally { release.countDown(); worker.close() }
        assertTrue(closed); assertNull(worker.poll())
    }
    @Test fun playbackPressureStopsFurtherInference() = runBlocking {
        var budget = true
        var calls = 0
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { calls++; if (calls == 1) budget = false; return "stop" }
                override fun finish(): String { check(budget); return "Actually open settings" }
                override fun close() {}
            }
        }, nowMs = { 0L }, dispatcher = Dispatchers.Unconfined, hasBudget = { budget })
        worker.submit(1, ByteArray(32000), 0)
        assertEquals(1, calls); assertFalse(worker.unavailable); assertTrue(worker.retryableFailure)
        assertNull(worker.poll())
        budget = true
        assertTrue(worker.submit(2, ByteArray(32000), 0))
        assertEquals(5, calls)
        assertEquals("Actually open settings", worker.poll()!!.text)
        assertFalse(worker.retryableFailure)
        worker.close()
    }
    @Test fun admittedDecodeFinishesWhenAudioFallsBelowAdmissionButAboveEmergencyFloor() = runBlocking {
        var queued: Long? = null
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { queued = 240; return "Actually tell me about dogs" }
                override fun finish() = "Actually tell me about dogs"
                override fun close() {}
            }
        }, nowMs = { 0L }, dispatcher = Dispatchers.Unconfined,
            hasBudget = { DuplexPlaybackBudget.allows(queued, false, false) },
            canContinue = { DuplexPlaybackBudget.allows(queued, true, false) })
        worker.submit(1, ByteArray(32000), 0)
        assertEquals("Actually tell me about dogs", worker.poll()?.text)
        assertFalse(worker.retryableFailure)
        worker.close()
    }

}
