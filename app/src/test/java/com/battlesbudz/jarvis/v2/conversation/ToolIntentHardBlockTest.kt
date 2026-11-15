package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The tool-intent hard block (Battles, 2026-11-09). On build 1771, call
 * 83164c01, the model planned media_control {action=play} on a direct-audio
 * turn whose recognized request was "What is the capital of Idaho?" and the
 * plan EXECUTED — "Sent play command to the active media session" — after
 * which the playback stole the call's audio focus and degraded the rest of
 * the call. The direct-audio native path validated model calls only with
 * AudioToolGuard (allowlist + argument sanity), which a media play passes
 * trivially; the spoken-request intent check (FinalVoiceToolGuard) never
 * ran there. These tests pin the block at plan formation and execution,
 * and the precision bounds around it.
 */
class ToolIntentHardBlockTest {
    private class ToolCallBackend(private val firstCall: ToolCall) : ConversationBackend {
        val requests = mutableListOf<String>()
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
        override fun inputContextDescription() = "fake"
        override var toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            val changed = toolsEnabled != enabled
            toolsEnabled = enabled
            return changed
        }
        private fun result(prompt: String, emit: (String) -> Unit): GenerationResult {
            requests.add(prompt)
            return if (requests.size == 1) GenerationResult("", 1, null, toolCalls = listOf(firstCall))
            else GenerationResult("A fresh answer.", 1, null).also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result(prompt, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) = result(prompt, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult = error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Single-step plans must not submit tool results")
    }

    private class Harness(val draft: ConversationDraft, val backend: ToolCallBackend,
                          val executed: List<ActionRequest>, val summaries: List<String>)

    private fun run(firstCall: ToolCall, asrTranscript: String?): Harness {
        val summaries = mutableListOf<String>()
        val diagnostics = ConversationDiagnostics({}, {}, { summaries += it }, {})
        val executed = mutableListOf<ActionRequest>()
        val backend = ToolCallBackend(firstCall)
        val draft = runBlocking {
            val memory = MemoryDeliveryFence()
            val audio = byteArrayOf(1, 2, 3)
            // A direct-audio turn: the invocation prompt is the audio
            // boilerplate, exactly as production builds it; the recognized
            // request exists only as the turn's final ASR transcript.
            val input = ConversationInvocation(GemmaAudioInputPolicy.REQUEST, emptyList(),
                voiceAudio = audio, directVoiceAudio = true, asrTranscript = asrTranscript)
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
            val actions = ConversationActions(
                executor = { MobileActionExecutor { error("No Android executor") } },
                admit = { _, _ -> "group-1" },
                execute = { request, _, _, _ ->
                    executed += request
                    ExecutionResult(true, when (request.name) {
                        "media_control" -> "Sent play command to the active media session."
                        "read_battery" -> "Battery is at 76%."
                        else -> "Done."
                    })
                },
                cancelUnfinished = {}, conversationId = "conversation", onActionResult = { _, _, _ -> },
                mainDispatcher = Dispatchers.Unconfined,
                executionDispatcher = Dispatchers.Unconfined)
            val generation = ConversationGeneration(session, references, builder, { error("No attachment") }, diagnostics)
            generation.generate(input, 10_000,
                RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
                PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
                ConversationPrompt(builder, true, { null }, { null }), backend, reply, actions,
                ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
        }
        return Harness(draft, backend, executed, summaries)
    }

    /** The verbatim specimen: build 1771, call 83164c01. */
    @Test fun idahoCapitalQuestionRejectsMediaPlayBeforeExecution() {
        val harness = run(ToolCall("media_control", "{\"action\":\"play\"}"),
            asrTranscript = "What is the capital of Idaho?")
        // Nothing executed: the plan never formed, so the turn fell through
        // to the existing invalid-tool retry and answered as text.
        assertTrue(harness.executed.isEmpty())
        assertNull(harness.draft.actionName)
        assertNull(harness.draft.actionResultMessage)
        assertEquals("A fresh answer.", harness.draft.generated.text)
        assertEquals(2, harness.backend.requests.size)
        // The rejection is visible in diagnostics in the existing style, at
        // plan formation and again at the retry seam.
        assertTrue(harness.summaries.any {
            it == "Audio-native tool plan rejected step=media_control reason=does_not_match_current_intent" })
        assertTrue(harness.summaries.any {
            it == "Rejected tool response reason=does_not_match_current_intent retryToolsEnabled=false" })
    }

    @Test fun spokenMediaRequestExecutesMediaPlay() {
        val harness = run(ToolCall("media_control", "{\"action\":\"play\"}"),
            asrTranscript = "Play some music")
        assertEquals(listOf("media_control"), harness.executed.map { it.name })
        assertEquals("play", harness.executed.single().arguments["action"])
        assertEquals("media_control", harness.draft.actionName)
        assertEquals("Sent play command to the active media session.", harness.draft.actionResultMessage)
        assertEquals(1, harness.backend.requests.size)
    }

    @Test fun spokenBatteryRequestExecutesReadBattery() {
        val harness = run(ToolCall("read_battery", "{}"),
            asrTranscript = "What's my battery?")
        assertEquals(listOf("read_battery"), harness.executed.map { it.name })
        assertEquals("read_battery", harness.draft.actionName)
        assertEquals("Battery is at 76%.", harness.draft.actionResultMessage)
        assertEquals(1, harness.backend.requests.size)
    }

    @Test fun sandboxProposalIsNeitherExecutedNorIntentDropped() {
        // A model-proposed sandbox call never dispatches from the plan: it
        // parks for Battles's exact approval of the command text, which is
        // its verification. The intent gate must not drop it (the transcript
        // check would kill the voice sandbox flow — FinalVoiceToolGuard
        // admits only the empty-command routing marker), and nothing may
        // execute it here either.
        val harness = run(ToolCall("run_in_sandbox", "{\"command\":\"echo hello\"}"),
            asrTranscript = "What is the capital of Idaho?")
        assertTrue(harness.executed.isEmpty())
        assertTrue(harness.summaries.any {
            it == "Audio-native tool plan steps=[run_in_sandbox]" })
        assertTrue(harness.summaries.none {
            it.contains("rejected step=run_in_sandbox") })
        assertEquals("Did not attempt: run_in_sandbox.", harness.draft.actionResultMessage)
    }

    @Test fun noTranscriptTurnKeepsAllowlistOnlyBehavior() {
        // The deliberate bound: with no ASR transcript there is no
        // recognized text to match a plan against, so the allowlist +
        // argument-sanity policy stands alone, exactly as before the block.
        val harness = run(ToolCall("media_control", "{\"action\":\"play\"}"),
            asrTranscript = null)
        assertEquals(listOf("media_control"), harness.executed.map { it.name })
        assertEquals("media_control", harness.draft.actionName)
    }
}
