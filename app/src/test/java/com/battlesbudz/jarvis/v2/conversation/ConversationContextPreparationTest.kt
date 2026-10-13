package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationContextPreparationTest {
    private class Memory : ConversationMemoryAccess {
        override val deliveryFence = MemoryDeliveryFence()
        var available = true
        var changed = false
        var cutoff = false
        var tokenCleared = false
        var adoptions = 0
        var captureReads = 0
        var cutoffReads = 0
        var packet = "approved packet"
        override fun approvedSnapshot(query: String, maxChars: Int): MemoryTurnContext? =
            if (available) MemoryTurnContext(packet, "token", query, null, 1,
                hasApprovedMemories = true, currentEpoch = { 1 }) else null
        override fun adopt(context: MemoryTurnContext) = changed.also { adoptions++ }
        override fun clearNativeToken() { tokenCleared = true }
        override fun isCurrent(context: MemoryTurnContext) = context.isCurrent()
        override fun consumeHistoryCutoff() = cutoff.also { cutoff = false; cutoffReads++ }
        override fun takeCaptureReceipt(prompt: String): ConversationMemoryResult? { captureReads++; return null }
    }
    private class Fixture {
        val memory = Memory()
        var currentHistory = listOf(ChatEntry("You", "new topic"))
        val terminal = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val bound = mutableListOf<MemoryDeliveryFence.Ticket>()
        val state = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = null
            override var hasContext = true
            override var characters = 800
        }
        val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false)
        val context = ShortTermConversationContext()
        val models = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
            "unused", context, {})
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? { queries.add(query); return "reference evidence" }
            override fun isInsufficientAnswer(answer: String) = answer.isBlank()
        }
        val reply = ConversationReply("context", PipelineBenchmarkCapture("context", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, memory.deliveryFence,
            { it() }, {}, terminal::add, {}, {}, { _, _, _ -> })
        val prompt = ConversationPrompt(ConversationPromptBuilder(context), false, { null }, { reply.memoryContext })
        val stage = ConversationContextPreparation({ currentHistory }, memory,
            TurnOrchestrator(ReferenceGroundingClient()), models, references, ConversationDiagnostics({}, {}, {}, {}))
        suspend fun actualDirectVoicePrompt(): String {
            val requestText = com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.REQUEST
            val builder = ConversationPromptBuilder(context)
            val finalPrompt = ConversationPrompt(builder, true, { model.contextTokens }, { reply.memoryContext })
            val request = ConversationTurnRequest(requestText,
                listOf(ChatEntry("You", "nominal voice seed that is not post-cutoff authority")),
                "conversation", false, false, true, true, false, null, null, false)
            val router = ConversationRouting({ currentHistory }, TurnOrchestrator(ReferenceGroundingClient()),
                models, { null }, {}, ConversationDiagnostics({}, {}, {}, {}))
            val actions = ConversationActions({ error("No action executor in direct audio") },
                { _, _ -> error("No action admission") }, { _, _, _, _ -> error("No action execution") },
                {}, "conversation", { _, _, _ -> })
            val routed = requireNotNull(router.route(request, reply, actions) {})
            val limit = ConversationPrompt.contextLimit(model.contextTokens, false)
            val prepared = requireNotNull(stage.prepare(request, routed, reply, finalPrompt, limit) { ticket, _ -> bound.add(ticket) })
            val history = models.prepareHistory(prepared.history, prepared.memoryHistoryInvalidated,
                finalPrompt.pendingSize(requestText, null, prepared.history, prepared.referenceContext), limit)
            return finalPrompt.assemble(requestText, null, history, !models.hasContext,
                routed.plan.activeSubject, routed.plan.resolvedQuestion, prepared.referenceContext, limit).text
        }
        suspend fun prepare(text: String, plan: TurnPlan = TurnPlan(TurnKind.NORMAL_CHAT)) = stage.prepare(
            ConversationTurnRequest(text, emptyList(), "conversation", false, false, false,
                false, false, null, null, false),
            RoutedConversation(listOf(ChatEntry("You", "old retained context")), plan, ActionTurnPlan.NotAction, null),
            reply, prompt, 10_000, { ticket, _ -> bound.add(ticket) })
    }

    @Test fun failedApprovedReadFencesResidentMemoryAndStopsBeforeLookup() = runBlocking {
        val f = Fixture()
        val prior = f.memory.deliveryFence.ticket()
        f.memory.available = false
        assertNull(f.prepare("A normal request", TurnPlan(TurnKind.EXPLICIT_LOOKUP, "lookup")))
        assertFalse(f.memory.deliveryFence.isValid(prior))
        assertTrue(f.memory.tokenCleared)
        assertFalse(f.state.hasContext)
        assertEquals(0, f.state.characters)
        assertTrue(f.queries.isEmpty())
        assertTrue(f.bound.isEmpty())
        assertEquals(listOf("Memory context is unavailable right now; please try again after storage recovers."), f.terminal)
    }

    @Test fun adoptedMemoryReloadsPostCutoffHistoryBeforeBuildingPrompt() = runBlocking {
        val f = Fixture()
        f.memory.changed = true
        f.memory.cutoff = true
        val prepared = f.prepare("A normal request")!!
        assertEquals(f.currentHistory, prepared.history)
        assertFalse(prepared.history.any { it.text == "old retained context" })
        assertTrue(prepared.memoryHistoryInvalidated)
        assertEquals(1, f.bound.size)
        assertFalse(f.state.hasContext)
        assertTrue(f.prompt.build("A normal request", null, emptyList(), false).contains("approved packet"))
    }

    @Test fun personalRecallUsesApprovedLocalEvidenceWithoutExternalQuery() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare("What is my name?", TurnPlan(TurnKind.FACTUAL_LOCAL_FIRST, "external"))!!
        assertTrue(prepared.personalMemoryRecall)
        assertNull(prepared.referenceContext)
        assertTrue(f.queries.isEmpty())
        assertTrue(f.terminal.isEmpty())
    }

    @Test fun explicitLookupKeepsRequestedReferenceRouteWithApprovedMemoryPresent() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare("Look up my name", TurnPlan(TurnKind.EXPLICIT_LOOKUP, "explicit query"))!!
        assertFalse(prepared.personalMemoryRecall)
        assertEquals(listOf("explicit query"), f.queries)
        assertEquals("reference evidence", prepared.referenceContext)
    }
    @Test fun nativePreviewMatchesActualFirstAndSecondTurnRouteContextAndAssembler() = runBlocking {
        for (secondTurn in listOf(false, true)) {
            val f = Fixture()
            f.state.hasContext = false // Voice preparation always resets native history.
            f.state.characters = 0
            f.currentHistory = if (secondTurn) listOf(
                ChatEntry("You", "Tell me about a quiet lake."),
                ChatEntry("Jarvis", "The lake was still at sunrise.")) else emptyList()
            val preview = requireNotNull(f.stage.previewDirectAudio(ConversationPromptBuilder(f.context)))
            assertEquals(0, f.memory.adoptions)
            assertEquals(0, f.memory.captureReads)
            assertEquals(0, f.memory.cutoffReads)
            assertTrue(f.bound.isEmpty()); assertTrue(f.terminal.isEmpty()); assertTrue(f.queries.isEmpty())
            // processTurn inserts a pending direct-audio row at final acceptance.
            f.currentHistory = f.currentHistory + ChatEntry("You", com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
            val actual = f.actualDirectVoicePrompt()
            assertEquals(preview.exactPrompt, actual)
            assertFalse(actual.contains("nominal voice seed"))
            assertFalse(actual.contains(com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
            if (secondTurn) assertTrue(actual.contains("The lake was still at sunrise."))
        }
    }

    @Test fun nativePreviewDoesNotAdoptMemoryAndFinalAdoptionDrainsBeforeNativeReset() = runBlocking {
        val f = Fixture(); f.state.hasContext = false; f.state.characters = 0
        f.memory.changed = true
        var drains = 0
        f.models.beforeNativeMutation = { drains++ }
        val preview = requireNotNull(f.stage.previewDirectAudio(ConversationPromptBuilder(f.context)))
        assertEquals(0, drains); assertEquals(0, f.memory.adoptions)
        assertEquals(preview.exactPrompt, f.actualDirectVoicePrompt())
        assertEquals(1, drains) // Equality alone cannot bypass required adoption/reset.
        assertEquals(1, f.memory.adoptions)
    }

    @Test fun changedMemoryCutoffMakesNativePreviewIneligibleBeforeFinalPromptReuse() = runBlocking {
        val f = Fixture(); f.state.hasContext = false; f.state.characters = 0
        f.currentHistory = listOf(ChatEntry("You", "I like old apples."))
        val preview = requireNotNull(f.stage.previewDirectAudio(ConversationPromptBuilder(f.context)))
        f.currentHistory = listOf(ChatEntry("You", "I prefer pears now."))
        f.memory.packet = "corrected approved packet"
        f.memory.changed = true; f.memory.cutoff = true
        var drains = 0; f.models.beforeNativeMutation = { drains++ }
        val actual = f.actualDirectVoicePrompt()
        assertNotEquals(preview.exactPrompt, actual)
        assertFalse(actual.contains("old apples")); assertTrue(actual.contains("corrected approved packet"))
        assertTrue(drains >= 1)
    }

}
