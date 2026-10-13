package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class LazyStreamingTranscriberMetricsTest {
    @Test fun readingMetricsDoesNotLoadModelAndMeasuredDelegateWorkPassesThrough() {
        var loads = 0
        var work = AsrRecognitionWorkMetrics("fixture_measured_api_wall")
        val lazy = LazyStreamingTranscriber {
            loads++
            object : StreamingTranscriber {
                override val recognitionWorkMetrics get() = work
                override fun accept(pcm: ByteArray): String {
                    work = work.copy(feedWorkNs = 5_000_000, invocations = 1, submittedAudioSamples = pcm.size.toLong() / 2)
                    return "original words"
                }
                override fun finish() = "original words"
                override fun close() {}
            }
        }
        assertNull(lazy.recognitionWorkMetrics)
        assertEquals(0, loads)
        lazy.accept(ByteArray(3200))
        assertEquals(1, loads)
        assertEquals(work, lazy.recognitionWorkMetrics)
        assertEquals(5L, lazy.recognitionWorkMetrics?.workMs)
        assertEquals(100L, lazy.recognitionWorkMetrics?.submittedAudioMs)
        lazy.finish()
        assertEquals(work, lazy.recognitionWorkMetrics)
        lazy.close()
        assertNull(lazy.recognitionWorkMetrics)
        assertEquals(1, loads)
    }

    @Test fun UninstrumentedDelegateRemainsUnknown() {
        val lazy = LazyStreamingTranscriber {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = "words"
                override fun finish() = "words"
                override fun close() {}
            }
        }
        lazy.accept(ByteArray(3200))
        assertNull(lazy.recognitionWorkMetrics)
        lazy.close()
    }
}
