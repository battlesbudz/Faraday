package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.ai.fakeGenerationScript
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Consumer proof for the fake-engine double (reliability epic item 8,
 * pattern 2; the harness item 5 / M7 builds on). These interleavings were
 * previously untestable: the ad-hoc backends in this package either
 * answered immediately or threw at entry, so no test could hold a
 * generation mid-stream while another turn ran, cancel a stream that had
 * already emitted tokens, or fail a stream after a partial reply and then
 * drive the next turn on the same engine. [FakeModelEngine]'s gates make
 * all three deterministic on the JVM's single test thread: a held turn
 * suspends, never blocks, so the interleaving below is exact, not timed.
 *
 * Assertions are about what the fake observed and what each turn's draft
 * contains. They pin the CURRENT ConversationGeneration behavior on these
 * interleavings (M6 changes no production behavior); M7's turn guard is
 * what will change the send-while-generating semantics, and its tests
 * start from this harness.
 */
class FakeEngineInterleavingTest {

    private suspend fun runTurn(backend: FakeModelEngine, promptText: String): ConversationDraft {
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation(promptText, emptyList())
        val callbacks = ConversationCallbacks({}, {})
        val reply = ConversationReply("generation", PipelineBenchmarkCapture("generation", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, memory, { it() }, {}, {}, {}, {}, { _, _, _ -> })
        val shortTerm = ShortTermConversationContext()
        val builder = ConversationPromptBuilder(shortTerm)
        val state = object : ConversationSessionState {
            override var engine: com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine? = null
            override var hasContext = false
            override var characters = 0
        }
        val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false, supportsTools = true)
        val session = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
            "unused", shortTerm, {})
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? = error("Generation must not fetch")
            override fun isInsufficientAnswer(answer: String) = false
        }
        val actions = ConversationActions({ error("No Android executor") }, { _, _ -> error("No action admission") },
            { _, _, _, _ -> error("No action dispatch") }, {}, "conversation", { _, _, _ -> })
        val generation = ConversationGeneration(session, references, builder, { error("No attachment") }, diagnostics)
        return generation.generate(input, 10_000,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
            ConversationPrompt(builder, false, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
    }

    @Test fun secondTurnCompletesWhileFirstTurnIsHeldMidStream() = runBlocking {
        val fake = FakeModelEngine()
        val gate = fake.newGate()
        fake.enqueue(fakeGenerationScript {
            emit("Alpha partial. ")
            holdAt(gate)
            emit("Alpha rest.")
        })
        fake.enqueueReply("Bravo answer.")

        val turnA = async { runTurn(fake, "first question") }
        withTimeout(5_000) { gate.entered.await() }
        // Turn A is in flight, parked after its first token chunk.
        assertTrue(turnA.isActive)
        assertEquals(1, fake.calls.size)
        assertEquals(listOf("Alpha partial. "), fake.emittedTokens)

        // A second turn on the same engine runs to completion while the
        // first is still held: its script, tokens and draft are its own.
        val draftB = runTurn(fake, "second question")
        assertEquals("Bravo answer.", draftB.generated.text)
        assertTrue(turnA.isActive)
        assertEquals(listOf("Alpha partial. ", "Bravo answer."), fake.emittedTokens)

        // Releasing the first turn lets it finish with its own full text,
        // and the fake's record shows the exact interleaving: A's prompt
        // and partial token, then all of B, then the rest of A.
        gate.release()
        val draftA = withTimeout(5_000) { turnA.await() }
        assertEquals("Alpha partial. Alpha rest.", draftA.generated.text)
        assertEquals(listOf("Alpha partial. ", "Bravo answer.", "Alpha rest."), fake.emittedTokens)
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[0].prompt.contains("first question"))
        assertTrue(fake.calls[1].prompt.contains("second question"))
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls[0].outcome)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls[1].outcome)
        assertEquals(listOf("Alpha partial. ", "Alpha rest."), fake.calls[0].emitted)
        assertEquals(listOf("Bravo answer."), fake.calls[1].emitted)
    }

    @Test fun cancellingAHeldStreamLeavesTheNextTurnClean() = runBlocking {
        val fake = FakeModelEngine()
        val gate = fake.newGate()
        fake.enqueue(fakeGenerationScript {
            emit("Partial ")
            hangOn(gate)
            emit("never delivered")
        })
        fake.enqueueReply("Recovered answer.")

        val turn = async { runTurn(fake, "cancel me") }
        withTimeout(5_000) { gate.entered.await() }
        assertEquals(listOf("Partial "), fake.emittedTokens)
        turn.cancelAndJoin()
        assertTrue(turn.isCancelled)
        // The cancellation reached the engine mid-stream: the held call is
        // recorded cancelled and its remaining tokens never emitted.
        assertEquals(FakeModelEngine.Outcome.CANCELLED, fake.calls[0].outcome)
        assertEquals(listOf("Partial "), fake.emittedTokens)

        // The next turn on the same engine is unaffected by the cancelled
        // stream: it consumes the next script and completes normally.
        val draft = runTurn(fake, "next question")
        assertEquals("Recovered answer.", draft.generated.text)
        assertEquals(2, fake.calls.size)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls[1].outcome)
        assertTrue(fake.calls[1].prompt.contains("next question"))
    }

    @Test fun midStreamFailurePropagatesAndTheNextTurnRecovers() = runBlocking {
        val fake = FakeModelEngine()
        val boom = RuntimeException("native engine exploded")
        fake.enqueueFailure(boom, "Partial ")
        fake.enqueueReply("Recovered answer.")

        try {
            runTurn(fake, "fail mid stream")
            fail("a non-parse mid-stream failure must propagate")
        } catch (error: RuntimeException) {
            assertSame(boom, error)
        }
        // The partial token streamed before the failure, exactly as a
        // native decode crash presents to the turn.
        assertEquals(listOf("Partial "), fake.calls[0].emitted)
        assertEquals(FakeModelEngine.Outcome.FAILED, fake.calls[0].outcome)

        val draft = runTurn(fake, "after the failure")
        assertEquals("Recovered answer.", draft.generated.text)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, fake.calls[1].outcome)
    }
}
