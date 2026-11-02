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
        var decodeFailure: Throwable? = null,
        var emitBeforeFailure: String? = null
    ) : VoicePrefillSession {
        val appends = mutableListOf<String>()
        val audioFeeds = mutableListOf<Triple<Int, Int, Int>>() // rows, tokenCount, width
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
            FloatArray(rows * width), pcmSampleCount = 3_200, embeddingWidth = width,
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
