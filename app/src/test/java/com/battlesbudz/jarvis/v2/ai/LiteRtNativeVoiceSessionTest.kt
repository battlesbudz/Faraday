package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.PreprocessedAudio
import com.google.ai.edge.litertlm.PreprocessedImage
import com.google.ai.edge.litertlm.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bridge boundary for snapshot images: preprocessing must be invoked,
 * in order, before the preprocessed payload is submitted — against fakes at
 * the seam, because the native library cannot load in JVM tests (that gap
 * is how the raw-bytes submission shipped broken in build 1624).
 */
class LiteRtNativeVoiceSessionTest {
    @Test fun imageIsPreprocessedBeforeItIsSubmitted() {
        val order = mutableListOf<String>()
        val payload = PreprocessedImage(0L)
        var submitted: PreprocessedImage? = null
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessImage = { bytes ->
                order += "preprocess"
                assertTrue(bytes.contentEquals(byteArrayOf(4, 2)))
                payload
            },
            submitPreprocessed = { image ->
                order += "submit"
                submitted = image
            })
        native.runPrefillImage(byteArrayOf(4, 2))
        assertEquals(listOf("preprocess", "submit"), order)
        assertSame(payload, submitted)
    }

    @Test fun preprocessFailureIsTypedAndNothingIsSubmitted() {
        var submissions = 0
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessImage = { throw IllegalStateException("not a jpeg") },
            submitPreprocessed = { submissions++ })
        try {
            native.runPrefillImage(byteArrayOf(1))
            fail("expected the submission failure to be typed")
        } catch (expected: SnapshotImageSubmissionException) {
            assertEquals("not a jpeg", expected.cause?.message)
        }
        assertEquals(0, submissions)
    }

    @Test fun submitFailureIsTyped() {
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessImage = { PreprocessedImage(0L) },
            submitPreprocessed = { throw IllegalStateException("native rejected the tensor") })
        try {
            native.runPrefillImage(byteArrayOf(1))
            fail("expected the submission failure to be typed")
        } catch (expected: SnapshotImageSubmissionException) {
            assertEquals("native rejected the tensor", expected.cause?.message)
        }
    }

    @Test fun audioChunkIsPreprocessedBeforeItIsSubmitted() {
        val order = mutableListOf<String>()
        val payload = PreprocessedAudio(0L)
        var submitted: PreprocessedAudio? = null
        var markers: Pair<Boolean, Boolean>? = null
        val rows = floatArrayOf(1f, 2f, 3f, 4f)
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessAudio = { chunkRows, tokenCount, embeddingWidth, begin, end ->
                order += "preprocess"
                assertTrue(chunkRows.contentEquals(rows))
                assertEquals(2, tokenCount)
                assertEquals(2, embeddingWidth)
                markers = begin to end
                payload
            },
            submitPreprocessedAudio = { audio ->
                order += "submit"
                submitted = audio
            })
        native.runPrefillAudio(rows, 2, 2, includeBeginMarker = true, includeEndMarker = false)
        assertEquals(listOf("preprocess", "submit"), order)
        assertSame(payload, submitted)
        assertEquals(true to false, markers)
    }

    @Test fun audioPreprocessFailureIsTypedAsSubmissionFailure() {
        val failure = IllegalStateException("preprocess unavailable")
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessAudio = { _, _, _, _, _ -> throw failure },
            submitPreprocessedAudio = { fail("a failed preprocess must submit nothing") })
        try {
            native.runPrefillAudio(FloatArray(4), 2, 2, includeBeginMarker = true, includeEndMarker = true)
            fail("audio prefill must report its failure")
        } catch (error: AudioPrefillSubmissionException) {
            assertSame(failure, error.cause)
        }
    }

    @Test fun audioSubmitFailureIsTypedAsSubmissionFailure() {
        val failure = IllegalStateException("prefill rejected")
        val native = LiteRtNativeVoiceSession(Session(0L),
            preprocessAudio = { _, _, _, _, _ -> PreprocessedAudio(0L) },
            submitPreprocessedAudio = { throw failure })
        try {
            native.runPrefillAudio(FloatArray(4), 2, 2, includeBeginMarker = false, includeEndMarker = true)
            fail("audio prefill must report its failure")
        } catch (error: AudioPrefillSubmissionException) {
            assertSame(failure, error.cause)
        }
    }
}
