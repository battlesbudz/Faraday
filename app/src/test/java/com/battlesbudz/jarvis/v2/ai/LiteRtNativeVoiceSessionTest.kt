package com.battlesbudz.jarvis.v2.ai

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
}
