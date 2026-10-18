package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GemmaSessionText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FirstPiperClauseTest {
    private val opening = "The first audio passage can start now"

    private class Pipeline(previous: String? = null) {
        val piper = PiperTextStream()
        val passages = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("Explain the next step", previous) {
            piper.append(VoiceRepetitionGuard.speechReady(it))
            while (true) passages += piper.take() ?: break
        }
        fun finish(): String {
            val text = guard.finish()
            while (true) passages += piper.take(final = true) ?: break
            return text
        }
    }

    @Test fun firstMeaningfulClausePassesBothGatesBeforeSentenceIsComplete() {
        for (punctuation in listOf(',', ';', ':')) {
            val pipeline = Pipeline()
            pipeline.guard.accept("$opening$punctuation")
            assertTrue(pipeline.passages.isEmpty()) // The punctuation itself may be token-split.
            pipeline.guard.accept(" ")
            assertEquals(listOf("$opening$punctuation"), pipeline.passages)
            assertEquals(0, pipeline.guard.acceptedSentences)
            pipeline.guard.accept("and the remaining explanation follows later.")
            assertEquals(1, pipeline.passages.size) // Subsequent speech retains existing batching.
            assertEquals(1, pipeline.guard.acceptedSentences)
            assertEquals("$opening$punctuation and the remaining explanation follows later.", pipeline.finish())
            assertEquals(pipeline.guard.text, pipeline.passages.joinToString(" "))
        }
    }

    @Test fun allCallbackSplitsKeepEveryWordAndLaterBatchingIntact() {
        val tail = (1..9).joinToString(" ") { "A separate detail number $it offers useful information about the next stage." }
        val answer = "$opening, and the rest of this sentence continues here. $tail"
        for (split in listOf(1, 2, 3, 7, 31, answer.length)) {
            val pipeline = Pipeline()
            answer.chunked(split).forEach(pipeline.guard::accept)
            assertEquals(answer, pipeline.finish())
            assertEquals(answer, pipeline.passages.joinToString(" "))
            assertEquals("$opening,", pipeline.passages.first())
            assertTrue(pipeline.passages[1].length in PiperTextStream.TARGET_CHARS..PiperTextStream.MAX_CHARS)
            assertTrue(pipeline.passages.all { it.length <= PiperTextStream.MAX_CHARS })
        }
    }

    @Test fun unfinishedDependentNegatedAndNonSpeechFragmentsWait() {
        val unsafe = listOf(
            "The first audio passage can start", // No grammatical boundary, however long it waits.
            "Although the first audio passage is ready, ",
            "If the first audio passage is ready, ",
            "The first audio passage is not ready, ",
            "The first audio passage cannot start yet, ",
            "The first audio passage isn't ready yet, ",
            "The first audio passage can start with, ",
            "The first audio passage is, ",
            "The passage which the engine can prepare, ",
            "The distant stars above the quiet lake, ",
            "The proposed solution currently being evaluated, ",
            "The recently published notes about the feature, ",
            "The first audio passage (which is ready, ",
            "<tool_call>The first audio passage is ready, ",
            "tool_call: The first audio passage is ready, ",
            "Yes, "
        )
        for (text in unsafe) {
            val pipeline = Pipeline()
            text.chunked(2).forEach(pipeline.guard::accept)
            assertTrue(text, pipeline.passages.isEmpty())
            val direct = PiperTextStream(nowMs = { Long.MAX_VALUE })
            direct.append(text)
            assertNull(text, direct.take())
            assertNull(direct.openingWaitMs())
        }
    }

    @Test fun sharedBoundariesPreserveAbbreviationsInitialsAndSplitDecimal() {
        val pipeline = Pipeline()
        "Dr. A. Smith measured 3.".chunked(1).forEach(pipeline.guard::accept)
        assertTrue(pipeline.passages.isEmpty())
        "14 volts at the sensor, ".chunked(1).forEach(pipeline.guard::accept)
        assertEquals(listOf("Dr. A. Smith measured 3.14 volts at the sensor,"), pipeline.passages)
        pipeline.guard.accept("and confirmed the measurement.")
        assertEquals("Dr. A. Smith measured 3.14 volts at the sensor, and confirmed the measurement.", pipeline.finish())
        assertEquals(pipeline.guard.text, pipeline.passages.joinToString(" "))
    }

    @Test fun rejectedOpeningSuppressesItsWholeSentenceButKeepsFirstSlotAvailable() {
        val rejected = "The stale answer is already available here,"
        for (rejectByPolicy in listOf(false, true)) {
            val pipeline = Pipeline(if (rejectByPolicy) null else "$rejected with outdated facts.")
            if (rejectByPolicy) pipeline.guard.isPublishable = { !it.startsWith("The stale answer") }
            pipeline.guard.accept("$rejected ")
            assertTrue(pipeline.passages.isEmpty())
            pipeline.guard.accept("with a suffix which must stay suppressed. ")
            pipeline.guard.accept("$opening, ")
            assertEquals(listOf("$opening,"), pipeline.passages)
            assertEquals(0, pipeline.guard.acceptedSentences)
            pipeline.guard.accept("and a new explanation follows.")
            assertEquals("$opening, and a new explanation follows.", pipeline.finish())
        }
    }

    @Test fun completedOpeningSentenceStillRejectsRepeatedOutput() {
        val answer = "$opening, and a useful explanation follows."
        val pipeline = Pipeline()
        pipeline.guard.accept("$answer $answer")
        assertEquals(answer, pipeline.finish())
        assertEquals(1, pipeline.guard.suppressedSentences)
        assertEquals(answer, pipeline.passages.joinToString(" "))
    }

    @Test fun hiddenChannelsAndSplitToolMarkersNeverReachPiper() {
        for (split in listOf(1, 2, 7, 150)) {
            val pipeline = Pipeline()
            val filtered = GemmaSessionText(pipeline.guard::accept)
            val raw = "<|channel>The hidden thought is ready for speech, <channel|>" +
                "$opening, and the answer is complete.<|tool_call>function_call: open_app."
            raw.chunked(split).forEach(filtered::accept)
            filtered.finish()
            assertEquals("$opening, and the answer is complete.", pipeline.finish())
            assertEquals(pipeline.guard.text, pipeline.passages.joinToString(" "))
        }
    }

    @Test fun fullReplyAndFormattingPreservationKeepTheirExistingContracts() {
        val full = PiperTextStream(waitForEnd = true)
        full.append("$opening, and its sentence is still growing")
        assertNull(full.take())
        assertEquals("$opening, and its sentence is still growing", full.take(final = true))
        val emitted = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("Show prose", null, emitted::add)
        guard.preserveFormatting = true
        guard.accept("$opening, ")
        assertTrue(emitted.isEmpty())
        guard.accept("and its sentence is now complete.")
        assertEquals(listOf("$opening, and its sentence is now complete."), emitted)
    }

    @Test fun repairStillCountsSentencesRatherThanEarlyClauseCallbacks() = runBlocking {
        val pipeline = Pipeline()
        val outcome = VoiceRepetitionRepair.run(pipeline.guard) { emit ->
            emit("$opening, ")
            assertEquals(0, pipeline.guard.acceptedSentences)
            emit("and its sentence is now complete.")
            assertEquals(1, pipeline.guard.acceptedSentences)
            emit("A second sentence completes the repair. An extra sentence must not escape.")
            error("Repair must stop at the original two-sentence budget")
        }
        assertEquals("sentence_or_character_limit", outcome.reason)
        assertEquals(2, pipeline.guard.acceptedSentences)
        assertEquals("$opening, and its sentence is now complete. A second sentence completes the repair.", pipeline.finish())
    }

    @Test fun bargeInAfterFirstClauseDiscardsSuffixAndFencesStaleCallbacks() = runBlocking {
        val pipeline = Pipeline()
        val released = CompletableDeferred<Unit>()
        var callback: ((String) -> Unit)? = null
        var nativeOwnerCleaned = false
        val turn = launch {
            VoiceRepetitionRepair.run(pipeline.guard) { emit ->
                callback = emit
                try {
                    emit("$opening, and this unfinished suffix")
                    released.complete(Unit)
                    awaitCancellation()
                } finally { nativeOwnerCleaned = true }
            }
        }
        released.await()
        assertEquals(listOf("$opening,"), pipeline.passages)
        turn.cancelAndJoin()
        assertTrue(nativeOwnerCleaned)
        callback!!.invoke("A stale answer is ready for speech, and must never escape.")
        assertEquals("$opening,", pipeline.guard.finish())
        assertEquals(listOf("$opening,"), pipeline.passages)
    }

    @Test fun discardedOpeningTailCannotMakeItsAlreadyPublishedPrefixRepeatable() {
        val pipeline = Pipeline()
        pipeline.guard.accept("$opening, and an unfinished tail")
        pipeline.guard.discardPending()
        pipeline.guard.beginRepair()
        pipeline.guard.accept("$opening, but the stale repair repeats that opening.")
        assertEquals("$opening,", pipeline.guard.finish())
        assertEquals(1, pipeline.guard.suppressedSentences)
        assertEquals(listOf("$opening,"), pipeline.passages)
    }

    @Test fun cancellationFromCompletedSentenceCallbackKeepsPrefixCleanupSafe() {
        val guard = VoiceRepetitionGuard("Explain", null) { output ->
            if (output.endsWith("complete.")) throw CancellationException("barge_in")
        }
        guard.accept("$opening, ")
        try {
            guard.accept("and the sentence is complete.")
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("barge_in", expected.message)
        } finally { guard.discardPending() }
        assertEquals("$opening, and the sentence is complete.", guard.finish())
    }

    @Test fun explicitStopStillPropagatesAfterAnOpeningClause() = runBlocking {
        val pipeline = Pipeline()
        try {
            VoiceRepetitionRepair.run(pipeline.guard) { emit ->
                emit("$opening, ")
                throw CancellationException("barge_in")
            }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("barge_in", expected.message)
        }
        assertEquals("$opening,", pipeline.guard.text)
    }

    @Test fun firstTextIsNotReportedAsFirstAudioAndEndedTurnsRejectAudioCallbacks() {
        var clock = 100L
        val trace = VoiceTurnTrace("opening-clause") { clock }
        val piper = PiperTextStream()
        val guard = VoiceRepetitionGuard("Explain", null) {
            trace.mark(VoiceTurnTrace.Stage.FIRST_REPLY_TEXT)
            piper.append(VoiceRepetitionGuard.speechReady(it))
        }
        clock += 15
        guard.accept("$opening, ")
        assertEquals("$opening,", piper.take())
        assertFalse(trace.snapshot().containsKey("first_reply_audio"))
        // A simulated rendered-PCM event remains independent; this is no acoustic/latency measurement.
        clock += 40
        trace.mark(VoiceTurnTrace.Stage.FIRST_REPLY_AUDIO)
        assertEquals(40L, trace.duration(VoiceTurnTrace.Stage.FIRST_REPLY_TEXT, VoiceTurnTrace.Stage.FIRST_REPLY_AUDIO))
        trace.mark(VoiceTurnTrace.Stage.TURN_FINISHED)
        clock += 50
        trace.mark(VoiceTurnTrace.Stage.FIRST_REPLY_AUDIO)
        assertEquals(55L, trace.snapshot()["first_reply_audio"])
    }
}
