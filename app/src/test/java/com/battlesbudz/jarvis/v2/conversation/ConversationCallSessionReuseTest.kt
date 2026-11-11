package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Persistent voice-call conversation (build 1771 latency fix): a direct-audio
 * turn whose native conversation already holds the call's context submits
 * only the current request, while a fresh turn — and every guard retry, which
 * resets first — still submits the full prompt.
 */
class ConversationCallSessionReuseTest {
    private class Backend(script: List<GenerationResult>) : ConversationBackend {
        val requests = mutableListOf<String>()
        val tools = mutableListOf<Boolean>()
        private val results = ArrayDeque(script)
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
        override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult {
            requests.add(prompt); return results.removeFirst()
        }
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult {
            requests.add(prompt); return results.removeFirst()
        }
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
            error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Unrequested tools must not execute")
    }

    private class Harness(val backend: Backend, resident: Boolean, toolsOn: Boolean) {
        val state = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = null
            override var hasContext = resident
            override var characters = 0
        }
        var resets = 0
        val session: ConversationModelSession
        val generation: ConversationGeneration
        init {
            val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false, supportsTools = true)
            val shortTerm = ShortTermConversationContext()
            session = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
                "unused", shortTerm, {})
            session.beforeNativeMutation = { resets++ }
            backend.toolsEnabled = toolsOn
            val references = object : ConversationReferences {
                override suspend fun fetch(query: String): String? = error("Generation must not fetch")
                override fun isInsufficientAnswer(answer: String) = false
            }
            generation = ConversationGeneration(session, references, ConversationPromptBuilder(shortTerm),
                { error("No attachment") }, ConversationDiagnostics({}, {}, {}, {}))
        }
        suspend fun generate(prompt: String, audio: ByteArray?, history: List<ChatEntry> = emptyList()): ConversationDraft {
            val diagnostics = ConversationDiagnostics({}, {}, {}, {})
            val input = ConversationInvocation(prompt, history, voiceAudio = audio, directVoiceAudio = audio != null)
            val callbacks = ConversationCallbacks({}, {})
            val reply = ConversationReply("reuse", PipelineBenchmarkCapture("reuse", "text", 0,
                PipelineBenchmarkProvenance("test", 1)), true, MemoryDeliveryFence(), { it() }, {}, {}, {}, {}, { _, _, _ -> })
            val builder = ConversationPromptBuilder(ShortTermConversationContext())
            return generation.generate(input, 10_000,
                RoutedConversation(history, TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
                PreparedConversation(history, null, null, null, false, false, null), history,
                ConversationPrompt(builder, audio != null, { null }, { null }), backend, reply,
                ConversationActions({ error("No Android executor") }, { _, _ -> error("No action admission") },
                    { _, _, _, _ -> error("No action dispatch") }, {}, "conversation", { _, _, _ -> }),
                ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
        }
    }

    @Test fun residentDirectAudioTurnSubmitsOnlyTheCurrentRequest() = runBlocking {
        val backend = Backend(listOf(GenerationResult("A fresh answer.", 1, null)))
        val h = Harness(backend, resident = true, toolsOn = true)
        val history = listOf(ChatEntry("You", "Tell me a joke."), ChatEntry("Jarvis", "A joke about a lake."))
        val draft = h.generate(GemmaAudioInputPolicy.REQUEST, byteArrayOf(1, 2, 3), history)
        // The whole submission is the current request: persona, dialogue and
        // the call-start supporting context already live in the resident
        // native conversation and are not re-prefilled.
        assertEquals(listOf(GemmaAudioInputPolicy.REQUEST), backend.requests)
        assertTrue(backend.requests.single().length < 600)
        // Tools were already enabled, so the turn performed no policy flip
        // and no reset: the one setToolsEnabled call is the no-op check.
        assertEquals(listOf(true), backend.tools)
        assertEquals(0, h.resets)
        assertTrue(h.state.hasContext)
        assertTrue(draft.containsCurrentTurn)
        assertEquals("A fresh answer.", draft.generated.text)
    }

    @Test fun freshDirectAudioTurnSubmitsTheFullPromptAndFlipsToolsOn() = runBlocking {
        val backend = Backend(listOf(GenerationResult("A fresh answer.", 1, null)))
        val h = Harness(backend, resident = false, toolsOn = false)
        h.generate(GemmaAudioInputPolicy.REQUEST, byteArrayOf(1, 2, 3))
        val prompt = backend.requests.single()
        assertTrue(prompt.contains(VoiceResponsePolicy.instructions))
        assertTrue(prompt.contains("Current user message:\n" + GemmaAudioInputPolicy.REQUEST))
        assertTrue(prompt.length > 1_200)
        assertEquals(listOf(true), backend.tools)
    }

    @Test fun residentTurnAudioRefusalRetryReseedsTheFullPrompt() = runBlocking {
        val refusal = "I cannot respond to the audio as I do not have access to the audio content."
        val backend = Backend(listOf(
            GenerationResult(refusal, 1, null),
            GenerationResult("A clear answer.", 1, null)))
        val h = Harness(backend, resident = true, toolsOn = true)
        val draft = h.generate(GemmaAudioInputPolicy.REQUEST, byteArrayOf(1, 2, 3))
        assertEquals(2, backend.requests.size)
        assertEquals(GemmaAudioInputPolicy.REQUEST, backend.requests[0])
        // The guard reset before regenerating, so its retry re-seeds the
        // full prompt (persona + request) and appends the correction.
        assertTrue(backend.requests[1].contains(VoiceResponsePolicy.instructions))
        assertTrue(backend.requests[1].contains(GemmaAudioInputPolicy.REQUEST))
        assertTrue(backend.requests[1].endsWith(AudioAccessRefusal.CORRECTION))
        assertEquals(1, h.resets)
        assertEquals("A clear answer.", draft.generated.text)
    }

    @Test fun residentTurnInvalidToolDraftRetryReseedsTheFullPrompt() = runBlocking {
        val backend = Backend(listOf(
            GenerationResult("", 1, null, toolCalls = listOf(ToolCall("no_such_tool", "{}"))),
            GenerationResult("A plain answer.", 1, null)))
        val h = Harness(backend, resident = true, toolsOn = true)
        val draft = h.generate(GemmaAudioInputPolicy.REQUEST, byteArrayOf(1, 2, 3))
        assertEquals(2, backend.requests.size)
        assertEquals(GemmaAudioInputPolicy.REQUEST, backend.requests[0])
        assertTrue(backend.requests[1].contains(VoiceResponsePolicy.instructions))
        assertTrue(backend.requests[1].contains("The previous output contained an invalid tool call."))
        assertEquals(1, h.resets)
        assertEquals("A plain answer.", draft.generated.text)
    }

    @Test fun textTurnWithResidentContextNeverUsesTheCallDelta() = runBlocking {
        val backend = Backend(listOf(GenerationResult("Hello.", 1, null)))
        val h = Harness(backend, resident = true, toolsOn = false)
        h.generate("Hello", null)
        val prompt = backend.requests.single()
        // Resident assembly is scoped to direct-audio call turns: a text turn
        // keeps the full chat prompt even when a conversation is resident.
        assertTrue(prompt.contains("You are Jarvis, a private local assistant"))
        assertTrue(prompt.contains("Current user message:\nHello"))
    }
}
