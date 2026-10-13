package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.FactualityVerifier
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Recovery orchestration is tested without native inference, Android effects or network access. */
class ConversationRecoveryTest {
    private fun result(text: String, calls: List<ToolCall> = emptyList()) =
        GenerationResult(text, 1L, null, toolCalls = calls)

    private class Backend : ConversationBackend {
        data class Request(val prompt: String, val mode: String,
                           val purpose: PipelineBenchmarkPurpose, val bytes: ByteArray?)
        val requests = mutableListOf<Request>()
        val outputs = ArrayDeque<GenerationResult>()
        val tools = mutableListOf<Boolean>()
        var failure: Exception? = null
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        override fun inputContextDescription() = "fake"
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            tools += enabled
            return false
        }
        private fun generate(prompt: String, mode: String, bytes: ByteArray?,
                             emit: (String) -> Unit): GenerationResult {
            requests += Request(prompt, mode, benchmarkPurpose, bytes)
            failure?.let { throw it }
            check(outputs.isNotEmpty()) { "Unexpected recovery inference: $mode" }
            return outputs.removeFirst().also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) =
            generate(prompt, "text", null, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) =
            generate(prompt, "image", imageBytes, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            generate(prompt, "audio", audioBytes, onToken)
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>,
                                             onToken: (String) -> Unit): GenerationResult =
            error("Recovery must never dispatch tools")
    }

    private class References : ConversationReferences {
        val queries = mutableListOf<String>()
        val evidence = mutableMapOf<String, String>()
        override suspend fun fetch(query: String): String? {
            queries += query
            return evidence[query]
        }
        override fun isInsufficientAnswer(answer: String) =
            answer.isBlank() || answer.contains("don't know", ignoreCase = true)
    }

    private class Fixture(
        kind: TurnKind = TurnKind.NORMAL_CHAT,
        referenceContext: String? = null,
        personalRecall: Boolean = false,
        val guard: VoiceRepetitionGuard? = null,
        directAudio: Boolean = false,
        voiceAudio: ByteArray? = null,
        imageBytes: ByteArray? = null
    ) {
        val backend = Backend()
        val references = References()
        val visible = mutableListOf<String>()
        var resets = 0
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val invocation = ConversationInvocation("Tell me something useful.", emptyList(), voiceAudio = voiceAudio)
        val callbacks = ConversationCallbacks(visible::add, {})
        val benchmark = PipelineBenchmarkCapture("recovery", "text", 0,
            PipelineBenchmarkProvenance("test", 1))
        val reply = ConversationReply("recovery", benchmark, true, MemoryDeliveryFence(),
            post = { it() }, onToken = visible::add, onComplete = {}, onLatency = {}, onAnswer = {},
            finishOwnedBenchmark = { _, _, _ -> })
        val plan = TurnPlan(kind, lookupQuery = "resolved subject")
        val answer = ConversationAnswer(invocation,
            RoutedConversation(emptyList(), plan, ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, referenceContext, personalRecall, false, null),
            emptyList(), ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()),
                voice = voiceAudio != null, contextTokens = { null }, memoryContext = { null }),
            backend, reply, "original submitted prompt", imageBytes, directAudio,
            AssistantStreamFilter { guard?.accept(it) ?: visible.add(it) }, guard,
            ConversationInferenceTelemetry(invocation, callbacks, reply, diagnostics))
        val recovery = ConversationRecovery({ resets++ }, references, FactualityVerifier(),
            automaticFallbackQuery = { "automatic question" }, diagnostics)
        fun draft(text: String, calls: List<ToolCall> = emptyList()) =
            ConversationDraft(answer, GenerationResult(text, 1L, null, toolCalls = calls), true)
    }

    @Test fun factualityPassIsInternalAndCannotBecomeResidentConversationContext() = runBlocking {
        val f = Fixture(TurnKind.FACTUAL_LOCAL_FIRST)
        f.backend.outputs += result("PASS")
        val draft = f.draft("The moon rotates.")
        f.recovery.recover(draft)
        assertEquals("The moon rotates.", draft.cleanedResponse)
        assertFalse(draft.containsCurrentTurn)
        assertEquals(2, f.resets)
        assertTrue(f.references.queries.isEmpty())
        assertTrue(f.visible.isEmpty())
        assertEquals(PipelineBenchmarkPurpose.DRAFT, f.backend.requests.single().purpose)
        assertEquals(listOf("factuality check"), f.reply.inferencePasses.map { it.stage })
    }

    @Test fun lookupVerdictUsesAutomaticQueryAndFreshEvidenceForOneFallback() = runBlocking {
        val f = Fixture(TurnKind.FACTUAL_LOCAL_FIRST)
        f.backend.outputs += result("LOOKUP")
        f.backend.outputs += result("A verified fact.")
        f.references.evidence["automatic question"] = "Reference evidence: moon rotation"
        val draft = f.draft("An unverified fact.")
        f.recovery.recover(draft)
        assertEquals(listOf("automatic question"), f.references.queries)
        assertEquals(3, f.resets)
        assertTrue(draft.containsCurrentTurn)
        assertEquals("A verified fact.", draft.cleanedResponse)
        assertEquals(listOf(PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkPurpose.RETRY),
            f.backend.requests.map { it.purpose })
        assertTrue(f.backend.requests.last().prompt.contains("Reference evidence: moon rotation"))
        assertEquals(listOf("factuality check", "reference fallback"), f.reply.inferencePasses.map { it.stage })
    }

    @Test fun unavailableEvidenceUsesDistinctFallbackAndRetryQueriesThenHonestFailure() = runBlocking {
        val f = Fixture(TurnKind.FACTUAL_LOCAL_FIRST)
        val draft = f.draft("I don't know.")
        f.recovery.recover(draft)
        assertEquals(listOf("automatic question", "resolved subject"), f.references.queries)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("I couldn't produce a verified answer from the available reference evidence. Please try again.",
            draft.cleanedResponse)
    }

    @Test fun explicitLookupRetriesFromEvidenceWithoutLocalFactualityPass() = runBlocking {
        val f = Fixture(TurnKind.EXPLICIT_LOOKUP, referenceContext = "old evidence")
        f.references.evidence["resolved subject"] = "fresh evidence"
        f.backend.outputs += result("A fresh fact.")
        val draft = f.draft("I don't know.")
        f.recovery.recover(draft)
        assertEquals(listOf("resolved subject"), f.references.queries)
        assertEquals(1, f.resets)
        assertEquals(PipelineBenchmarkPurpose.RETRY, f.backend.requests.single().purpose)
        assertTrue(f.backend.requests.single().prompt.contains("fresh evidence"))
        assertEquals(listOf("reference retry"), f.reply.inferencePasses.map { it.stage })
        assertEquals("A fresh fact.", draft.cleanedResponse)
    }

    @Test fun personalRecallDoesNotRunExternalFactualityOrReferenceRecovery() = runBlocking {
        val f = Fixture(TurnKind.FACTUAL_LOCAL_FIRST, personalRecall = true)
        val draft = f.draft("I don't know.")
        f.recovery.recover(draft)
        assertTrue(f.references.queries.isEmpty())
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("I don't know.", draft.cleanedResponse)
    }

    @Test fun acceptedOpeningPreventsReplacingAlreadyPublishedReferenceReply() = runBlocking {
        val guard = VoiceRepetitionGuard("Tell me something useful.", null) {}
        guard.accept("A useful opening.")
        val f = Fixture(TurnKind.EXPLICIT_LOOKUP, guard = guard)
        val draft = f.draft("I don't know.")
        f.recovery.recover(draft)
        assertTrue(f.references.queries.isEmpty())
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("A useful opening.", draft.cleanedResponse)
    }

    @Test fun protocolDraftIsFencedEvenWhenLaterReferenceRetrySucceeds() = runBlocking {
        val f = Fixture(TurnKind.EXPLICIT_LOOKUP)
        f.references.evidence["resolved subject"] = "fresh evidence"
        f.backend.outputs += result("A verified answer.")
        val draft = f.draft("", listOf(ToolCall("open_app", "{}")))
        f.recovery.recover(draft)
        assertTrue(draft.rawControlOutput)
        assertTrue(draft.containsCurrentTurn)
        assertEquals(2, f.resets)
        assertEquals("A verified answer.", draft.cleanedResponse)
        assertTrue(f.backend.tools.isEmpty())
    }

    @Test fun repetitionRepairDisablesToolsRetainsDirectAudioAndClearsNativeContext() = runBlocking {
        val guard = VoiceRepetitionGuard("Tell me something useful.", "Old answer.") {}
        val audio = byteArrayOf(1, 2, 3)
        val f = Fixture(guard = guard, directAudio = true, voiceAudio = audio)
        f.backend.outputs += result("A fresh answer.")
        val draft = f.draft("Old answer.")
        f.recovery.recover(draft)
        assertEquals(listOf(false), f.backend.tools)
        assertEquals("audio", f.backend.requests.single().mode)
        assertArrayEquals(audio, f.backend.requests.single().bytes)
        assertEquals(PipelineBenchmarkPurpose.RETRY, f.backend.requests.single().purpose)
        assertEquals(2, f.resets)
        assertFalse(draft.containsCurrentTurn)
        assertEquals("A fresh answer.", draft.cleanedResponse)
    }

    @Test fun userCancellationDuringRepairPropagatesAndStillResetsNativeOwner() = runBlocking {
        val guard = VoiceRepetitionGuard("Tell me something useful.", "Old answer.") {}
        val f = Fixture(guard = guard)
        f.backend.failure = CancellationException("user_stop")
        try {
            f.recovery.recover(f.draft("Old answer."))
            fail("User Stop must propagate")
        } catch (expected: CancellationException) {
            assertEquals("user_stop", expected.message)
        }
        assertEquals(2, f.resets)
        assertEquals("", guard.text)
    }

    @Test fun verifiedActionReceiptBypassesRepetitionRepair() = runBlocking {
        val guard = VoiceRepetitionGuard("Tell me something useful.", "Old answer.") {}
        val f = Fixture(guard = guard)
        val draft = f.draft("Old answer.")
        draft.actionName = "set_volume"
        draft.actionResultMessage = "Volume changed to 30 percent."
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertTrue(f.backend.tools.isEmpty())
        assertEquals(0, f.resets)
        assertEquals("Volume changed to 30 percent.",
            ConversationFinalizer.resolve(draft.cleanedResponse, null, draft.actionResultMessage, true,
                draft.actionName).text)
    }
}
