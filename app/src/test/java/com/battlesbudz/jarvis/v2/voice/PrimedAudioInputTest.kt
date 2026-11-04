package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Route B (Option B) priming owner: the prefix is prefilled during
 * "capture", the sealed rows are chunk-fed at finalization, and every
 * failure before user-visible output degrades to the sealed Conversation
 * route with the session already closed.
 */
class PrimedAudioInputTest {
    private class FakeSession(
        var failAppend: Boolean = false,
        var failAudio: Boolean = false,
        var failAudioChunk: Boolean = false,
        var decodeFailure: Throwable? = null,
        var emitBeforeFailure: String? = null
    ) : VoicePrefillSession {
        val appends = mutableListOf<String>()
        val audioFeeds = mutableListOf<Triple<Int, Int, Int>>() // rows, tokenCount, width
        data class ChunkFeed(val values: FloatArray, val tokenCount: Int, val width: Int,
                             val begin: Boolean, val end: Boolean)
        val chunkFeeds = mutableListOf<ChunkFeed>()
        var decodes = 0
        var closed = false

        override fun append(text: String) {
            if (failAppend) error("append failed")
            appends += text
        }

        override fun appendImage(imageBytes: ByteArray) {
            error("primed audio turns never carry an image")
        }

        override fun appendAudio(rows: FloatArray, tokenCount: Int, embeddingWidth: Int) {
            if (failAudio) error("audio rejected")
            audioFeeds += Triple(rows.size, tokenCount, embeddingWidth)
        }

        override fun appendAudioChunk(rows: FloatArray, tokenCount: Int, embeddingWidth: Int,
                                      includeBeginMarker: Boolean, includeEndMarker: Boolean) {
            if (failAudioChunk) error("audio chunk rejected")
            chunkFeeds += ChunkFeed(rows.copyOf(), tokenCount, embeddingWidth,
                includeBeginMarker, includeEndMarker)
        }

        override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
            decodes++
            decodeFailure?.let { failure ->
                emitBeforeFailure?.let(onToken)
                throw failure
            }
            onToken("answer")
            return GenerationResult("answer", 7, null)
        }

