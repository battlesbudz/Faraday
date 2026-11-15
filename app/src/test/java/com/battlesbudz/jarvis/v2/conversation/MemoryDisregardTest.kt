package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.FactualityVerifier
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
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
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The two device specimens behind the memory-disregard guard. On build 1648
 * the name record was in the prompt and "whats my name" still drew a
 * model-identity deflection; on build 1666 all five records were in the
 * prompt (retrieval line: semantic=available, profile=4, fused=1) and
 * "what are some things I have done?" still drew a no-access claim.
 * Retrieval and injection were never at fault — the prompt framed the
 * records only as untrusted data and never as the user's history to answer
 * from. These tests pin the backstop: with records injected, a disregard is
 * detected, the turn regenerates once with the disregard named, and a second
 * disregard is replaced by the injected records themselves. With no records
 * injected, the same words are honest and pass untouched.
 */
class MemoryDisregardTest {
    private companion object {
        /** Verbatim deflection from Battles's build-1648 test call. */
        const val FAILURE_NAME = "I am a large language model, trained to communicate and assist."
        /** Verbatim no-access claim from Battles's build-1666 chat export. */
        const val FAILURE_HISTORY = "I don't have access to your personal history or activities, " +
            "so I can't tell you what you have done."
        val CONTENTS = listOf(
            "My name is Justin Lee Battles",
            "I have traveled to 27 countries",
            "I've also been wakeboarding in Dubai and I enjoy skateboarding")
        const val HISTORY_QUESTION = "hey what are some things I have done?"
        // Whole against CONTENTS, not merely disregard-free: the
        // completeness guard (MemoryCompletenessTest) also runs on this
        // path, and an answer that omits the name record is partial.
        const val RECORD_ANSWER = "Your name is Justin Lee Battles. You have traveled to 27 countries, " +
            "and you've been wakeboarding in Dubai."
    }

    private fun context(contents: List<String>): MemoryTurnContext =
        MemoryTurnContext("packet", "token", "question", null, 1,
            hasApprovedMemories = contents.isNotEmpty(), injectedContents = contents) { 1L }

    @Test fun detectorCatchesBothSpecimenDisregards() {
        assertTrue(MemoryDisregard.isDisregard(FAILURE_NAME, "whats my name"))
        assertTrue(MemoryDisregard.isDisregard(FAILURE_HISTORY, HISTORY_QUESTION))
        assertTrue(MemoryDisregard.isDisregard("I have no information about you, sir.", "What do you remember about me?"))
        assertTrue(MemoryDisregard.isDisregard("I don't know your name, sir.", "whats my name"))
    }

    @Test fun detectorLeavesHonestAndUnrelatedAnswersAlone() {
        assertFalse(MemoryDisregard.isDisregard(RECORD_ANSWER, HISTORY_QUESTION))
        assertFalse(MemoryDisregard.isDisregard("Your name is Justin Lee Battles, sir.", "whats my name"))
        // Truthful denials about things that are not the injected records.
        assertFalse(MemoryDisregard.isDisregard("I don't have access to your email, sir.", "Can you read my email?"))
        assertFalse(MemoryDisregard.isDisregard("I can't access the audio settings from here, sir.", "Turn the volume up"))
        // A model-identity statement is only a disregard when it deflects a
        // question about the user.
        assertFalse(MemoryDisregard.isDisregard(FAILURE_NAME, "What are you?"))
        assertFalse(MemoryDisregard.isDisregard("I'm an AI assistant, and from your records your name is Justin Lee Battles.", "whats my name"))
        // The fallback itself must never trip the detector.
        assertFalse(MemoryDisregard.isDisregard(MemoryDisregard.fallback(CONTENTS), HISTORY_QUESTION))
        assertFalse(MemoryDisregard.isDisregard("", HISTORY_QUESTION))
    }

    @Test fun fallbackRendersTheInjectedRecordsThemselves() {
        val fallback = MemoryDisregard.fallback(CONTENTS)
        assertTrue(fallback.startsWith("From your saved memories: "))
        CONTENTS.forEach { assertTrue(fallback.contains(it)) }
    }

