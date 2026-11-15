package com.battlesbudz.jarvis.v2.harness

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bundledFiles(compactText: String): Map<String, String> {
        val defaults = HarnessConfig.DEFAULT
        return mapOf(
            HarnessConfigParser.FILE_INSTRUCTIONS to instructionsWith(compactText),
            HarnessConfigParser.FILE_VOICE_INSTRUCTIONS to defaults.voiceInstructions + "\n",
            HarnessConfigParser.FILE_FORMATTER to FORMATTER_JSON,
            HarnessConfigParser.FILE_TOOL_DESCRIPTIONS to "{}",
            HarnessConfigParser.FILE_HISTORY_POLICY to HISTORY_JSON,
            HarnessConfigParser.FILE_MEMORY_EVAL to """{"saveRelevance": 30, "saveConfidence": 60}"""
        )
    }

    private fun store(root: File, bundled: Map<String, String>): HarnessStore =
        HarnessStore(root) { name -> bundled[name] }

    @Test fun noPointerUsesBundledFiles() {
        val root = temp.newFolder("harness")
        val config = store(root, bundledFiles("bundled wording")).current()
        assertEquals("bundled wording", config.instructions.compactText)
        assertEquals(1, config.version)
    }

    @Test fun pointerSelectsLiveVersion() {
        val root = temp.newFolder("harness")
        val v2 = File(root, "v2").apply { mkdirs() }
        File(v2, HarnessConfigParser.FILE_INSTRUCTIONS).writeText(instructionsWith("live wording"))
        File(root, HarnessStore.POINTER_FILE).writeText("v2\n")
        val config = store(root, bundledFiles("bundled wording")).current()
        assertEquals("live wording", config.instructions.compactText)
        assertEquals(2, config.version)
        // Components missing from v2 fall back to the bundled files.
        assertEquals(0.8f, config.formatter.containmentThreshold)
    }

    @Test fun corruptLiveFileFallsBackPerComponent() {
        val root = temp.newFolder("harness")
        val v2 = File(root, "v2").apply { mkdirs() }
        File(v2, HarnessConfigParser.FILE_INSTRUCTIONS).writeText(instructionsWith("live wording"))
        File(v2, HarnessConfigParser.FILE_FORMATTER).writeText("{not json")
        File(root, HarnessStore.POINTER_FILE).writeText("v2")
        val config = store(root, bundledFiles("bundled wording")).current()
        assertEquals("live wording", config.instructions.compactText)
        assertEquals(0.5f, config.formatter.serverConfidence)
    }

    @Test fun missingPointerTargetFallsBackToBundled() {
        val root = temp.newFolder("harness")
        File(root, HarnessStore.POINTER_FILE).writeText("v9")
        val config = store(root, bundledFiles("bundled wording")).current()
        assertEquals("bundled wording", config.instructions.compactText)
    }

    @Test fun nothingAnywhereYieldsCompiledDefaults() {
        val root = temp.newFolder("harness")
        val config = HarnessStore(root) { null }.current()
        assertEquals(HarnessConfig.DEFAULT, config)
    }

    private fun instructionsWith(compactText: String): String {
        val d = HarnessConfig.DEFAULT.instructions
        return listOf(
            "=== MAIN_TEMPLATE ===", d.mainTemplate,
            "=== COMPACT_TEXT ===", compactText,
            "=== DIALOGUE_RECALL_INSTRUCTION ===", d.dialogueRecallInstruction,
            "=== TOOL_RESULT_TEMPLATE ===", d.toolResultTemplate,
            "=== STATUS_SUCCEEDED ===", d.statusSucceeded,
            "=== STATUS_FAILED ===", d.statusFailed,
            "=== STATUS_REJECTED_VALIDATION ===", d.statusRejectedValidation,
            "=== STATUS_DENIED_PERMISSION ===", d.statusDeniedPermission,
            "=== STATUS_UNKNOWN_COMPLETION ===", d.statusUnknownCompletion,
            "=== STATUS_NEEDS_UNLOCK ===", d.statusNeedsUnlock,
            "=== SUPPORTING_CONTEXT_PREFIX ===", d.supportingContextPrefix,
            "=== SUPPORTING_CONTEXT_SUFFIX ===", d.supportingContextSuffix,
            "=== COMPACT_CONTEXT_TAIL_CHARS ===", d.compactContextTailChars.toString()
        ).joinToString("\n") + "\n"
    }

    private companion object {
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