        override fun close() {
            closed = true
        }
    }

    private fun sealed(rows: Int = 200, width: Int = 4): Content.SealedAudioEmbeddings =
        Content.SealedAudioEmbeddings.fromSealedAudio(
            FloatArray(rows * width), pcmSampleCount = 128_000, embeddingWidth = width,
            sealToken = "primed-test", producerSha256 = "a".repeat(64)
        )

    @Test fun primedTurnFeedsRemainderAndAudioThenDecodesOnTheSession() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        var fallbackCalls = 0
        val tokens = mutableListOf<String>()
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealed(), { tokens += it }) {
            fallbackCalls++
            GenerationResult("sealed", 9, null)
        }
        assertEquals("answer", result.text)
        assertEquals(listOf("PREFIX ", "remainder"), fake.appends)
        assertEquals(listOf(Triple(800, 200, 4)), fake.audioFeeds)
        assertEquals(listOf("answer"), tokens)
        assertEquals(0, fallbackCalls)
        assertTrue(owner.usedPriming)
        assertEquals(null, owner.fallbackReason)
        assertTrue(fake.closed)
    }

    @Test fun promptPrefixChangeFallsBackWithTheSessionAlreadyClosed() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        val result = owner.answerWithSealedFallback("OTHER prompt", sealed(), {}) {
            // The sealed route creates a native Conversation: the primed
            // Session must already be released or that admission fails.
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertFalse(owner.usedPriming)
        assertEquals("prompt_prefix_changed", owner.fallbackReason)
        assertEquals(0, fake.decodes)
    }

    @Test fun primingFailureFallsBackToTheSealedRoute() = runBlocking {
        val fake = FakeSession(failAppend = true)
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealed(), {}) {
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("priming_failed", owner.fallbackReason)
        assertTrue(fake.closed)
    }

    @Test fun audioRejectionFallsBackBeforeAnyOutput() = runBlocking {
        val fake = FakeSession(failAudio = true)
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        val tokens = mutableListOf<String>()
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealed(), { tokens += it }) {
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("session_generation_failed", owner.fallbackReason)
        assertTrue(tokens.isEmpty())
    }

    @Test fun decodeFailureAfterOutputNeverFallsBack() = runBlocking {
        val fake = FakeSession(decodeFailure = IllegalStateException("decode died"), emitBeforeFailure = "partial")
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        var fallbackCalls = 0
        try {
            owner.answerWithSealedFallback("PREFIX remainder", sealed(), {}) {
                fallbackCalls++
                GenerationResult("sealed", 9, null)
            }
            fail("a failure after visible output must propagate, not double-answer")
        } catch (expected: IllegalStateException) {
            assertEquals("decode died", expected.message)
        }
        assertEquals(0, fallbackCalls)
        assertTrue(fake.closed)
    }

    @Test fun neverPrimedOwnerFallsBack() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealed(), {}) {
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("not_primed", owner.fallbackReason)
    }

    // Option A: provisional rows offered during "capture" are fed in
    // 72-row chunks; the seal verification compares them step for step
    // against the sealed rows of the same encode pass.

    /** Sealed content whose rows are the global-index pattern the offers slice. */
    private fun sealedPattern(rows: Int = 200, width: Int = 4): Content.SealedAudioEmbeddings =
        Content.SealedAudioEmbeddings.fromSealedAudio(
            FloatArray(rows * width) { it.toFloat() }, pcmSampleCount = 128_000, embeddingWidth = width,
            sealToken = "primed-provisional-test", producerSha256 = "a".repeat(64)
        )

    private fun offer(firstRow: Int, totalRows: Int, width: Int = 4, corruptRow: Int = -1) =
        RetainedPcmEncoderWorker.ProvisionalRows(firstRow, totalRows,
            FloatArray((totalRows - firstRow) * width) { (firstRow * width + it).toFloat() }.also {
                if (corruptRow in firstRow until totalRows) it[(corruptRow - firstRow) * width] += 1f
            })

    @Test fun provisionalFeedVerifiesAtTheSealAndDecodesOnTheSession() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        // 96 + 96 + 8 rows offered: the feeder emits two 72-row chunks
        // during capture and holds the rest back for the finalization tail.
        owner.offerProvisionalRows(offer(0, 96))
        owner.offerProvisionalRows(offer(96, 192))
        owner.offerProvisionalRows(offer(192, 200))
        var fallbackCalls = 0
        val result = owner.answerWithSealedFallback("PREFIX ", sealedPattern(), {}) {
            fallbackCalls++
            GenerationResult("sealed", 9, null)
        }
        assertEquals("answer", result.text)
        assertEquals(0, fallbackCalls)
        assertTrue(owner.usedPriming)
        assertEquals(null, owner.fallbackReason)
        assertEquals(200, owner.provisionalRowsOffered)
        assertEquals(144, owner.provisionalRowsFed)
        assertEquals(0.0, owner.provisionalSealMaxAbsDiff!!, 0.0)
        // Two provisional chunks (begin on the first only), then the
        // 56-row finalization tail carrying the end marker. The one-shot
        // appendAudio is never used on a provisionally fed turn.
        assertTrue(fake.audioFeeds.isEmpty())
        assertEquals(listOf(72, 72, 56), fake.chunkFeeds.map { it.tokenCount })
        assertEquals(listOf(true, false, false), fake.chunkFeeds.map { it.begin })
        assertEquals(listOf(false, false, true), fake.chunkFeeds.map { it.end })
        val fedValues = FloatArray(200 * 4)
        var at = 0
        for (chunk in fake.chunkFeeds) { chunk.values.copyInto(fedValues, at); at += chunk.values.size }
        assertTrue(fedValues.contentEquals(FloatArray(200 * 4) { it.toFloat() }))
        assertEquals(1, fake.decodes)
    }

    @Test fun corruptedProvisionalRowDiscardsThePrimedSession() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 96, corruptRow = 80))
        owner.offerProvisionalRows(offer(96, 192))
        owner.offerProvisionalRows(offer(192, 200))
        val result = owner.answerWithSealedFallback("PREFIX ", sealedPattern(), {}) {
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertFalse(owner.usedPriming)
        assertEquals("provisional_rows_mismatch", owner.fallbackReason)
        assertEquals(1.0, owner.provisionalSealMaxAbsDiff!!, 0.0)
        assertEquals(0, fake.decodes)
    }

    @Test fun extendedPromptAfterProvisionalFeedDiscardsTheSession() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 96))
        owner.offerProvisionalRows(offer(96, 192))
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealedPattern(), {}) {
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("prompt_extended_after_audio", owner.fallbackReason)
        assertEquals(144, owner.provisionalRowsFed)
        assertEquals(0, fake.decodes)
        assertTrue(fake.audioFeeds.isEmpty())
    }

    @Test fun provisionalFeedFailureDiscardsTheSession() = runBlocking {
        val fake = FakeSession(failAudioChunk = true)
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 96))
        owner.offerProvisionalRows(offer(96, 192))
        val result = owner.answerWithSealedFallback("PREFIX ", sealedPattern(), {}) {
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("provisional_feed_failed", owner.fallbackReason)
        assertEquals(0, fake.decodes)
    }

    @Test fun sequenceBreakAfterRowsWereFedDiscardsTheSession() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 96))
        owner.offerProvisionalRows(offer(96, 192))
        // The encoder candidate restarted: the chain breaks at row 0 again.
        owner.offerProvisionalRows(offer(0, 12))
        val result = owner.answerWithSealedFallback("PREFIX ", sealedPattern(), {}) {
            assertTrue(fake.closed)
            GenerationResult("sealed", 9, null)
        }
        assertEquals("sealed", result.text)
        assertEquals("provisional_rows_out_of_sequence", owner.fallbackReason)
        assertEquals(144, owner.provisionalRowsFed)
        assertEquals(0, fake.decodes)
    }

    @Test fun sequenceBreakBeforeAnyFeedKeepsTheOptionBPath() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 24))
        owner.offerProvisionalRows(offer(0, 24))
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealedPattern(), {}) {
            GenerationResult("sealed", 9, null)
        }
        // Nothing was ever fed, so the session holds only text: the
        // ordinary Option B finalization (remainder + one-shot audio)
        // still applies and the poisoned snapshot stream is just dropped.
        assertEquals("answer", result.text)
        assertTrue(owner.usedPriming)
        assertEquals(0, owner.provisionalRowsFed)
        assertEquals(listOf(Triple(800, 200, 4)), fake.audioFeeds)
        assertTrue(fake.chunkFeeds.isEmpty())
    }

    @Test fun offersBelowOneChunkKeepTheOptionBPathEntirely() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        owner.offerProvisionalRows(offer(0, 24))
        val result = owner.answerWithSealedFallback("PREFIX remainder", sealedPattern(), {}) {
            GenerationResult("sealed", 9, null)
        }
        assertEquals("answer", result.text)
        assertTrue(owner.usedPriming)
        assertEquals(24, owner.provisionalRowsOffered)
        assertEquals(0, owner.provisionalRowsFed)
        assertEquals(null, owner.provisionalSealMaxAbsDiff)
        assertEquals(listOf(Triple(800, 200, 4)), fake.audioFeeds)
    }

    @Test fun closeAfterConsumptionIsAnIdempotentNoOp() = runBlocking {
        val fake = FakeSession()
        val owner = PrimedAudioInput(this, "PREFIX ", { fake })
        owner.prime()
        // Consuming the owner joins priming and closes the session.
        owner.answerWithSealedFallback("PREFIX remainder", sealed(), {}) {
            GenerationResult("sealed", 9, null)
        }
        assertTrue(fake.closed)
        owner.close()
        owner.close()
        assertTrue(fake.closed)
    }
}
