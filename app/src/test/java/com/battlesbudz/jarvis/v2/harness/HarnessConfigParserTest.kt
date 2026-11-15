package com.battlesbudz.jarvis.v2.harness

import org.junit.Assert.*
import org.junit.Test

class HarnessConfigParserTest {
    private fun instructionsText(mainTemplate: String = VALID_MAIN, tail: String = "600"): String =
        listOf(
            "=== MAIN_TEMPLATE ===", mainTemplate,
            "=== COMPACT_TEXT ===", "compact wording",
            "=== DIALOGUE_RECALL_INSTRUCTION ===", "recall wording",
            "=== TOOL_RESULT_TEMPLATE ===", VALID_TOOL,
            "=== STATUS_SUCCEEDED ===", "succeeded",
            "=== STATUS_FAILED ===", "failed",
            "=== STATUS_REJECTED_VALIDATION ===", "rejected",
            "=== STATUS_DENIED_PERMISSION ===", "denied",
            "=== STATUS_UNKNOWN_COMPLETION ===", "unknown",
            "=== STATUS_NEEDS_UNLOCK ===", "unlock",
            "=== SUPPORTING_CONTEXT_PREFIX ===", "[prefix]",
            "=== SUPPORTING_CONTEXT_SUFFIX ===", "[suffix]",
            "=== COMPACT_CONTEXT_TAIL_CHARS ===", tail
        ).joinToString("\n") + "\n"

    @Test fun validInstructionsParse() {
        val parsed = HarnessConfigParser.parseInstructions(instructionsText())
        assertNotNull(parsed)
        assertEquals("compact wording", parsed!!.compactText)
        assertEquals(600, parsed.compactContextTailChars)
        assertEquals("[prefix]", parsed.supportingContextPrefix)
    }

    @Test fun instructionsMissingATokenAreRejected() {
        val broken = VALID_MAIN.replace(HarnessConfig.TOKEN_USER_PROMPT, "USER")
        assertNull(HarnessConfigParser.parseInstructions(instructionsText(mainTemplate = broken)))
    }

    @Test fun instructionsWithBadTailAreRejected() {
        assertNull(HarnessConfigParser.parseInstructions(instructionsText(tail = "soon")))
        assertNull(HarnessConfigParser.parseInstructions(instructionsText(tail = "-5")))
    }

    @Test fun formatterValidation() {
        val valid = HarnessConfigParser.parseFormatter(FORMATTER_JSON)
        assertNotNull(valid)
        assertEquals(0.8f, valid!!.containmentThreshold)
        assertEquals(0.5f, valid.serverConfidence)
        assertTrue(valid.emitCountsLine)
        assertNull(HarnessConfigParser.parseFormatter(FORMATTER_JSON.replace("0.8", "1.5")))
        assertNull(HarnessConfigParser.parseFormatter(FORMATTER_JSON.replace("\"emitCountsLine\": true,", "")))
        assertNull(HarnessConfigParser.parseFormatter("{broken"))
        assertNull(HarnessConfigParser.parseFormatter(null))
    }

    @Test fun historyValidation() {
        val valid = HarnessConfigParser.parseHistoryPolicy(HISTORY_JSON)
        assertNotNull(valid)
        assertEquals(8, valid!!.recentEntryLimit)
        assertNull(HarnessConfigParser.parseHistoryPolicy(HISTORY_JSON.replace("\"recentEntryLimit\": 8", "\"recentEntryLimit\": 0")))
        assertNull(HarnessConfigParser.parseHistoryPolicy(HISTORY_JSON.replace("\"contextCapChars\": 3000,", "")))
    }

    @Test fun memoryEvalValidation() {
        val valid = HarnessConfigParser.parseMemoryEval("""{"saveRelevance": 30, "saveConfidence": 60}""")
        assertNotNull(valid)
        assertEquals(30, valid!!.saveRelevance)
        assertNull(HarnessConfigParser.parseMemoryEval("""{"saveRelevance": 101, "saveConfidence": 60}"""))
        assertNull(HarnessConfigParser.parseMemoryEval("""{"saveRelevance": 30}"""))
    }

