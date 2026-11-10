package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
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
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The build-1632 specimen: on a voice call whose build had no vision input
 * of any kind, "Can you tell me what you see?" was answered with an
 * invented scene stated as live perception, and asking again produced the
 * identical text, which the repetition guard suppressed into its generic
 * fallback. These tests pin the honesty guard: a vision-directed question
 * on a turn with no visual input regenerates once with the false claim
 * named, a second claim is replaced by the honest fallback, and the
 * recovery backstop discards a claim that reaches it — while turns with
 * an image, non-vision questions, honest answers and references to a
 * photo the user actually sent all pass untouched.
 *
 * The build-1697 specimens (call 443f75e8) pin the direct-audio gap that
 * call exposed: in native-audio mode the invocation prompt is the fixed
 * audio boilerplate, so the question side of the guard had no question
 * to read and both fabricated answers shipped with no guard line in the
 * export at all. On those turns the concrete-claim side admits the
 * guard instead, and every evaluation leaves a diagnostics line.
 */
class VisionPerceptionClaimTest {
    private companion object {
        /** Verbatim confabulation from Battles's build-1632 call diagnostics. */
        const val DEVICE_CLAIM = "I see a room with a window and a desk. " +
            "This is the primary visual data I am currently processing."
        const val HONEST_REGEN = "I can't see on this turn, sir — no image came through."
        const val VISION_QUESTION = "Can you tell me what you see?"
        /** Verbatim fabricated answer, build 1697 call 443f75e8 turn 1. */
        const val SPECIMEN_ONE = "I see a picture of a very large, brightly coloured, " +
            "somewhat abstract shape. It appears to be a complex, interwoven structure."
        /** Verbatim fabricated answer, same call, turn 2. */
        const val SPECIMEN_TWO = "I see a large, abstract shape. It is complex and interwoven."
        /**
         * Verbatim fabricated answer, build 1771 call c9a59a9f: an
         * audio-only call, no image attached, asked aloud "What can you
         * see right now?" The guard was wired in and ran; both sentences
         * evaded the claim patterns — the possessive-led object of
         * "I see your…" and the pronoun-subject "It is currently
         * displaying…" — so the claim shipped with no guard line at all.
         */
        const val SPECIMEN_PHONE_SCREEN = "I see your phone screen. " +
            "It is currently displaying a settings screen."
    }

    // ---- question detector ----

    @Test fun questionDetectorCatchesCurrentPerceptionAsks() {
        val positives = listOf(
            "Can you tell me what you see?",
            "Tell me what you see.",
            "Can you see what I'm looking at?",
            "What do you see?",
            "What can you see?",
            "What do you see now?",
            "Do you see anything?",
            "Look at this.",
            "What's in front of me?",
            "What is in front of the camera?",
            "What's around you?"
        )
        for (question in positives) {
            assertTrue("expected vision-directed: $question",
                VisionPerceptionClaim.isVisionDirectedQuestion(question))
        }
    }

    @Test fun questionDetectorLeavesMetaphoricalSeeAlone() {
        val negatives = listOf(
            "I see your point.",
            "Let's see what happens.",
            "What do you see in this argument?",
            "What do you see in this essay?",
            "Can you see why that matters?",
            "Can you see my point?",
            "How do you see this playing out?",
            "What do you see this as, a threat or an opportunity?",
            "See what you can find about train times.",
            "What's the weather like tomorrow?",
            ""
        )
        for (question in negatives) {
            assertFalse("expected not vision-directed: $question",
                VisionPerceptionClaim.isVisionDirectedQuestion(question))
        }
    }

    @Test fun questionDetectorLeavesSuppliedPhotoFollowUpsAlone() {
        // A follow-up about an image the user supplied on an earlier turn
        // is a legitimate reference, not a request for fresh perception.
        assertFalse(VisionPerceptionClaim.isVisionDirectedQuestion(
            "What else is in the photo I sent you?"))
        assertFalse(VisionPerceptionClaim.isVisionDirectedQuestion(
            "Describe the photo I shared earlier."))
        assertFalse(VisionPerceptionClaim.isVisionDirectedQuestion(
            "What do you see in the picture I uploaded?"))
    }

    // ---- answer detector ----

    @Test fun answerDetectorCatchesTheDeviceClaimVerbatim() {
        assertTrue(VisionPerceptionClaim.assertsPerception(DEVICE_CLAIM))
        assertTrue(VisionPerceptionClaim.assertsPerception(
            "I see a room with a window and a desk."))
        assertTrue(VisionPerceptionClaim.assertsPerception(
            "This is the primary visual data I am currently processing."))
    }

