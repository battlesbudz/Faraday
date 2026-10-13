package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.voice.*
import org.junit.Assert.*
import org.junit.Test

class TurnLatencyTest {
    private fun timing(id: String = "turn-a") = TurnLatency(id, 3000, 900, 400, 200,
        listOf(InferenceTiming.from("answer", GenerationResult("hello", 250, 13.6, 4, 1000))))

    @Test fun absentMeasurementsNeverPretendToBeZeroLatency() {
        val absent = InferenceTiming.from("tool", GenerationResult("", -1, null))
        assertNull(absent.firstTokenMs)
        assertNull(absent.totalMs)
        assertTrue(timing().copy(passes = listOf(absent)).summary().contains("TTFT —"))
        assertFalse(timing().copy(passes = listOf(absent)).summary().contains("TTFT 0.00"))
    }

    @Test fun timingsAndVoiceDetailsSurviveStorageAndEstimatesRemainLabelled() {
        val original = timing().copy(voice = "Paul", speechEndToReplyMs = 4500,
            textToPcmMs = 300, textToPlaybackMs = 500, supplyGapMs = 0)
        assertEquals(original, TurnLatency.read(original.json()))
        assertTrue(original.summary().contains("TTFT 0.25s"))
        assertTrue(original.summary().contains("~13.6 tok/s"))
        assertTrue(original.details().contains("4.50s"))
        assertNull(TurnLatency.read(null))
    }

    @Test fun toolRetryAndPreparedDraftAreNotConfusedWithOneLiveGeneration() {
        val original = timing().copy(passes = listOf(
            InferenceTiming("answer", 250, 4000, 100, 25.0, prepared = true),
            InferenceTiming("tool response", 300, 1000, 20, 20.0)))
        assertTrue(original.summary().contains("2 model passes"))
        assertTrue(original.details().contains("prepared before this request"))
        assertTrue(original.details().contains("Other reply processing: 1.40s"))
    }

    @Test fun delayedVoiceMeasurementOnlyUpdatesItsOwnReplyAndPersists() {
        val saved = mutableListOf<VoiceCallRecord>()
        val store = object : VoiceCallStore {
            override fun list() = saved.toList()
            override fun save(call: VoiceCallRecord) { saved.removeAll { it.id == call.id }; saved.add(call) }
            override fun delete(callId: String) { saved.removeAll { it.id == callId } }
        }
        val controller = VoiceSessionController(store)
        controller.beginCall()
        controller.appendTranscript("Jarvis", "First", latency = timing())
        controller.appendTranscript("You", "Next")
        controller.appendTranscript("Jarvis", "Second", latency = timing("turn-b"))
        controller.updateReplyLatency(timing().copy(voice = "Kokoro", textToPlaybackMs = 500))
        val transcript = controller.currentTranscript()
        assertEquals(500L, transcript.first().latency?.textToPlaybackMs)
        assertNull(transcript.last().latency?.textToPlaybackMs)
        val restored = SharedPreferencesVoiceCallStore.decode(SharedPreferencesVoiceCallStore.encode(saved))
        assertEquals(transcript, restored.single().transcript)
        controller.updateReplyLatency(timing("unknown"))
        assertEquals(transcript, controller.currentTranscript())
    }
}
