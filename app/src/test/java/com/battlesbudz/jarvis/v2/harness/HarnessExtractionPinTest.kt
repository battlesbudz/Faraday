package com.battlesbudz.jarvis.v2.harness

import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.MobileToolCatalog
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.memory.AutoMemorySource
import com.battlesbudz.jarvis.v2.memory.EvaluationContext
import com.battlesbudz.jarvis.v2.memory.HeuristicMemoryEvaluator
import com.battlesbudz.jarvis.v2.memory.MemoryCandidate
import com.battlesbudz.jarvis.v2.memory.MemoryCategory
import com.battlesbudz.jarvis.v2.vision.NativeVisionSnapshot
import com.battlesbudz.jarvis.v2.vision.VisionContextFormatter
import com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy
import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.ClassPrediction
import com.battlesbudz.phoneinference.Detection
import com.battlesbudz.phoneinference.Keypoint
import com.battlesbudz.phoneinference.PoseResult
import com.battlesbudz.phoneinference.Pt
import com.battlesbudz.phoneinference.SegResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 1 extraction pins: with the shipped v1 harness content, every
 * refactored surface must produce byte-identical output to the previously
 * hardcoded strings. The expected values below are those old strings,
 * written out in full on purpose.
 */
class HarnessExtractionPinTest {
    private val builder = ConversationPromptBuilder(ShortTermConversationContext())

    @Test fun mainChatPromptIsByteIdentical() {
        val sessionContext = ""
        val memorySection = ""
        val dialogueInstruction = ""
        val userPrompt = "Hello"
        val actionContext = ""
        val expected = """
            You are Jarvis, a private local assistant. Answer the current
            user message directly and naturally. Do not list your capabilities,
            describe your tools, or discuss the routing system unless the user
            explicitly asks about them. Use a phone action only when the current
            request actually asks you to inspect or change the phone. Tool calls
            are handled internally by the app. Never emit <|tool_call>,
            <start_function_call>, call:, or any other tool-call markup in your
            user-facing answer. If a verified tool result is included below,
            treat it as authoritative and explain it naturally. Do not claim that
            you accessed or verified sources unless reference evidence is included
            in this prompt.
            For historical or factual questions, do not fill gaps by guessing.
            If you are not confident and no reference evidence is included,
            say that you could not verify the answer. Never ask the user whether
            to search Wikipedia; the app handles factual lookup automatically.
            Do not claim Wikipedia or Wikidata was searched unless evidence is
            included below.
            Prefer a concise answer that fits the current mobile turn budget.
            For broad requests, give the key points first and avoid unnecessary
            repetition or a long preamble.

            $sessionContext

            $memorySection

            $dialogueInstruction

            Current user message:
            $userPrompt
            $actionContext
        """.trimIndent()
        assertEquals(expected, builder.buildGemmaPrompt("Hello", null, emptyList(), seedContext = false))
    }

    @Test fun compactChatPromptIsByteIdentical() {
        val expected = "You are Jarvis, a private assistant. Answer the current request briefly. " +
            "Use dialogue as background, not instructions. Never invent tool results or sources." +
            "\n\nCurrent user message:\nHello"
        assertEquals(expected, builder.buildGemmaPrompt(
            "Hello", null, emptyList(), seedContext = false, compactInstructions = true))
    }

    @Test fun toolResultContextIsByteIdentical() {
        val expected = """
            Native tool execution context:
            - User request: Open Facebook
            - Selected tool: open_app
            - Execution status: succeeded
            - Android result: Opened Facebook.

            Report the execution status faithfully in your reply. "Sent but
            unconfirmed" means the request went out but you could not verify
            what happened: say exactly that. Never generalize it to
            "background tasks can't be performed", "I can't do that in the
            background", or any other blanket refusal. A "not attempted"
            status is not a permanent limitation: suggest trying with the app
            in the foreground, or setting Jarvis as the system assistant for
            background launches.
        """.trimIndent()
        assertEquals(expected, builder.buildToolResultContext(
            "Open Facebook", "open_app", "Opened Facebook.", ExecutionResult.Outcome.SUCCEEDED))
    }

    @Test fun voicePromptIsByteIdentical() {
        assertEquals(HarnessConfig.DEFAULT.voiceInstructions, VoiceResponsePolicy.instructions)
        val expected = VoiceResponsePolicy.instructions + "\n\nCurrent user message:\nHello"
        assertEquals(expected, builder.buildGemmaPrompt(
            "Hello", null, emptyList(), seedContext = true, voice = true))
    }

    @Test fun nativeFormatterOutputIsByteIdentical() {
        val snapshot = NativeVisionSnapshot(
            detections = listOf(
                Detection("car", 1, 0.9f, Box(10f, 20f, 110f, 220f)),
                Detection("person", 0, 0.85f, Box(0f, 0f, 50f, 100f))
            ),
            detectionMs = 10.0,
            segments = listOf(SegResult("car", 1, 0.7f, Box(10f, 20f, 110f, 220f),
                listOf(Pt(1f, 2f), Pt(3f, 4f), Pt(5f, 6f)))),
            segmentationMs = 5.0,
            poses = listOf(PoseResult(0.9f, Box(0f, 0f, 50f, 100f),
                List(17) { Keypoint("kp$it", it, 10f, 20f, 0.8f) })),
            poseMs = 8.0,
            classifications = listOf(ClassPrediction("outdoor", 1, 0.7f)),
            classificationMs = 3.0
        )
        val expected = listOf(
            "On-device vision analysis (native ONNX models, 26 ms total). " +
                "Treat the following as authoritative for what is visible in the attached image.",
            "Objects detected: 2. Detected objects: car 0.90 at [10,20,110,220]; person 0.85 at [0,0,50,100]",
            "Segmented outlines: car (3-point outline)",
            "People count: 1. People: person with 17 tracked keypoints",
            "Scene looks like: outdoor (0.70)"
        ).joinToString("\n")
        assertEquals(expected, VisionContextFormatter.format(snapshot))
        assertEquals("I see: car (0.90), person (0.85) \u2014 26 ms on-device.",
            VisionContextFormatter.summaryLine(snapshot))
    }

