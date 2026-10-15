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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ConversationGenerationTest {
    private class Backend(private val events: MutableList<String> = mutableListOf()) : ConversationBackend {
        data class Request(val prompt: String, val audio: ByteArray?, val purpose: PipelineBenchmarkPurpose)
        val requests = mutableListOf<Request>()
        val tools = mutableListOf<Boolean>()
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
        override fun inputContextDescription() = "fake"
        // Real flip semantics: a transition returns true, a no-op returns false.
        override var toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            tools.add(enabled)
            events.add("tools:$enabled")
            val changed = toolsEnabled != enabled
            toolsEnabled = enabled
            return changed
        }
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
                             nativeSpeculation: NativeVoiceSpeculation? = null,
                             backend: Backend = Backend()): Pair<ConversationDraft, Backend> {
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
        // Direct-audio intentionally enables tools so the model may emit tool
        // calls (audio-native tools); the invalid-draft retry disables them
        // again. The no-dispatch guarantee is the assertions around this one.
        assertEquals(listOf(true, false), backend.tools)
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

    @Test fun blockedDriverDrainsBeforeAnyToolsPolicyTransition() = runBlocking {
        withTimeout(10_000) {
            val events = mutableListOf<String>()
            val backend = Backend(events)
            val probePrompt = ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()), true, { null }, { null })
            val submitted = probePrompt.assemble("Hello", null, emptyList(), true, null, null, null, 10_000).text
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var rollbacks = 0
            // Blocked driver: the speculative draft is still generating when
            // the ordinary turn starts. Nothing may mutate the native
            // conversation (notably the tool-policy reset inside
            // setToolsEnabled) until this driver has drained via promote.
            val driver = object : NativeSpeculationDriver {
                override suspend fun generate(proposal: NativePauseProposal, exactPrompt: String,
                    onToken: (String) -> Unit, onProgress: (InferenceProgress) -> Unit): GenerationResult {
                    events.add("driver_entered")
                    entered.complete(Unit)
                    release.await()
                    events.add("driver_returned")
                    onToken("Prepared.")
                    return GenerationResult("Prepared.", 20, null)
                }
                override suspend fun rollback() { rollbacks++ }
                override fun requestCancel() {}
                override fun safeToRelease() = true
                override fun timing(): NativeAudioCaptureTiming? = null
            }
            val pcm = byteArrayOf(1, 0, 2, 0)
            val full = pcm + byteArrayOf(0, 0)
            val wav = WavEncoder.pcm16Mono(full, 16000)
            val candidate = NativeVoiceSpeculation(this, "call", "turn", 1, driver,
                NativeVoicePromptPreview(submitted) { true }, { true }, { true })
            val proposal = NativePauseProposal("turn", 1, 1, 2, 300, pcm)
            assertTrue(candidate.onProposal(proposal))
            assertTrue(candidate.confirmCapture(NativePauseCertificate(proposal, full.size / 2, pcmHash(full), 1, 3, 650), wav))
            val generation = async { generate(wav, candidate, backend) }
            try {
                entered.await()
                // Let the ordinary turn start: with the old ordering it calls
                // setToolsEnabled at the top, before promote ever drains the
                // blocked driver. With the fix, promote runs first and blocks
                // on the driver, so no policy transition has happened yet.
                // 500ms is far beyond the dispatch time of the generation
                // coroutine and far inside the 1500ms speculation deadline.
                delay(500)
                assertTrue("tool policy must not transition while the speculation driver is blocked",
                    backend.tools.isEmpty())
                assertTrue(events.none { it.startsWith("tools:") })
            } finally {
                release.complete(Unit)
            }
            val (draft, _) = generation.await()
            assertEquals("Prepared.", draft.generated.text)
            assertTrue("promoted speculation must not fall through to the backend", backend.requests.isEmpty())
            // The policy transition runs only after the blocked driver drained.
            assertEquals(listOf(true), backend.tools)
            assertTrue(events.indexOf("driver_returned") < events.indexOf("tools:true"))
            assertEquals(1, rollbacks)
            assertTrue(candidate.closeAndDrain())
        }
    }
}