    @Test fun toolDescriptionsValidation() {
        val valid = HarnessConfigParser.parseToolDescriptions("""{"open_app": "Open an app."}""")
        assertEquals(mapOf("open_app" to "Open an app."), valid)
        assertEquals(emptyMap<String, String>(), HarnessConfigParser.parseToolDescriptions("{}"))
        assertNull(HarnessConfigParser.parseToolDescriptions("""{"open_app": "  "}"""))
        assertNull(HarnessConfigParser.parseToolDescriptions("""{"open_app": 3}"""))
        assertNull(HarnessConfigParser.parseToolDescriptions("not json"))
    }

    @Test fun voiceInstructionsParsing() {
        assertEquals("be brief", HarnessConfigParser.parseVoiceInstructions("be brief\n"))
        assertEquals("be brief", HarnessConfigParser.parseVoiceInstructions("be brief"))
        assertNull(HarnessConfigParser.parseVoiceInstructions("  \n"))
        assertNull(HarnessConfigParser.parseVoiceInstructions(null))
    }

    @Test fun resolveFallsBackPerComponent() {
        val bundled = mapOf(
            HarnessConfigParser.FILE_INSTRUCTIONS to instructionsText(),
            HarnessConfigParser.FILE_FORMATTER to FORMATTER_JSON,
            HarnessConfigParser.FILE_HISTORY_POLICY to HISTORY_JSON
        )
        val live = mapOf(
            HarnessConfigParser.FILE_INSTRUCTIONS to "garbage with no sections",
            HarnessConfigParser.FILE_HISTORY_POLICY to HISTORY_JSON.replace("3000", "2500")
        )
        val resolved = HarnessConfigParser.resolve(2, live, bundled)
        assertEquals(2, resolved.version)
        // Live instructions are garbage: bundled instructions win.
        assertEquals("compact wording", resolved.instructions.compactText)
        // Live history parses: it wins over bundled.
        assertEquals(2500, resolved.historyPolicy.contextCapChars)
        // Formatter only exists bundled.
        assertEquals(0.8f, resolved.formatter.containmentThreshold)
        // Nothing anywhere for memory eval: compiled-in defaults.
        assertEquals(HarnessConfig.DEFAULT.memoryEval, resolved.memoryEval)
        assertEquals(HarnessConfig.DEFAULT.voiceInstructions, resolved.voiceInstructions)
    }

    private companion object {
        const val VALID_MAIN = "Body {{SESSION_CONTEXT}} {{MEMORY_SECTION}} {{DIALOGUE_INSTRUCTION}} " +
            "Current user message:\n{{USER_PROMPT}}\n{{ACTION_CONTEXT}}"
        const val VALID_TOOL = "Result {{USER_PROMPT}} {{TOOL_NAME}} {{STATUS_TEXT}} {{RESULT_MESSAGE}}"
        const val FORMATTER_JSON = """{
          "containmentThreshold": 0.8, "serverConfidence": 0.5,
          "emitSegmentsLine": true, "emitSceneLine": true, "emitCountsLine": true, "emitPeopleCountLine": true,
          "nativeHeader": "n {{MS}}", "serverHeader": "s {{MS}}", "failureNote": "f {{ERROR}}",
          "summaryEmpty": "e {{MS}}", "summaryPrefix": "p ", "summarySuffix": " x {{MS}}",
          "objectsLinePrefix": "Objects detected: ", "objectsLineJoiner": ". Detected objects: ",
          "noObjectsText": "none recognizable.", "countsLinePrefix": "Counts: ",
          "peopleCountPrefix": "People count: ", "segmentsLinePrefix": "Segmented outlines: ",
          "peopleLinePrefix": "People: ", "sceneLinePrefix": "Scene looks like: "
        }"""
        const val HISTORY_JSON = """{
          "recentEntryLimit": 8, "summaryCharacterLimit": 2000, "userEntryChars": 300,
          "assistantEntryChars": 450, "topicAnchorChars": 900, "contextCapChars": 3000,
          "compactSnapshotTailChars": 2800, "compactSnapshotSummaryTailChars": 1000
        }"""
    }
}