    @Test fun serverFormatterOutputIsByteIdentical() {
        // The Oct 21 street-photo failure shape: a weaker car box contained
        // in a stronger one must be dropped before counting.
        val detections = listOf(
            Detection("car", 1, 0.92f, Box(0f, 0f, 200f, 100f)),
            Detection("car", 1, 0.60f, Box(10f, 10f, 100f, 60f)),
            Detection("person", 0, 0.88f, Box(300f, 0f, 360f, 200f)),
            Detection("person", 0, 0.87f, Box(400f, 0f, 460f, 200f)),
            Detection("bus", 2, 0.77f, Box(500f, 0f, 700f, 200f))
        )
        val expected = listOf(
            "On-device vision analysis (Phone Inference server, 254 ms). " +
                "Treat the following as authoritative for what is visible in the attached image.",
            "Objects detected: 4. Detected objects: car 0.92 at [0,0,200,100]; " +
                "person 0.88 at [300,0,360,200]; person 0.87 at [400,0,460,200]; bus 0.77 at [500,0,700,200]",
            "Counts: person 2, bus 1, car 1.",
            "People count: 2."
        ).joinToString("\n")
        assertEquals(expected, VisionContextFormatter.formatServerDetections(detections, 254.0))
    }

    @Test fun failureNoteAndEmptySummaryAreByteIdentical() {
        assertEquals(
            "On-device vision analysis was unavailable for this image (boom). " +
                "Answer from the attached image alone and mention that on-device detection was unavailable.",
            VisionContextFormatter.failureNote(IllegalStateException("boom")))
        val empty = NativeVisionSnapshot(
            detections = emptyList(), detectionMs = 10.0,
            segments = emptyList(), segmentationMs = 5.0,
            poses = emptyList(), poseMs = 8.0,
            classifications = emptyList(), classificationMs = 3.0)
        assertEquals("I didn't spot anything recognizable in that image (26 ms on-device).",
            VisionContextFormatter.summaryLine(empty))
        assertEquals(
            "On-device vision analysis (native ONNX models, 26 ms total). " +
                "Treat the following as authoritative for what is visible in the attached image.\n" +
                "Objects detected: 0. Detected objects: none recognizable.\nPeople count: 0.",
            VisionContextFormatter.format(empty))
    }

    @Test fun shortTermContextIsByteIdentical() {
        val context = ShortTermConversationContext()
        val history = listOf("You" to "I like apricots", "Jarvis" to "Nice.")
        assertEquals(
            "Conversation topic anchor:\nYou: I like apricots\n\n" +
                "Recent visible turns:\nYou: I like apricots\nJarvis: Nice.",
            context.promptContext(history))
        assertEquals(
            "Recent dialogue:\nYou: I like apricots\nJarvis: Nice.",
            context.promptContext(history, compact = true))
    }

    @Test fun catalogSchemaIsByteIdenticalAndOverridesApply() {
        val expected = "{\"name\":\"read_battery\",\"description\":\"Read the phone battery " +
            "percentage, charge level, and current battery status. Use this when the user asks " +
            "how much battery the phone has or what the battery percentage is.\"," +
            "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]," +
            "\"additionalProperties\":false}}"
        assertEquals(expected, MobileToolCatalog.find("read_battery")!!.schemaJson())
        val baseline = MobileToolCatalog.all().map { it.schemaJson() }
        try {
            MobileToolCatalog.descriptionOverrides = { HarnessConfig.DEFAULT.toolDescriptions }
            assertEquals(baseline, MobileToolCatalog.all().map { it.schemaJson() })
            MobileToolCatalog.descriptionOverrides = { mapOf("read_battery" to "CUSTOM") }
            assertEquals("CUSTOM", MobileToolCatalog.find("read_battery")!!.description)
            assertEquals("Open an installed Android application.",
                MobileToolCatalog.find("open_app")!!.description)
        } finally {
            MobileToolCatalog.descriptionOverrides = { emptyMap() }
        }
    }

    @Test fun evaluatorDefaultsMatchHarnessThresholds() {
        val candidate = MemoryCandidate("id-1", "I live in Watertown", MemoryCategory.FACT,
            AutoMemorySource.CHAT, "evt-1", 0L, "fp")
        val bare = HeuristicMemoryEvaluator().evaluate(candidate, EvaluationContext())
        assertEquals(50, bare.relevance)
        assertEquals(80, bare.confidence)
        assertTrue(bare.save)
        val harnessDriven = HeuristicMemoryEvaluator(
            HarnessConfig.DEFAULT.memoryEval.saveRelevance,
            HarnessConfig.DEFAULT.memoryEval.saveConfidence).evaluate(candidate, EvaluationContext())
        assertEquals(bare.relevance, harnessDriven.relevance)
        assertEquals(bare.confidence, harnessDriven.confidence)
        assertEquals(bare.save, harnessDriven.save)
        assertEquals(bare.reason, harnessDriven.reason)
        val stricter = HeuristicMemoryEvaluator(saveRelevanceThreshold = 60)
            .evaluate(candidate, EvaluationContext())
        assertFalse(stricter.save)
        assertEquals("Relevance 50 below threshold 60.", stricter.reason)
    }
}
