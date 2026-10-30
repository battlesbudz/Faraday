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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The build-1595 specimen: on a direct-audio call turn whose audio WAS
 * attached to every submission, Gemma still answered "I cannot respond to
 * the audio as I do not have access to the audio content." The routing was
 * never at fault — every retry resubmits the audio — so these tests pin the
 * refusal guard: the claim is detected, the turn regenerates once with the
 * audio re-attached and the false claim named, and a second refusal is
 * replaced by the honest repeat-request fallback instead of being answered.
 */
class AudioAccessRefusalTest {
    private companion object {
        /** Verbatim refusal from Battles's build-1595 call diagnostics. */
        const val REFUSAL_ONE = "Sir, I cannot respond to the audio as I do not have access to the audio content. " +
            "I require the audio file to process your request."
        /** Second verbatim refusal from the same call. */
        const val REFUSAL_TWO = "Sir, I am unable to respond to the audio as I do not have access to its content."
        const val HONEST_UNCLEAR = "I couldn't make out the words in that recording, sir. Could you say it again?"
    }

    @Test fun detectorCatchesBothSpecimenRefusals() {
        assertTrue(AudioAccessRefusal.isRefusal(REFUSAL_ONE))
        assertTrue(AudioAccessRefusal.isRefusal(REFUSAL_TWO))
        assertTrue(AudioAccessRefusal.isRefusal("I don't have access to the audio."))
        assertTrue(AudioAccessRefusal.isRefusal("I cannot access the audio."))
    }

    @Test fun detectorLeavesHonestAndUnrelatedAnswersAlone() {
        assertFalse(AudioAccessRefusal.isRefusal(HONEST_UNCLEAR))
        assertFalse(AudioAccessRefusal.isRefusal(AudioAccessRefusal.FALLBACK))
        assertFalse(AudioAccessRefusal.isRefusal("The recording was too quiet to understand."))
        assertFalse(AudioAccessRefusal.isRefusal("I can't access the audio settings from here, sir."))
        assertFalse(AudioAccessRefusal.isRefusal("You are looking at a person."))
        assertFalse(AudioAccessRefusal.isRefusal(""))
    }

