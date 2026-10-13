package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class LongUtteranceTest {
    @Test fun moonshineSizedSegmentsKeepThirtyAndSixtySecondsWithDeferredPartials() {
        for (seconds in listOf(30, 60)) {
            var loads = 0
            var closes = 0
            var bytes = 0
            val events = mutableListOf<String>()
            val recognizer = SegmentedTranscriber(create = {
                assertEquals(loads, closes)
                val index = loads++
                object : StreamingTranscriber {
                    override val segmentSoftLimitMs = 8_000L
                    override val segmentHardLimitMs = 12_000L
                    override fun accept(pcm: ByteArray): String = error("must retain the partial policy")
                    override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
                        assertFalse(allowPartial)
                        bytes += pcm.size
                        return ""
                    }
                    override fun finish() = "marker $index marker ${index + 1}"
                    override fun close() { closes++ }
                }
            }, log = events::add)
            repeat(seconds * 10) {
                recognizer.observeSpeech(true)
                recognizer.accept(ByteArray(3200), false)
            }
            val final = recognizer.finish()
            recognizer.close()
            assertNull(recognizer.issue)
            assertEquals(loads, closes)
            assertEquals(seconds * 32000 + (loads - 1) * 38400, bytes)
            assertEquals((0..loads).joinToString(" ") { "marker $it" }, final)
            assertTrue(events.all { "hardLimitMs=12000" in it })
            assertTrue(loads >= seconds / 12)
        }
    }

    @Test fun completeRecordingCanRecoverARecognizedQuestionAfterAnEmptyResumedSegment() {
        var loads = 0
        var recoveredBytes = 0
        val recognizer = SegmentedTranscriber(create = {
            val first = loads++ == 0
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = ""
                override fun finish() = if (first) "What is the capital of Idaho?" else ""
                override fun recover(pcm: ByteArray): String { recoveredBytes = pcm.size; return "What is the capital of Idaho?" }
                override fun close() {}
            }
        })
        recognizer.observeSpeech(true); recognizer.accept(ByteArray(3200)); recognizer.finish()
        recognizer.resumeAfterEndpoint()
        recognizer.observeSpeech(true); recognizer.accept(ByteArray(3200)); recognizer.finish()
        assertEquals("unrecognized_segment", recognizer.issue)
        assertEquals("What is the capital of Idaho?", recognizer.recover(ByteArray(6400)))
        assertEquals(6400, recoveredBytes)
        recognizer.close()
    }

    @Test fun missingFinalCorrectionCannotDispatchOnlyCommittedPrefix() {
        val text = UtteranceAccumulator()
        text.commit("Open Facebook", nextOverlaps = false)
        assertEquals("Open Facebook", text.finish("", speechExpected = true))
        assertEquals("unrecognized_segment", text.issue)
    }

    @Test fun thirtyAndSixtySecondRequestsKeepCorrectionsAcrossForcedSegments() {
        for (seconds in listOf(30, 60)) {
            var loads = 0
            var closes = 0
            var samples = 0
            val utterances = listOf("Open Facebook actually use", "actually use YouTube and set volume", "set volume to fifty percent")
            val recognizer = SegmentedTranscriber(create = {
                assertEquals(loads, closes) // No native overlap at rotation.
                val text = utterances[loads++]
                object : StreamingTranscriber {
                    override fun accept(pcm: ByteArray): String { samples += pcm.size; return text }
                    override fun finish() = text
                    override fun close() { closes++ }
                }
            })
            repeat(seconds * 10) {
                recognizer.observeSpeech(true)
                recognizer.accept(ByteArray(3200))
            }
            val final = recognizer.finish()
            assertNull(recognizer.issue)
            assertTrue(final.startsWith("Open Facebook actually use YouTube"))
            assertFalse(final.contains("actually use actually use"))
            if (seconds == 60) assertTrue(final.endsWith("set volume to fifty percent"))
            assertTrue(samples <= seconds * 32000 + 2 * 38400)
            recognizer.close(); recognizer.close()
            assertEquals(loads, closes)
        }
    }
    @Test fun unverifiedBoundaryCannotBecomeAnExecutablePartialRequest() {
        val text = UtteranceAccumulator()
        text.commit("Open Facebook instead of", nextOverlaps = true)
        text.finish("YouTube please")
        assertEquals("segment_boundary_uncertain", text.issue)
    }
    @Test fun quietBoundaryDoesNotDeduplicateIntentionalRepetition() {
        val text = UtteranceAccumulator()
        text.commit("Very very", nextOverlaps = false)
        assertEquals("Very very very quiet", text.finish("very quiet"))
        assertNull(text.issue)
    }
    @Test fun revisedPartialIsReplacedAndFinalNegationIsPreserved() {
        val text = UtteranceAccumulator()
        text.commit("I want to", nextOverlaps = true)
        assertEquals("I want to open Facebook", text.partial("want to open Facebook"))
        assertEquals("I want to cancel that instead", text.finish("want to cancel that instead"))
    }
    @Test fun capacityAndEmptySegmentsAreExplicitFailures() {
        val text = UtteranceAccumulator(20)
        text.commit("Open Facebook", false)
        text.finish("but actually open YouTube")
        assertEquals("transcript_capacity", text.issue)
        val empty = UtteranceAccumulator()
        empty.commit("", false)
        empty.finish("open settings")
        assertEquals("unrecognized_segment", empty.issue)
    }
    @Test fun speechAfterEndpointCanResumeWithoutFinalizingNativeStreamTwice() {
        var finishes = 0
        var loads = 0
        val recognizer = SegmentedTranscriber(create = {
            val text = if (loads++ == 0) "Open Facebook" else "actually open YouTube"
            object : StreamingTranscriber {
                private var sealed = false
                override fun accept(pcm: ByteArray): String { check(!sealed); return text }
                override fun finish(): String { check(!sealed); sealed = true; finishes++; return text }
                override fun close() {}
            }
        })
        recognizer.observeSpeech(true); recognizer.accept(ByteArray(3200))
        recognizer.finish()
        recognizer.resumeAfterEndpoint()
        recognizer.observeSpeech(true); recognizer.accept(ByteArray(3200))
        assertEquals("Open Facebook actually open YouTube", recognizer.finish())
        assertEquals(2, finishes)
        recognizer.close()
    }
    @Test fun closingAfterStopDoesNotFinalizeOrPublishAnotherSegment() {
        var finalized = false
        var closed = false
        val recognizer = SegmentedTranscriber(create = {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = "open"
                override fun finish(): String { finalized = true; return "open settings" }
                override fun close() { closed = true }
            }
        })
        recognizer.observeSpeech(true); recognizer.accept(ByteArray(3200))
        recognizer.close()
        assertFalse(finalized); assertTrue(closed)
    }
}
