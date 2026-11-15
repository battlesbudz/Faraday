package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.FactualityVerifier
import com.battlesbudz.jarvis.v2.ai.FakeModelEngine
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.ai.fakeGenerationScript
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.work.TurnGuard
import com.battlesbudz.jarvis.v2.work.TurnOwnership
import com.battlesbudz.jarvis.v2.work.TurnSupersededException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M7 (reliability epic item 5, part 1): orchestrator interleavings on the
 * M6 fake-engine harness, now sharing ONE conversation session and ONE
 * [TurnGuard] across turns — the setup in which a superseded turn's
 * teardown could previously clear its replacement's state. Pins:
 *
 * - send-while-generating: the newer turn completes and writes shared
 *   session state; the older turn, released afterwards, aborts as
 *   superseded before any post-generation reset/retry and leaves the
 *   newer turn's state byte-identical.
 * - a superseded turn's mid-stream failure is dropped silently (the turn
 *   aborts with [TurnSupersededException], never with its own error),
 *   while the current turn's identical failure propagates unchanged.
 * - cancel-during-stream: cancelling a held turn does not disturb the
 *   next turn, which claims, completes and owns the guard afterwards.
 * - recovery-after-failure: recovery's reset-bearing stages check
 *   ownership at entry — a superseded draft aborts before a single
 *   reset, while the same draft under current ownership recovers.
 */
class TurnGuardInterleavingTest {

    private class Harness {
        val fake = FakeModelEngine()
        val guard = TurnGuard()
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val shortTerm = ShortTermConversationContext()
        val builder = ConversationPromptBuilder(shortTerm)
        val state = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = null
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

        fun claim(): TurnOwnership = TurnOwnership(guard, guard.claim())

        suspend fun runTurn(promptText: String, ownership: TurnOwnership): ConversationDraft {
            val input = ConversationInvocation(promptText, emptyList())
            val callbacks = ConversationCallbacks({}, {})
            val reply = ConversationReply("generation", PipelineBenchmarkCapture("generation", "text", 0,
                PipelineBenchmarkProvenance("test", 1)), true, MemoryDeliveryFence(), { it() }, {}, {}, {}, {}, { _, _, _ -> })
            val generation = ConversationGeneration(session, references, builder, { error("No attachment") }, diagnostics)
            return generation.generate(input, 10_000,
                RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
                PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
                ConversationPrompt(builder, false, { null }, { null }), fake, reply, actions,
                ConversationInferenceTelemetry(input, callbacks, reply, diagnostics),
                turnOwnership = ownership)
        }
    }

    @Test fun supersededHeldTurnCannotTouchNewerTurnsSessionState() = runBlocking {
        val h = Harness()
        val gate = h.fake.newGate()
        h.fake.enqueue(fakeGenerationScript {
            emit("Alpha partial. ")
            holdAt(gate)
            emit("Alpha rest.")
        })
        h.fake.enqueueReply("Bravo answer.")

        val ownershipA = h.claim()
        val turnA = async { h.runTurn("first question", ownershipA) }
        withTimeout(5_000) { gate.entered.await() }
        assertTrue(turnA.isActive)

        // The newer turn claims (superseding A), runs to completion on the
        // same engine/session, and its finish writes shared session state.
        val ownershipB = h.claim()
        val draftB = h.runTurn("second question", ownershipB)
        assertEquals("Bravo answer.", draftB.generated.text)
        h.state.hasContext = true
        h.state.characters = 42

        // Releasing A lets its engine generation finish, but the turn
        // aborts as superseded before any post-generation shared-state
        // touch (action dispatch, reset-and-retry guards).
        gate.release()
        try {
            withTimeout(5_000) { turnA.await() }
            fail("a superseded turn must abort instead of completing")
        } catch (expected: TurnSupersededException) {
            assertTrue(expected.message!!.contains(ownershipA.sequence.toString()))
        }
        assertEquals("newer turn's context flag must survive the older teardown", true, h.state.hasContext)
        assertEquals("newer turn's character accounting must survive the older teardown", 42, h.state.characters)
        assertTrue(h.guard.isCurrent(ownershipB.sequence))
        assertFalse(h.guard.isCurrent(ownershipA.sequence))
        assertEquals(FakeModelEngine.Outcome.COMPLETED, h.fake.calls[0].outcome)
        assertEquals(FakeModelEngine.Outcome.COMPLETED, h.fake.calls[1].outcome)
    }

