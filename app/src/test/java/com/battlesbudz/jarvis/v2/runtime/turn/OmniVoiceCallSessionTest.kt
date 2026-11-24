package com.battlesbudz.jarvis.v2.runtime.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniVoiceCallSessionTest {
    @Test fun oneConversationContinuesAcrossSequentialAudioTurns() = runBlocking {
        val fixture = Fixture()
        val session = fixture.session()
        session.start()
        assertEquals(1, fixture.engine.resets)

        assertEquals("answer-1", session.runTurn().text)
        assertEquals("answer-2", session.runTurn().text)
        assertEquals(2, fixture.engine.audioTurns)
        assertEquals(1, fixture.engine.resets)
        assertEquals(2, fixture.output.finishes)
        assertEquals(listOf("question 1", "answer-1", "question 2", "answer-2"),
            session.transcriptSnapshot().map { it.text })
        val turns = fixture.events.filterIsInstance<OmniVoiceCallSession.Event.Turn>()
        assertEquals(2, turns.size)
        assertEquals(4, turns.first().supplyGaps)
        assertEquals(1_000L, turns.first().totalMs)
        session.closeCall()
        assertEquals(1, fixture.engine.endCalls)
    }

    @Test fun rolloverOccursAtTurnBoundaryAndDropsOldestCompletePairsToFit() = runBlocking {
        val fixture = Fixture().apply { engine.estimateLimit = 2 }
        val session = fixture.session()
        session.start()
        session.runTurn()
        session.runTurn()
        fixture.engine.fakeContextPositions = OmniVoiceCallSession.ROLLOVER_AT
        session.runTurn()

        val rollover = fixture.events.filterIsInstance<OmniVoiceCallSession.Event.ContextRollover>().single()
        assertEquals(1, rollover.droppedPairs)
        assertEquals(OmniVoiceCallSession.ROLLOVER_AT, rollover.oldPositions)
        assertTrue(rollover.prefilledPositions <= OmniVoiceCallSession.MAX_CONTEXT_POSITIONS)
        assertEquals(2, fixture.engine.resets)
        assertEquals(listOf("question 2", "answer-2"), fixture.engine.prefilled.map { it.text })
        assertEquals("question 2", session.transcriptSnapshot().first().text)
        session.closeCall()
    }

    @Test fun hangupDuringAnswerStopsAndFlushesWithoutPublishingTurn() = runBlocking {
        val fixture = Fixture()
        fixture.engine.onAnswer = { fixture.active = false }
        val session = fixture.session()
        session.start()

        try {
            session.runTurn()
            throw AssertionError("Expected call cancellation")
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue(fixture.output.flushes > 0)
        assertEquals(0, fixture.output.finishes)
        assertTrue(session.transcriptSnapshot().isEmpty())
        session.closeCall()
    }

    @Test fun callLoopStartsOneConversationAndRunsTurnsUntilCallEnds() = runBlocking {
        val fixture = Fixture()
        val answers = mutableListOf<String>()
        val session = fixture.session()
        OmniVoiceCallLoop(session, { fixture.active }) { answer ->
            answers += answer.text
            if (answers.size == 2) fixture.active = false
        }.run()

        assertEquals(listOf("answer-1", "answer-2"), answers)
        assertEquals(1, fixture.engine.resets)
        assertEquals(1, fixture.engine.endCalls)
        assertEquals(2, fixture.engine.audioTurns)
    }

    private class Fixture {
        var active = true
        val events = mutableListOf<OmniVoiceCallSession.Event>()
        val engine = FakeEngine { active }
        val output = FakeOutput()
        private val capture = FakeCapture()

        fun session() = OmniVoiceCallSession(engine, capture, output, events::add,
            isCallActive = { active }, clockMs = { 2_000L })
    }

    private class FakeEngine(private val isActive: () -> Boolean) : OmniCallEngine {
        var fakeContextPositions = 0
        override val contextPositions: Int get() = fakeContextPositions
        var resets = 0
        var endCalls = 0
        var audioTurns = 0
        var estimateLimit = Int.MAX_VALUE
        var onAnswer: (() -> Unit)? = null
        var prefilled = emptyList<OmniCallMessage>()

        override suspend fun estimateTextPositions(transcript: List<OmniCallMessage>): Int =
            if (transcript.size > estimateLimit) 5000 else transcript.size

        override suspend fun resetConversation() {
            resets++
            fakeContextPositions = 0
        }

        override suspend fun endCall() { endCalls++ }

        override suspend fun prefillText(transcript: List<OmniCallMessage>) {
            prefilled = transcript.toList()
            fakeContextPositions = transcript.size
        }

        override suspend fun answerAudio(
            pcm16kMono: FloatArray,
            output: OmniCallSpeechOutput,
            shouldStop: () -> Boolean,
        ): OmniCallAnswer {
            onAnswer?.invoke()
            if (shouldStop() || !isActive()) throw CancellationException("hangup")
            audioTurns++
            fakeContextPositions += 20
            output.playChunk(floatArrayOf(0.1f))
            return OmniCallAnswer("answer-$audioTurns", mapOf("decoder" to 5L))
        }
    }

    private class FakeCapture : OmniTurnCapture {
        var turn = 0
        override suspend fun startCall() = Unit
        override suspend fun awaitTurn(): OmniCapturedTurn =
            OmniCapturedTurn(floatArrayOf(0.0f), 1000L + turn, "question ${++turn}")
        override fun close() = Unit
    }

    private class FakeOutput : OmniCallSpeechOutput {
        var flushes = 0
        var finishes = 0
        override val supplyGapsThisTurn: Int get() = 4
        override var firstSoundAtMonotonicMs: Long? = null
            private set
        override fun beginTurn() { firstSoundAtMonotonicMs = null }
        override suspend fun playChunk(pcm24kMono: FloatArray) {
            firstSoundAtMonotonicMs = System.nanoTime() / 1_000_000
        }
        override suspend fun finishTurn() { finishes++ }
        override fun stopAndFlush() { flushes++ }
        override fun close() = Unit
    }
}
