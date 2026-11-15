package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
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
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The presence half of the no-vision honesty guard at the generation
 * stage: a turn that carries an attached image is never guarded, even
 * when the question is vision-directed and the answer claims perception.
 * Bad recognition on a real image is a different problem. Needs a real
 * [Uri], hence Robolectric; the no-image generation and recovery cases
 * live in [VisionPerceptionClaimTest].
 */
@RunWith(RobolectricTestRunner::class)
class VisionPerceptionClaimImageTurnTest {
    private companion object {
        const val DEVICE_CLAIM = "I see a room with a window and a desk. " +
            "This is the primary visual data I am currently processing."
    }

    /** Scripted backend: the image overload records the bytes it was handed. */
    private class ScriptedBackend(outputs: List<GenerationResult>) : ConversationBackend {
        data class Request(val prompt: String, val image: ByteArray?)
        val requests = mutableListOf<Request>()
        private val outputs = ArrayDeque(outputs)
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
        private fun result(prompt: String, image: ByteArray?, emit: (String) -> Unit): GenerationResult {
            requests.add(Request(prompt, image))
            check(outputs.isNotEmpty()) { "Unexpected generation" }
            return outputs.removeFirst().also { if (it.text.isNotEmpty()) emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result(prompt, null, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            result(prompt, null, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) =
            result(prompt, imageBytes, onToken)
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Unrequested tools must not execute")
    }

    @Test fun imageTurnIsNeverGuardedEvenWhenTheAnswerClaimsPerception() = runBlocking {
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val backend = ScriptedBackend(listOf(GenerationResult(DEVICE_CLAIM, 1, null)))
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation("What do you see in this?", emptyList(),
            imageUri = Uri.parse("content://faraday.test/image"))
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
        val generation = ConversationGeneration(session, references, builder,
            { ByteArrayInputStream(imageBytes) }, diagnostics)
        val draft = generation.generate(input, 10_000,
            RoutedConversation(emptyList(), TurnPlan(TurnKind.NORMAL_CHAT), ActionTurnPlan.NotAction, null),
            PreparedConversation(emptyList(), null, null, null, false, false, null), emptyList(),
            ConversationPrompt(builder, false, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
        // The vision-directed question and the claiming answer both trip
        // their detectors; only the attached image stands between this
        // turn and the guard, and it must be enough: exactly one request,
        // carrying the image, and the draft ships unchanged.
        assertTrue(VisionPerceptionClaim.isVisionDirectedQuestion(input.prompt))
        assertTrue(VisionPerceptionClaim.assertsPerception(DEVICE_CLAIM))
        assertEquals(1, backend.requests.size)
        assertTrue(backend.requests.single().image.contentEquals(imageBytes))
        assertEquals(DEVICE_CLAIM, draft.generated.text)
        assertTrue(draft.generated.text.isNotEmpty())
    }
}