    @Test fun answerDetectorCatchesPerceptionVariants() {
        val claims = listOf(
            "I can see a kitchen with a table.",
            "I'm looking at a white ceiling fan.",
            "I am seeing double after that flash.",
            "The camera shows a hallway.",
            "What I see is a garden.",
            "You are looking at a map.",
            // A bare scene description as the opening, no "I see" anywhere.
            "A room with a window and a desk.",
            "I see you, sir."
        )
        for (claim in claims) {
            assertTrue("expected perception claim: $claim",
                VisionPerceptionClaim.assertsPerception(claim))
        }
    }

    @Test fun answerDetectorLeavesHonestAndAnchoredAnswersAlone() {
        val honest = listOf(
            VisionPerceptionClaim.FALLBACK,
            HONEST_REGEN,
            "I can't see anything on this turn, sir.",
            "No image came through on this turn, so I can't tell you what's there.",
            "I don't have a camera feed on this turn.",
            // References anchored to an image the user actually supplied.
            "In the photo you sent, I can see a desk by the window.",
            "The image you uploaded shows a chart of the results.",
            // Metaphorical seeing.
            "I see your point about the budget.",
            "I see. Let me think about that.",
            "You are looking at the problem from the wrong angle.",
            // Hypothetical framing that ends in an honest denial.
            "If I could see it I would tell you, but I can't see anything without an image.",
            ""
        )
        for (answer in honest) {
            assertFalse("expected no perception claim: $answer",
                VisionPerceptionClaim.assertsPerception(answer))
        }
    }

    // ---- generation stage ----

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
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            result(prompt, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
            error("Unexpected image input")
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Unrequested tools must not execute")
    }