    @Test fun supersededTurnsFailureIsDroppedSilentlyButCurrentTurnsFailurePropagates() = runBlocking {
        val h = Harness()
        val gate = h.fake.newGate()
        val boom = RuntimeException("native engine exploded")
        h.fake.enqueue(fakeGenerationScript {
            emit("Partial ")
            holdAt(gate)
            fail(boom)
        })
        h.fake.enqueueReply("Bravo answer.")

        val ownershipA = h.claim()
        val turnA = async { h.runTurn("first question", ownershipA) }
        withTimeout(5_000) { gate.entered.await() }

        val ownershipB = h.claim()
        assertEquals("Bravo answer.", h.runTurn("second question", ownershipB).generated.text)

        // A's mid-stream failure lands after supersession: it is dropped
        // silently — the awaited failure is the supersession abort, never
        // the turn's own error.
        gate.release()
        try {
            withTimeout(5_000) { turnA.await() }
            fail("a superseded turn must abort instead of surfacing its error")
        } catch (expected: TurnSupersededException) {
            assertTrue(expected.message!!.contains(ownershipA.sequence.toString()))
        }
        assertEquals(FakeModelEngine.Outcome.FAILED, h.fake.calls[0].outcome)
        assertSame(boom, h.fake.calls[0].failure)

        // The identical failure on the CURRENT turn propagates unchanged.
        val boom2 = RuntimeException("current turn exploded")
        h.fake.enqueueFailure(boom2, "Partial ")
        val ownershipC = h.claim()
        try {
            h.runTurn("third question", ownershipC)
            fail("the current turn's failure must propagate")
        } catch (expected: RuntimeException) {
            assertSame(boom2, expected)
        }
        assertTrue(h.guard.isCurrent(ownershipC.sequence))
    }

    @Test fun cancelDuringStreamLeavesTheNextTurnCleanAndOwning() = runBlocking {
        val h = Harness()
        val gate = h.fake.newGate()
        h.fake.enqueue(fakeGenerationScript {
            emit("Partial ")
            hangOn(gate)
            emit("never delivered")
        })
        h.fake.enqueueReply("Recovered answer.")

        val ownershipA = h.claim()
        val turnA = async { h.runTurn("cancel me", ownershipA) }
        withTimeout(5_000) { gate.entered.await() }
        turnA.cancelAndJoin()
        assertTrue(turnA.isCancelled)
        assertEquals(FakeModelEngine.Outcome.CANCELLED, h.fake.calls[0].outcome)

        val ownershipB = h.claim()
        val draft = h.runTurn("next question", ownershipB)
        assertEquals("Recovered answer.", draft.generated.text)
        assertTrue(h.guard.isCurrent(ownershipB.sequence))
        assertEquals(FakeModelEngine.Outcome.COMPLETED, h.fake.calls[1].outcome)
    }

    @Test fun recoveryAbortsBeforeAnyResetWhenSupersededAndRunsWhenCurrent() = runBlocking {
        val h = Harness()
        var resets = 0
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? = null
            override fun isInsufficientAnswer(answer: String) = answer.isBlank()
        }
        val recovery = ConversationRecovery({ resets++ }, references, FactualityVerifier(),
            { "automatic question" }, h.diagnostics)

        fun draft(ownership: TurnOwnership): ConversationDraft {
            val input = ConversationInvocation("Tell me something useful.", emptyList())
            val callbacks = ConversationCallbacks({}, {})
            val reply = ConversationReply("recovery", PipelineBenchmarkCapture("recovery", "text", 0,
                PipelineBenchmarkProvenance("test", 1)), true, MemoryDeliveryFence(), { it() }, {}, {}, {}, {}, { _, _, _ -> })
            val answer = ConversationAnswer(input,
                RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
                PreparedConversation(emptyList(), null, null, null, false, false, null),
                emptyList(), ConversationPrompt(h.builder, false, { null }, { null }),
                h.fake, reply, "submitted prompt", null, false,
                AssistantStreamFilter { }, null,
                ConversationInferenceTelemetry(input, callbacks, reply, h.diagnostics),
                turnOwnership = ownership)
            return ConversationDraft(answer, GenerationResult("A complete answer.", 1L, null), true)
        }

        // Current ownership: recovery runs its stages (this draft needs no
        // reset) and completes.
        val current = h.claim()
        recovery.recover(draft(current))
        assertEquals(0, resets)

        // Superseded ownership: the same draft aborts at recovery entry,
        // before any stage can reset the shared native conversation.
        val stale = h.claim()
        h.claim()
        try {
            recovery.recover(draft(stale))
            fail("a superseded turn's recovery must abort before touching shared state")
        } catch (expected: TurnSupersededException) {
            assertTrue(expected.message!!.contains(stale.sequence.toString()))
        }
        assertEquals("no recovery reset may run for a superseded turn", 0, resets)
    }
}