    /** Scripted backend: each generation consumes the next queued result. */
    private class ScriptedBackend(outputs: List<GenerationResult>) : ConversationBackend {
        data class Request(val prompt: String, val purpose: PipelineBenchmarkPurpose)
        val requests = mutableListOf<Request>()
        val tools = mutableListOf<Boolean>()
        private val outputs = ArrayDeque(outputs)
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
        override fun inputContextDescription() = "fake"
        override var toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            tools.add(enabled)
            val changed = toolsEnabled != enabled
            toolsEnabled = enabled
            return changed
        }
        private fun result(prompt: String, emit: (String) -> Unit): GenerationResult {
            requests.add(Request(prompt, benchmarkPurpose))
            check(outputs.isNotEmpty()) { "Unexpected generation" }
            return outputs.removeFirst().also { if (it.text.isNotEmpty()) emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result(prompt, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
            error("Unexpected audio input")
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
            error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Unrequested tools must not execute")
    }

    private suspend fun generate(backend: ScriptedBackend, prompt: String,
                                 memoryContext: MemoryTurnContext?): ConversationDraft {
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation(prompt, emptyList())
        val callbacks = ConversationCallbacks({}, {})
        val reply = ConversationReply("generation", PipelineBenchmarkCapture("generation", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, memory, { it() }, {}, {}, {}, {}, { _, _, _ -> })
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
        val generation = ConversationGeneration(session, references, builder, { error("No attachment") }, diagnostics)
        return generation.generate(input, 10_000,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
            ConversationPrompt(builder, false, { null }, { memoryContext }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
    }

    @Test fun firstGenerationDisregardIsRegeneratedWithTheRecordsNamed() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(FAILURE_HISTORY, 1, null),
            GenerationResult(RECORD_ANSWER, 1, null)))
        val draft = generate(backend, HISTORY_QUESTION, context(CONTENTS))
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.last().prompt.contains("ARE the user's own saved history"))
        assertEquals(PipelineBenchmarkPurpose.RETRY, backend.requests.last().purpose)
        assertEquals(RECORD_ANSWER, draft.generated.text)
        assertNull(draft.actionResultMessage)
    }

    @Test fun nameDeflectionIsRegenerated() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(FAILURE_NAME, 1, null),
            GenerationResult("Your name is Justin Lee Battles, sir.", 1, null)))
        val draft = generate(backend, "whats my name", context(CONTENTS))
        assertEquals(2, backend.requests.size)
        assertEquals("Your name is Justin Lee Battles, sir.", draft.generated.text)
    }

    @Test fun repeatedDisregardFallsBackToTheInjectedRecords() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(FAILURE_HISTORY, 1, null),
            GenerationResult(FAILURE_NAME, 1, null)))
        val draft = generate(backend, HISTORY_QUESTION, context(CONTENTS))
        assertEquals(2, backend.requests.size)
        assertEquals(MemoryDisregard.fallback(CONTENTS), draft.generated.text)
        assertTrue(draft.generated.text.contains("27 countries"))
        assertFalse(MemoryDisregard.isDisregard(draft.generated.text, HISTORY_QUESTION))
    }

    @Test fun disregardWithNoRecordsInjectedIsNotGuarded() = runBlocking {
        val backend = ScriptedBackend(listOf(GenerationResult(FAILURE_HISTORY, 1, null)))
        val draft = generate(backend, HISTORY_QUESTION, null)
        // The guard is scoped to turns that actually carried records; with
        // none injected, the same words are an honest statement.
        assertEquals(1, backend.requests.size)
        assertEquals(FAILURE_HISTORY, draft.generated.text)
    }

    @Test fun answerUsingTheRecordsPassesThroughUntouched() = runBlocking {
        val backend = ScriptedBackend(listOf(GenerationResult(RECORD_ANSWER, 1, null)))
        val draft = generate(backend, HISTORY_QUESTION, context(CONTENTS))
        assertEquals(1, backend.requests.size)
        assertEquals(RECORD_ANSWER, draft.generated.text)
    }

    /** Recovery fixture, modeled on AudioAccessRefusalTest's. */
    private class RecoveryFixture(memoryContext: MemoryTurnContext?) {
        val backend = ScriptedRecoveryBackend()
        var resets = 0
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val invocation = ConversationInvocation(HISTORY_QUESTION, emptyList())
        val callbacks = ConversationCallbacks({}, {})
        val reply = ConversationReply("recovery", PipelineBenchmarkCapture("recovery", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, MemoryDeliveryFence(),
            post = { it() }, onToken = {}, onComplete = {}, onLatency = {}, onAnswer = {},
            finishOwnedBenchmark = { _, _, _ -> })
        val answer = ConversationAnswer(invocation,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null),
            emptyList(), ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()),
                voice = false, contextTokens = { null }, memoryContext = { memoryContext }),
            backend, reply, "original submitted prompt", null, false,
            AssistantStreamFilter { }, null,
            ConversationInferenceTelemetry(invocation, callbacks, reply, diagnostics))
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? = null
            override fun isInsufficientAnswer(answer: String) = answer.isBlank()
        }
        val recovery = ConversationRecovery({ resets++ }, references, FactualityVerifier(), { "automatic question" },
            diagnostics)
        fun draft(text: String) = ConversationDraft(answer, GenerationResult(text, 1L, null), true)
    }

    private class ScriptedRecoveryBackend : ConversationBackend {
        val requests = mutableListOf<String>()
        val outputs = ArrayDeque<GenerationResult>()
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        override fun inputContextDescription() = "fake"
        override val toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean = false
        private fun next(prompt: String, emit: (String) -> Unit): GenerationResult {
            requests += prompt
            check(outputs.isNotEmpty()) { "Unexpected recovery inference" }
            return outputs.removeFirst().also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = next(prompt, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, onToken)
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>,
                                             onToken: (String) -> Unit): GenerationResult =
            error("Recovery must never dispatch tools")
    }

    @Test fun recoveryBackstopSubstitutesTheRecordsFallback() = runBlocking {
        val f = RecoveryFixture(context(CONTENTS))
        val draft = f.draft(FAILURE_HISTORY)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(MemoryDisregard.fallback(CONTENTS), draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesTheSameWordsAloneWhenNoRecordsWereInjected() = runBlocking {
        val f = RecoveryFixture(null)
        val draft = f.draft(FAILURE_HISTORY)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(FAILURE_HISTORY, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesARealAnswerAlone() = runBlocking {
        val f = RecoveryFixture(context(CONTENTS))
        val draft = f.draft(RECORD_ANSWER)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(RECORD_ANSWER, draft.cleanedResponse)
    }
}