    private suspend fun generate(
        backend: ScriptedBackend,
        prompt: String,
        history: List<ChatEntry> = emptyList()
    ): ConversationDraft {
        val diagnostics = ConversationDiagnostics({}, {}, {}, {})
        val memory = MemoryDeliveryFence()
        val input = ConversationInvocation(prompt, history)
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
            PreparedConversation(history, null, null, null, false, false, null), history,
            ConversationPrompt(builder, false, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
    }

    @Test fun visionClaimIsRegeneratedOnceWithTheClaimNamed() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(DEVICE_CLAIM, 1, null),
            GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generate(backend, VISION_QUESTION)
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.last().prompt.contains("cannot see anything right now"))
        assertEquals(PipelineBenchmarkPurpose.RETRY, backend.requests.last().purpose)
        assertEquals(listOf(false, false), backend.tools)
        assertEquals(HONEST_REGEN, draft.generated.text)
        assertNull(draft.actionResultMessage)
    }

    @Test fun repeatedVisionClaimFallsBackToTheHonestLine() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(DEVICE_CLAIM, 1, null),
            GenerationResult(DEVICE_CLAIM, 1, null)))
        val draft = generate(backend, VISION_QUESTION)
        assertEquals(2, backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, draft.generated.text)
        assertFalse(VisionPerceptionClaim.assertsPerception(draft.generated.text))
    }

    @Test fun honestVisionAnswerPassesThroughUntouched() = runBlocking {
        val backend = ScriptedBackend(listOf(GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generate(backend, VISION_QUESTION)
        assertEquals(1, backend.requests.size)
        assertEquals(HONEST_REGEN, draft.generated.text)
    }

    @Test fun claimOnANonVisionQuestionIsNotGuarded() = runBlocking {
        val backend = ScriptedBackend(listOf(GenerationResult(DEVICE_CLAIM, 1, null)))
        val draft = generate(backend, "What's the capital of France?")
        // All three conditions are required; the question is not
        // vision-directed, so the text is the model's to answer for.
        assertEquals(1, backend.requests.size)
        assertEquals(DEVICE_CLAIM, draft.generated.text)
    }

    @Test fun consecutiveVisionQuestionsBothEndHonest() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult(DEVICE_CLAIM, 1, null),
            GenerationResult(DEVICE_CLAIM, 1, null),
            GenerationResult(DEVICE_CLAIM, 1, null),
            GenerationResult(DEVICE_CLAIM, 1, null)))
        val first = generate(backend, VISION_QUESTION)
        assertEquals(VisionPerceptionClaim.FALLBACK, first.generated.text)
        // The follow-up the device test died on: the same question asked
        // again, with the first turn's honest fallback as the prior reply.
        // The guard fires per turn, independent of history, so the second
        // turn ends with the honest fallback too — never the claim and
        // never the repetition guard's generic dead end.
        val second = generate(backend, "Tell me what you see.", history = listOf(
            ChatEntry("You", VISION_QUESTION),
            ChatEntry("Jarvis", first.generated.text)))
        assertEquals(4, backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, second.generated.text)
        assertFalse(VisionPerceptionClaim.assertsPerception(second.generated.text))
    }

    // ---- recovery backstop ----

    /** Recovery fixture, modeled on AudioAccessRefusalTest's. */
    private class RecoveryFixture(
        prompt: String,
        directAudio: Boolean = false,
        voiceAudio: ByteArray? = null,
        imageBytes: ByteArray? = null,
        visualContextInjected: Boolean = false
    ) {
        val backend = ScriptedRecoveryBackend()
        var resets = 0
        val diagnosticLines = mutableListOf<String>()
        val diagnostics = ConversationDiagnostics({ diagnosticLines += it }, {}, {}, {})
        val invocation = ConversationInvocation(prompt, emptyList(), voiceAudio = voiceAudio)
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
            backend, reply, "original submitted prompt", imageBytes, directAudio,
            AssistantStreamFilter { }, null,
            ConversationInferenceTelemetry(invocation, callbacks, reply, diagnostics),
            visualContextInjected = visualContextInjected)
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? = null
            override fun isInsufficientAnswer(answer: String) = answer.isBlank()
        }
        val recovery = ConversationRecovery({ resets++ }, references, FactualityVerifier(), { "automatic question" },
            diagnostics)
        fun draft(text: String) = ConversationDraft(answer, GenerationResult(text, 1L, null), true)
    }

    private class ScriptedRecoveryBackend : ConversationBackend {
        data class Request(val prompt: String, val audio: ByteArray?, val image: ByteArray?)
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
        private fun next(prompt: String, audio: ByteArray?, image: ByteArray?,
                         emit: (String) -> Unit): GenerationResult {
            requests += Request(prompt, audio, image)
            check(outputs.isNotEmpty()) { "Unexpected recovery inference" }
            return outputs.removeFirst().also { emit(it.text) }
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = next(prompt, null, null, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, null, imageBytes, onToken)
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) =
            next(prompt, audioBytes, null, onToken)
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>,
                                             onToken: (String) -> Unit): GenerationResult =
            error("Recovery must never dispatch tools")
    }

    @Test fun recoveryBackstopRegeneratesAClaimWithTheCorrection() = runBlocking {
        val f = RecoveryFixture(VISION_QUESTION)
        f.backend.outputs += GenerationResult(HONEST_REGEN, 1L, null)
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertTrue(f.backend.requests.single().prompt.contains("cannot see anything right now"))
        assertEquals(listOf(false), f.backend.tools)
        assertEquals(1, f.resets)
        assertEquals(HONEST_REGEN, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopFallsBackWhenTheRegenerationClaimsAgain() = runBlocking {
        val f = RecoveryFixture(VISION_QUESTION)
        f.backend.outputs += GenerationResult(DEVICE_CLAIM, 1L, null)
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopResubmitsAudioOnDirectAudioTurns() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(VISION_QUESTION, directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult(HONEST_REGEN, 1L, null)
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertSame(audio, f.backend.requests.single().audio)
        assertEquals(HONEST_REGEN, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesAnHonestAnswerAlone() = runBlocking {
        val f = RecoveryFixture(VISION_QUESTION)
        val draft = f.draft(HONEST_REGEN)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(HONEST_REGEN, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesAnImageTurnAlone() = runBlocking {
        val f = RecoveryFixture(VISION_QUESTION, imageBytes = byteArrayOf(1, 2, 3))
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        // A turn with an image is never guarded, even if the answer is
        // wrong — bad recognition is a different problem.
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(DEVICE_CLAIM, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesAVisualContextTurnAlone() = runBlocking {
        val f = RecoveryFixture(VISION_QUESTION, visualContextInjected = true)
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(DEVICE_CLAIM, draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesTheEarlierPhotoFollowUpAlone() = runBlocking {
        // The earlier turn had the image; this turn only refers to it.
        // The honest behavior is to let the anchored reference stand.
        val f = RecoveryFixture("What else is in the photo I sent you?")
        val draft = f.draft("In the photo you sent, I can see a desk by the window.")
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("In the photo you sent, I can see a desk by the window.", draft.cleanedResponse)
    }

    @Test fun recoveryBackstopLeavesAClaimOnANonVisionQuestionAlone() = runBlocking {
        val f = RecoveryFixture("Tell me something useful.")
        val draft = f.draft(DEVICE_CLAIM)
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals(DEVICE_CLAIM, draft.cleanedResponse)
    }

    // ---- direct-audio turns: the build-1697 specimens ----
    //
    // On a direct-audio turn the invocation prompt is the fixed audio
    // boilerplate (GemmaAudioInputPolicy.REQUEST): the user's words
    // exist only as audio, Whisper produced no transcript, and the Gemma
    // caption finalizes after the answer. The old question-only gate
    // read the boilerplate, matched nothing, and released both specimen
    // claims below without a single diagnostics line. These tests replay
    // that call (443f75e8) through the production wiring: the claim side
    // must admit the guard, and the evaluation must be observable.

    @Test fun concreteDetectorCatchesBothDeviceSpecimensVerbatim() {
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(SPECIMEN_ONE))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(SPECIMEN_TWO))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(DEVICE_CLAIM))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(
            "The camera shows a hallway."))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(
            "A room with a window and a desk."))
    }

    @Test fun detectorsCatchThe1771PhoneScreenSpecimenVerbatim() {
        // Each sentence of the build-1771 specimen carries its own
        // evasion, so each is pinned on its own as well as together.
        assertTrue(VisionPerceptionClaim.assertsPerception(SPECIMEN_PHONE_SCREEN))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(SPECIMEN_PHONE_SCREEN))
        assertTrue(VisionPerceptionClaim.assertsPerception("I see your phone screen."))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception("I see your phone screen."))
        assertTrue(VisionPerceptionClaim.assertsPerception(
            "It is currently displaying a settings screen."))
        assertTrue(VisionPerceptionClaim.assertsConcretePerception(
            "It is currently displaying a settings screen."))
        assertEquals(VisionPerceptionClaim.Trigger.DIRECT_AUDIO_CLAIM,
            VisionPerceptionClaim.triggerFor(GemmaAudioInputPolicy.REQUEST, true,
                SPECIMEN_PHONE_SCREEN))
    }

    @Test fun detectorsLeavePossessiveAndDisplayNonClaimsAlone() {
        val negatives = listOf(
            // The possessive widening reaches only a concrete thing
            // seen: "your point" names none, and in "your message about
            // the screen repair" the scene noun sits three words out.
            "I see your point about the budget.",
            "I see your message about the screen repair.",
            "I can see your reasoning on the window replacement.",
            // A pronoun-subject display sentence about data, not a
            // perceived scene: no scene noun follows the article.
            "It is currently showing the quarterly results.",
            "This shows the way the totals break down."
        )
        for (answer in negatives) {
            assertFalse("expected no perception claim: $answer",
                VisionPerceptionClaim.assertsPerception(answer))
            assertFalse("expected no concrete perception claim: $answer",
                VisionPerceptionClaim.assertsConcretePerception(answer))
        }
    }

    @Test fun concreteDetectorLeavesMetaphoricalSeeAlone() {
        val negatives = listOf(
            // Metaphorical seeing carries no visual content noun.
            "I see what you mean.",
            "I see your point about the budget.",
            "I see the point.",
            // A perception claim with no pictured/shaped/scened thing:
            // the question-side gate still guards it when the question is
            // known (see answerDetectorCatchesPerceptionVariants), but
            // the claim side alone does not admit the guard.
            "I see you, sir.",
            VisionPerceptionClaim.FALLBACK,
            HONEST_REGEN,
            // Anchored to an image the user actually supplied.
            "In the photo you sent, I can see a desk by the window.",
            ""
        )
        for (answer in negatives) {
            assertFalse("expected no concrete perception claim: $answer",
                VisionPerceptionClaim.assertsConcretePerception(answer))
        }
    }

    @Test fun triggerForAdmitsQuestionThenConcreteClaim() {
        assertEquals(VisionPerceptionClaim.Trigger.QUESTION,
            VisionPerceptionClaim.triggerFor(VISION_QUESTION, false, DEVICE_CLAIM))
        assertEquals(VisionPerceptionClaim.Trigger.DIRECT_AUDIO_CLAIM,
            VisionPerceptionClaim.triggerFor(GemmaAudioInputPolicy.REQUEST, true, SPECIMEN_ONE))
        assertEquals(VisionPerceptionClaim.Trigger.DIRECT_AUDIO_CLAIM,
            VisionPerceptionClaim.triggerFor(GemmaAudioInputPolicy.REQUEST, true, SPECIMEN_TWO))
        // The boilerplate prompt is not a question the detector can read;
        // without the direct-audio claim side both specimens walk through.
        assertFalse(VisionPerceptionClaim.isVisionDirectedQuestion(GemmaAudioInputPolicy.REQUEST))
        // A known, non-vision question is never admitted by the claim side.
        assertNull(VisionPerceptionClaim.triggerFor("What's the capital of France?", false, DEVICE_CLAIM))
        // The claim side requires the direct-audio turn shape.
        assertNull(VisionPerceptionClaim.triggerFor(GemmaAudioInputPolicy.REQUEST, false, SPECIMEN_ONE))
        assertNull(VisionPerceptionClaim.triggerFor(GemmaAudioInputPolicy.REQUEST, true,
            "I see what you mean."))
    }

    /** Direct-audio generation fixture, modeled on AudioAccessRefusalTest's. */
    private suspend fun generateDirectAudio(
        backend: ScriptedBackend,
        history: List<ChatEntry> = emptyList(),
        summaryLines: MutableList<String> = mutableListOf()
    ): ConversationDraft {
        val diagnostics = ConversationDiagnostics({}, {}, { summaryLines += it }, {})
        val memory = MemoryDeliveryFence()
        val audio = byteArrayOf(9, 8, 7)
        val input = ConversationInvocation(GemmaAudioInputPolicy.REQUEST, history,
            voiceAudio = audio, directVoiceAudio = true)
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
            PreparedConversation(history, null, null, null, false, false, null), history,
            ConversationPrompt(builder, true, { null }, { null }), backend, reply, actions,
            ConversationInferenceTelemetry(input, callbacks, reply, diagnostics))
    }

    @Test fun directAudioSpecimenOneIsRegeneratedWithTheClaimNamed() = runBlocking {
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(
            GenerationResult(SPECIMEN_ONE, 1, null),
            GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.last().prompt.contains("cannot see anything right now"))
        assertEquals(listOf(true, false), backend.tools)
        assertEquals(HONEST_REGEN, draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=regenerated stage=generation " +
            "trigger=direct_audio_claim") })
    }

    @Test fun directAudioSpecimenOneRepeatedClaimFallsBack() = runBlocking {
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(
            GenerationResult(SPECIMEN_ONE, 1, null),
            GenerationResult(SPECIMEN_ONE, 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(2, backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=fallback stage=generation " +
            "trigger=direct_audio_claim") })
    }

    @Test fun directAudioSpecimenTwoAfterFabricatedHistoryIsRegenerated() = runBlocking {
        // The second turn of the device call: the fabricated turn-1
        // exchange sits in the dialogue, the spoken question is again
        // only audio, and the model repeats the fabrication, shortened.
        val history = listOf(
            ChatEntry("You", "Can you tell me what you see?"),
            ChatEntry("Jarvis", SPECIMEN_ONE))
        val backend = ScriptedBackend(listOf(
            GenerationResult(SPECIMEN_TWO, 1, null),
            GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generateDirectAudio(backend, history = history)
        assertEquals(2, backend.requests.size)
        assertEquals(HONEST_REGEN, draft.generated.text)
        assertFalse(VisionPerceptionClaim.assertsPerception(draft.generated.text))
    }

    @Test fun directAudioToolRetryClaimIsCheckedAndRegenerated() = runBlocking {
        // The device turn 1 shape: the first generation is a tool call
        // that does not match the spoken intent, and the claim arrives
        // in the tool-less retry's draft. The guard runs after the retry,
        // on the draft that would be published.
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(
            GenerationResult("", 1, null, toolCalls = listOf(ToolCall("open_app", "{}"))),
            GenerationResult(SPECIMEN_ONE, 1, null),
            GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(3, backend.requests.size)
        assertEquals(listOf(true, false, false), backend.tools)
        assertTrue(lines.any { it.contains("does_not_match_current_intent") })
        assertEquals(HONEST_REGEN, draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=regenerated stage=generation " +
            "trigger=direct_audio_claim") })
    }

    @Test fun directAudio1771SpecimenIsRegeneratedWithTheClaimNamed() = runBlocking {
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(
            GenerationResult(SPECIMEN_PHONE_SCREEN, 1, null),
            GenerationResult(HONEST_REGEN, 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(2, backend.requests.size)
        assertTrue(backend.requests.last().prompt.contains("cannot see anything right now"))
        assertEquals(HONEST_REGEN, draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=regenerated stage=generation " +
            "trigger=direct_audio_claim") })
    }

    @Test fun directAudio1771SpecimenRepeatedClaimFallsBack() = runBlocking {
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(
            GenerationResult(SPECIMEN_PHONE_SCREEN, 1, null),
            GenerationResult(SPECIMEN_PHONE_SCREEN, 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(2, backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=fallback stage=generation " +
            "trigger=direct_audio_claim") })
    }

    @Test fun directAudioMetaphoricalSeePassesUntouched() = runBlocking {
        val backend = ScriptedBackend(listOf(
            GenerationResult("I see what you mean, sir.", 1, null)))
        val draft = generateDirectAudio(backend)
        assertEquals(1, backend.requests.size)
        assertEquals("I see what you mean, sir.", draft.generated.text)
    }

    @Test fun directAudioNonConcreteClaimIsRecordedNotFired() = runBlocking {
        // A perception claim with no visual content: the claim side does
        // not admit the guard, but the evaluation is on the record —
        // the build-1697 export's total silence is part of the defect.
        val lines = mutableListOf<String>()
        val backend = ScriptedBackend(listOf(GenerationResult("I see you, sir.", 1, null)))
        val draft = generateDirectAudio(backend, summaryLines = lines)
        assertEquals(1, backend.requests.size)
        assertEquals("I see you, sir.", draft.generated.text)
        assertTrue(lines.any { it.contains("outcome=not_fired stage=generation " +
            "reason=claim_not_concrete") })
    }

    @Test fun recoveryBackstopFiresOnDirectAudioSpecimen() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(GemmaAudioInputPolicy.REQUEST, directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult(HONEST_REGEN, 1L, null)
        val draft = f.draft(SPECIMEN_ONE)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertSame(audio, f.backend.requests.single().audio)
        assertEquals(HONEST_REGEN, draft.cleanedResponse)
        assertTrue(f.diagnosticLines.any { it.contains("outcome=regenerated stage=recovery " +
            "trigger=direct_audio_claim") })
    }

    @Test fun recoveryBackstopFallsBackOnDirectAudioSpecimen() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(GemmaAudioInputPolicy.REQUEST, directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult(SPECIMEN_TWO, 1L, null)
        val draft = f.draft(SPECIMEN_ONE)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertEquals(VisionPerceptionClaim.FALLBACK, draft.cleanedResponse)
        assertTrue(f.diagnosticLines.any { it.contains("outcome=fallback stage=recovery " +
            "trigger=direct_audio_claim") })
    }

    @Test fun recoveryBackstopFiresOnDirectAudio1771Specimen() = runBlocking {
        val audio = byteArrayOf(3, 2, 1)
        val f = RecoveryFixture(GemmaAudioInputPolicy.REQUEST, directAudio = true, voiceAudio = audio)
        f.backend.outputs += GenerationResult(HONEST_REGEN, 1L, null)
        val draft = f.draft(SPECIMEN_PHONE_SCREEN)
        f.recovery.recover(draft)
        assertEquals(1, f.backend.requests.size)
        assertSame(audio, f.backend.requests.single().audio)
        assertEquals(HONEST_REGEN, draft.cleanedResponse)
        assertTrue(f.diagnosticLines.any { it.contains("outcome=regenerated stage=recovery " +
            "trigger=direct_audio_claim") })
    }

    @Test fun recoveryBackstopLeavesDirectAudioMetaphorAlone() = runBlocking {
        val f = RecoveryFixture(GemmaAudioInputPolicy.REQUEST, directAudio = true,
            voiceAudio = byteArrayOf(1))
        val draft = f.draft("I see what you mean, sir.")
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("I see what you mean, sir.", draft.cleanedResponse)
    }

    @Test fun recoveryBackstopRecordsNonConcreteClaimOnDirectAudio() = runBlocking {
        val f = RecoveryFixture(GemmaAudioInputPolicy.REQUEST, directAudio = true,
            voiceAudio = byteArrayOf(1))
        val draft = f.draft("I see you, sir.")
        f.recovery.recover(draft)
        assertTrue(f.backend.requests.isEmpty())
        assertEquals("I see you, sir.", draft.cleanedResponse)
        assertTrue(f.diagnosticLines.any { it.contains("outcome=not_fired stage=recovery " +
            "reason=claim_not_concrete") })
    }
}