    /** Scripted backend: each generation consumes the next queued result. */
    private class ScriptedBackend(outputs: List<GenerationResult>) : ConversationBackend {
        data class Request(val prompt: String, val audio: ByteArray?, val purpose: PipelineBenchmarkPurpose)
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
        private fun result(prompt: String, audio: ByteArray?, emit: (String) -> Unit): GenerationResult {
            requests.add(Request(prompt, audio, benchmarkPurpose))
            check(outputs.isNotEmpty()) { "Unexpected generation" }
            return outputs.removeFirst().also { if (it.text.isNotEmpty()) emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result(prompt, null, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            result(prompt, audioBytes, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
            error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Unrequested tools must not execute")
    }

    private suspend fun generate(backend: ScriptedBackend, audio: ByteArray?): ConversationDraft {
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation("Hello", emptyList(), voiceAudio = audio, directVoiceAudio = audio != null)
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
            ConversationPrompt(builder, audio != null, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
    }

    @Test fun toolRetryRefusalIsRegeneratedWithTheSameAudioAndTheClaimNamed() = runBlocking {
        val audio = byteArrayOf(1, 2, 3)
        val backend = ScriptedBackend(listOf(
            // The specimen's first generation: a tool-call-only draft that
            // does not match the spoken intent...
            GenerationResult("", 1, null, toolCalls = listOf(ToolCall("open_app", "{}"))),
            // ...whose tool-less retry answers with the false refusal...
            GenerationResult(REFUSAL_ONE, 1, null),
            // ...so the guard regenerates once more and the turn answers.
            GenerationResult("A fresh answer.", 1, null)))
        val draft = generate(backend, audio)
        assertEquals(3, backend.requests.size)
        assertTrue(backend.requests.all { it.audio === audio })
        assertTrue(backend.requests.last().prompt.contains("IS attached to this message"))
        assertEquals(PipelineBenchmarkPurpose.RETRY, backend.requests.last().purpose)
        assertEquals(listOf(true, false, false), backend.tools)
        assertEquals("A fresh answer.", draft.generated.text)
        assertNull(draft.actionResultMessage)
    }

    @Test fun firstGenerationRefusalIsRegeneratedWithAudio() = runBlocking {
        val audio = byteArrayOf(4, 5, 6)
        val backend = ScriptedBackend(listOf(
            GenerationResult(REFUSAL_TWO, 1, null),
            GenerationResult("A fresh answer.", 1, null)))
        val draft = generate(backend, audio)
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.all { it.audio === audio })
        assertEquals("A fresh answer.", draft.generated.text)
    }

    @Test fun repeatedRefusalFallsBackToAnHonestRepeatRequest() = runBlocking {
        val audio = byteArrayOf(7, 8, 9)
        val backend = ScriptedBackend(listOf(
            GenerationResult(REFUSAL_ONE, 1, null),
            GenerationResult(REFUSAL_TWO, 1, null)))
        val draft = generate(backend, audio)
        assertEquals(2, backend.requests.size)
        assertEquals(AudioAccessRefusal.FALLBACK, draft.generated.text)
        assertFalse(AudioAccessRefusal.isRefusal(draft.generated.text))
    }

    @Test fun honestUnclearAudioAnswerPassesThroughUntouched() = runBlocking {
        val audio = byteArrayOf(1, 0, 2, 0)
        val backend = ScriptedBackend(listOf(GenerationResult(HONEST_UNCLEAR, 1, null)))
        val draft = generate(backend, audio)
        assertEquals(1, backend.requests.size)
        assertEquals(HONEST_UNCLEAR, draft.generated.text)
    }

    @Test fun refusalTextOnATextTurnIsNotGuarded() = runBlocking {
        val backend = ScriptedBackend(listOf(GenerationResult(REFUSAL_ONE, 1, null)))
        val draft = generate(backend, null)
        // The guard is scoped to turns that actually carry audio; a text
        // turn's statement about an audio file can be a legitimate answer.
        assertEquals(1, backend.requests.size)
        assertEquals(REFUSAL_ONE, draft.generated.text)
    }

    /** Recovery fixture, modeled on ConversationRecoveryTest's. */
    private class RecoveryFixture(directAudio: Boolean, voiceAudio: ByteArray?) {
        val backend = ScriptedRecoveryBackend()
        var resets = 0
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val invocation = ConversationInvocation("Tell me something useful.", emptyList(), voiceAudio = voiceAudio)
        val callbacks = ConversationCallbacks({}, {})
        val reply = ConversationReply("recovery", PipelineBenchmarkCapture("recovery", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, MemoryDeliveryFence(),
            post = { it() }, onToken = {}, onComplete = {}, onLatency = {}, onAnswer = {},
            finishOwnedBenchmark = { _, _, _ -> })
        val answer = ConversationAnswer(invocation,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null),
            emptyList(), ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()),
                voice = voiceAudio != null, contextTokens = { null }, memoryContext = { null }),
            backend, reply, "original submitted prompt", null, directAudio,
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
        data class Request(val prompt: String, val audio: ByteArray?)
        val requests = mutableListOf<Request>()
        val outputs = ArrayDeque<GenerationResult>()
        val tools = mutableListOf<Boolean>()
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        override fun inputContextDescription() = "fake"
        override val toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            tools += enabled
            return false
        }
        private fun next(prompt: String, audio: ByteArray?, emit: (String) -> Unit): GenerationResult {
            requests += Request(prompt, audio)
            check(outputs.isNotEmpty()) { "Unexpected recovery inference" }
            return outputs.removeFirst().also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = next(prompt, null, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, imageBytes, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, audioBytes, onToken)
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>,
                                             onToken: (String) -> Unit): GenerationResult =
            error("Recovery must never dispatch tools")
    }

    @Test fun recoveryBackstopRegeneratesARefusalDraftWithTheAudio() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult("A fresh answer.", 1L, null)
        val draft = f.draft(REFUSAL_ONE)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertSame(audio, f.backend.requests.single().audio)
        assertTrue(f.backend.requests.single().prompt.contains("IS attached to this message"))
        assertEquals(listOf(false), f.backend.tools)
        assertEquals(1, f.resets)
        assertEquals("A fresh answer.", draft.cleanedResponse)
    }

    @Test fun recoveryBackstopFallsBackWhenTheRegenerationRefusesAgain() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult(REFUSAL_TWO, 1L, null)
        val draft = f.draft(REFUSAL_ONE)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertEquals(AudioAccessRefusal.FALLBACK, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesAnHonestAudioAnswerAlone() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(directAudio = true, voiceAudio = audio)
        val draft = f.draft(HONEST_UNCLEAR)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(HONEST_UNCLEAR, draft.cleanedResponse)
    }
}
