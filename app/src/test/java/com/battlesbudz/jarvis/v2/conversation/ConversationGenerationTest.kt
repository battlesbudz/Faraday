package com.battlesbudz.jarvis.v2.conversation

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
import com.battlesbudz.jarvis.v2.voice.NativeAudioCaptureTiming
import com.battlesbudz.jarvis.v2.voice.NativePauseCertificate
import com.battlesbudz.jarvis.v2.voice.NativePauseProposal
import com.battlesbudz.jarvis.v2.voice.NativeSpeculationDriver
import com.battlesbudz.jarvis.v2.voice.NativeVoicePromptPreview
import com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation
import com.battlesbudz.jarvis.v2.voice.WavEncoder
import com.battlesbudz.jarvis.v2.voice.pcmHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationGenerationTest {
    private class Backend : ConversationBackend {
        data class Request(val prompt: String, val audio: ByteArray?, val purpose: PipelineBenchmarkPurpose)
        val requests = mutableListOf<Request>()
        val tools = mutableListOf<Boolean>()
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
        override fun inputContextDescription() = "fake"
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean { tools.add(enabled); return false }
        private fun result(prompt: String, audio: ByteArray?, emit: (String) -> Unit): GenerationResult {
            requests.add(Request(prompt, audio, benchmarkPurpose))
            return if (requests.size == 1) GenerationResult("", 1, null,
                toolCalls = listOf(ToolCall("open_app", "{}")))
            else GenerationResult("A fresh answer.", 1, null).also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result(prompt, null, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) = result(prompt, audioBytes, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult = error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult = error("Unrequested tools must not execute")
    }

    private suspend fun generate(audio: ByteArray? = null,
                             nativeSpeculation: NativeVoiceSpeculation? = null): Pair<ConversationDraft, Backend> {
        val backend = Backend()
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation("Hello", emptyList(), voiceAudio = audio, directVoiceAudio = audio != null,
            nativeSpeculation = nativeSpeculation)
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
        val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false)
        val session = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
            "unused", shortTerm, {})
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? = error("Generation must not fetch")
            override fun isInsufficientAnswer(answer: String) = false
        }
        val actions = ConversationActions({ error("No Android executor") }, { _, _ -> error("No action admission") },
            { _, _, _, _ -> error("No action dispatch") }, {}, "conversation", { _, _, _ -> })
        val generation = ConversationGeneration(session, references, builder, { error("No attachment") }, diagnostics)
        val draft = generation.generate(input, 10_000,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
            ConversationPrompt(builder, audio != null, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
        return draft to backend
    }

    @Test fun invalidToolDraftRetriesAsReadOnlyTextWithOriginalPromptBoundary() = runBlocking {
        val (draft, backend) = generate()
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.last().prompt.startsWith(backend.requests.first().prompt + "\nThe previous output"))
        assertEquals(listOf(false, false), backend.tools)
        assertEquals(PipelineBenchmarkPurpose.RETRY, backend.requests.last().purpose)
        assertEquals("A fresh answer.", draft.generated.text)
        assertNull(draft.actionResultMessage)
    }

    @Test fun invalidDirectAudioDraftRetriesTheSameAuthoritativeAudioWithoutActionDispatch() = runBlocking {
        val audio = byteArrayOf(1, 2, 3)
        val (_, backend) = generate(audio)
        assertEquals(2, backend.requests.size)
        assertSame(audio, backend.requests.first().audio)
        assertSame(audio, backend.requests.last().audio)
        assertEquals(listOf(false, false), backend.tools)
        assertEquals(PipelineBenchmarkPurpose.RETRY, backend.requests.last().purpose)
    }

    private fun speculationDriver(resets: () -> Unit) = object : NativeSpeculationDriver {
        override suspend fun generate(proposal: NativePauseProposal, exactPrompt: String,
            onToken: (String) -> Unit, onProgress: (InferenceProgress) -> Unit): GenerationResult {
            onToken("Prepared."); return GenerationResult("Prepared.", 20, null)
        }
        override suspend fun rollback() { resets() }
        override fun requestCancel() {}
        override fun safeToRelease() = true
        override fun timing(): NativeAudioCaptureTiming? = null
    }

    @Test fun directAudioPromotesMatchingNativeSpeculation() = runBlocking {
        // The preview must carry the exact assembled submission prompt for
        // promote to accept it. assemble is a pure function of the same
        // inputs the production path uses, so computing it here yields the
        // identical string.
        val probePrompt = ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()), true, { null }, { null })
        val submitted = probePrompt.assemble("Hello", null, emptyList(), true, null, null, null, 10_000).text
        var resets = 0
        val pcm = byteArrayOf(1, 0, 2, 0)
        val full = pcm + byteArrayOf(0, 0)
        val wav = WavEncoder.pcm16Mono(full, 16000)
        val candidate = NativeVoiceSpeculation(this, "call", "turn", 1, speculationDriver { resets++ },
            NativeVoicePromptPreview(submitted) { true }, { true }, { true })
        val proposal = NativePauseProposal("turn", 1, 1, 2, 300, pcm)
        assertTrue(candidate.onProposal(proposal))
        assertTrue(candidate.confirmCapture(NativePauseCertificate(proposal, full.size / 2, pcmHash(full), 1, 3, 650), wav))
        val (draft, backend) = generate(wav, candidate)
        assertEquals("Prepared.", draft.generated.text)
        assertTrue("promoted speculation must not fall through to the backend", backend.requests.isEmpty())
        assertFalse(draft.containsCurrentTurn)
        assertTrue(candidate.closeAndDrain())
    }

    @Test fun toolPhaseSettlesOutstandingSpeculationBeforeMutation() = runBlocking {
        var resets = 0
        val pcm = byteArrayOf(1, 0, 2, 0)
        val candidate = NativeVoiceSpeculation(this, "call", "turn", 1, speculationDriver { resets++ },
            NativeVoicePromptPreview("unused") { true }, { true }, { true })
        assertTrue(candidate.onProposal(NativePauseProposal("turn", 1, 1, 2, 300, pcm)))
        // Never confirmed or promoted: the speculation is still outstanding
        // when the tool phase starts, so it must be settled (rolled back) there.
        val (draft, _) = generate(null, candidate)
        assertEquals(1, resets)
        assertEquals("A fresh answer.", draft.generated.text)
        assertTrue(candidate.closeAndDrain())
    }
}
