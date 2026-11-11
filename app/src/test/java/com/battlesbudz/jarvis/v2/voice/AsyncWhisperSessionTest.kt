package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncWhisperSessionTest {
    @Test fun deferredPartialsKeepEveryAudioByteForFinalDecode() {
        val clips = mutableListOf<ByteArray>()
        val s = AsyncWhisperSession({ pcm -> clips.add(pcm); "Complete question." }, {})
        try {
            s.observeSpeech(true)
            val expected = ByteArray(96000) { (it % 127).toByte() }
            expected.toList().chunked(3200).forEach { s.accept(it.toByteArray(), false) }
            assertTrue(clips.isEmpty())
            assertEquals("Complete question.", s.finish())
            assertEquals(1, clips.size)
            assertArrayEquals(expected, clips.single())
        } finally { s.close() }
    }
    @Test fun stableWordsRequireAgreementAndFinalIncludesTail() {
        val decoded = mutableListOf<Int>()
        val first = CountDownLatch(1); val second = CountDownLatch(1)
        val count = AtomicInteger()
        var releases = 0
        val s = AsyncWhisperSession(decode = { pcm ->
            synchronized(decoded) { decoded.add(pcm.size) }
            if (count.incrementAndGet() == 1) "Can you tell" else "Can you tell me now?"
        }, release = { releases++ }, log = {
            if (it.startsWith("whisper_partial")) { if (count.get() == 1) first.countDown() else second.countDown() }
        })
        try {
            s.observeSpeech(true)
            assertEquals("", s.accept(ByteArray(64000)))
            assertTrue(first.await(2, TimeUnit.SECONDS))
            s.accept(ByteArray(38400))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (second.count > 0 && System.nanoTime() < deadline) {
                s.accept(ByteArray(0)) // Next microphone poll schedules the newest window after worker completion.
                Thread.yield()
            }
            assertEquals(0L, second.count)
            assertEquals("Can you tell", s.accept(ByteArray(3200)))
            assertEquals("Can you tell me now?", s.finish())
            assertEquals(105600, decoded.last())
        } finally { s.close() }
        assertEquals(1, releases)
    }
    @Test fun busyDecoderDoesNotBlockCaptureOrQueueOldWindows() {
        val began = CountDownLatch(1); val unblock = CountDownLatch(1)
        val calls = AtomicInteger()
        val s = AsyncWhisperSession({ calls.incrementAndGet(); began.countDown(); check(unblock.await(2, TimeUnit.SECONDS)); "hello" }, {})
        try {
            s.observeSpeech(true); s.accept(ByteArray(48000))
            assertTrue(began.await(2, TimeUnit.SECONDS))
            repeat(10) { s.accept(ByteArray(38400)) }
            assertEquals(1, calls.get())
            unblock.countDown()
            assertEquals("hello", s.finish())
            assertEquals(2, calls.get())
        } finally { unblock.countDown(); s.close() }
    }
    @Test fun idleAudioDoesNotTriggerBackgroundInference() {
        var calls = 0
        val s = AsyncWhisperSession({ calls++; "hello" }, {})
        try { repeat(40) { s.accept(ByteArray(3200)) }; assertEquals(0, calls) }
        finally { s.close() }
    }
    @Test fun finalWaitsForBusyPartialAndDecodesContiguousPhraseIncludingQuietTail() {
        val began = CountDownLatch(1); val unblock = CountDownLatch(1)
        val clips = mutableListOf<ByteArray>()
        val s = AsyncWhisperSession({ pcm ->
            clips.add(pcm)
            if (clips.size == 1) { began.countDown(); check(unblock.await(2, TimeUnit.SECONDS)) }
            "complete phrase"
        }, {})
        try {
            s.observeSpeech(false)
            s.accept(ByteArray(64000) { 1 }, false)
            s.observeSpeech(true)
            val speech = ByteArray(48000) { 2 }
            s.accept(speech)
            assertTrue(began.await(2, TimeUnit.SECONDS))
            s.observeSpeech(false)
            val ending = ByteArray(28800) { 3 }
            s.accept(ending, false)
            unblock.countDown()
            assertEquals("complete phrase", s.finish())
            assertEquals(2, clips.size)
            assertArrayEquals(ByteArray(38400) { 1 } + speech + ending, clips.last())
        } finally { unblock.countDown(); s.close() }
    }
    @Test fun changedPrefixIsNotCommitted() {
        assertEquals("Can you", AsyncWhisperSession.agreeingPrefix("Can you open", "Can you tell me"))
        assertEquals("", AsyncWhisperSession.agreeingPrefix("Radio says", "What time is it?"))
    }
}
