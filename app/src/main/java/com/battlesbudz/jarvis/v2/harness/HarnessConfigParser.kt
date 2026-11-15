package com.battlesbudz.jarvis.v2.harness

import org.json.JSONObject

/**
 * Parses and validates the editable harness files (Phase 1). Every parser
 * is total: malformed, missing, or out-of-range content returns null for
 * that component, and [resolve] falls back per component — live files
 * first, then the shipped (bundled) files, then the compiled-in
 * [HarnessConfig.DEFAULT]. A bad harness file must never crash or wedge a
 * conversation; it just loses to the defaults.
 */
object HarnessConfigParser {
    const val FILE_INSTRUCTIONS = "instructions.txt"
    const val FILE_VOICE_INSTRUCTIONS = "voice_instructions.txt"
    const val FILE_FORMATTER = "formatter.json"
    const val FILE_TOOL_DESCRIPTIONS = "tool_descriptions.json"
    const val FILE_HISTORY_POLICY = "history_policy.json"
    const val FILE_MEMORY_EVAL = "memory_eval.json"

    val FILES: List<String> = listOf(
        FILE_INSTRUCTIONS, FILE_VOICE_INSTRUCTIONS, FILE_FORMATTER,
        FILE_TOOL_DESCRIPTIONS, FILE_HISTORY_POLICY, FILE_MEMORY_EVAL
    )

    private val SECTION_HEADER = Regex("^=== ([A-Z0-9_]+) ===$")

    /** Sectioned plain-text format: `=== NAME ===` marker lines, content verbatim after. */
    fun parseSections(text: String): Map<String, String> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (rawLine in text.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            val header = SECTION_HEADER.matchEntire(line)
            if (header != null) {
                current = sections.getOrPut(header.groupValues[1]) { mutableListOf() }
            } else {
                current?.add(line)
            }
        }
        return sections.mapValues { (_, lines) ->
            lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
        }
    }

    fun parseInstructions(text: String?): HarnessConfig.InstructionsConfig? {
        if (text == null) return null
        val sections = parseSections(text)
        fun required(name: String): String? = sections[name]?.takeIf { it.isNotBlank() }
        val mainTemplate = required("MAIN_TEMPLATE") ?: return null
        val toolTemplate = required("TOOL_RESULT_TEMPLATE") ?: return null
        for (token in listOf(HarnessConfig.TOKEN_SESSION_CONTEXT, HarnessConfig.TOKEN_MEMORY_SECTION,
                HarnessConfig.TOKEN_DIALOGUE_INSTRUCTION, HarnessConfig.TOKEN_USER_PROMPT,
                HarnessConfig.TOKEN_ACTION_CONTEXT)) {
            if (token !in mainTemplate) return null
        }
        for (token in listOf(HarnessConfig.TOKEN_USER_PROMPT, HarnessConfig.TOKEN_TOOL_NAME,
                HarnessConfig.TOKEN_STATUS_TEXT, HarnessConfig.TOKEN_RESULT_MESSAGE)) {
            if (token !in toolTemplate) return null
        }
        val tail = sections["COMPACT_CONTEXT_TAIL_CHARS"]?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..100_000 } ?: return null
        return HarnessConfig.InstructionsConfig(
            mainTemplate = mainTemplate,
            compactText = required("COMPACT_TEXT") ?: return null,
            dialogueRecallInstruction = required("DIALOGUE_RECALL_INSTRUCTION") ?: return null,
            toolResultTemplate = toolTemplate,
            statusSucceeded = required("STATUS_SUCCEEDED") ?: return null,
            statusFailed = required("STATUS_FAILED") ?: return null,
            statusRejectedValidation = required("STATUS_REJECTED_VALIDATION") ?: return null,
            statusDeniedPermission = required("STATUS_DENIED_PERMISSION") ?: return null,
            statusUnknownCompletion = required("STATUS_UNKNOWN_COMPLETION") ?: return null,
            statusNeedsUnlock = required("STATUS_NEEDS_UNLOCK") ?: return null,
            supportingContextPrefix = required("SUPPORTING_CONTEXT_PREFIX") ?: return null,
            supportingContextSuffix = required("SUPPORTING_CONTEXT_SUFFIX") ?: return null,
            compactContextTailChars = tail
        )
    }

    fun parseVoiceInstructions(text: String?): String? {
        if (text == null) return null
        val content = text.removeSuffix("\n").removeSuffix("\r")
        return content.takeIf { it.isNotBlank() }
    }

    fun parseFormatter(text: String?): HarnessConfig.FormatterConfig? {
        val json = jsonObject(text) ?: return null
        return HarnessConfig.FormatterConfig(
            containmentThreshold = json.reqFloat("containmentThreshold")?.takeIf { it in 0f..1f } ?: return null,
            serverConfidence = json.reqFloat("serverConfidence")?.takeIf { it in 0f..1f } ?: return null,
            emitSegmentsLine = json.reqBoolean("emitSegmentsLine") ?: return null,
            emitSceneLine = json.reqBoolean("emitSceneLine") ?: return null,
            emitCountsLine = json.reqBoolean("emitCountsLine") ?: return null,
            emitPeopleCountLine = json.reqBoolean("emitPeopleCountLine") ?: return null,
            nativeHeader = json.reqString("nativeHeader") ?: return null,
            serverHeader = json.reqString("serverHeader") ?: return null,
            failureNote = json.reqString("failureNote") ?: return null,
            summaryEmpty = json.reqString("summaryEmpty") ?: return null,
            summaryPrefix = json.reqString("summaryPrefix") ?: return null,
            summarySuffix = json.reqString("summarySuffix") ?: return null,
            objectsLinePrefix = json.reqString("objectsLinePrefix") ?: return null,
            objectsLineJoiner = json.reqString("objectsLineJoiner") ?: return null,
            noObjectsText = json.reqString("noObjectsText") ?: return null,
            countsLinePrefix = json.reqString("countsLinePrefix") ?: return null,
            peopleCountPrefix = json.reqString("peopleCountPrefix") ?: return null,
            segmentsLinePrefix = json.reqString("segmentsLinePrefix") ?: return null,
            peopleLinePrefix = json.reqString("peopleLinePrefix") ?: return null,
            sceneLinePrefix = json.reqString("sceneLinePrefix") ?: return null
        )
    }

    fun parseToolDescriptions(text: String?): Map<String, String>? {
        val json = jsonObject(text) ?: return null
        if (json.length() > 256) return null
        val out = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key) as? String ?: return null
            if (value.isBlank() || value.length > 4_000) return null
            out[key] = value
        }
        return out
    }

    fun parseHistoryPolicy(text: String?): HarnessConfig.HistoryPolicyConfig? {
        val json = jsonObject(text) ?: return null
        fun field(name: String, range: IntRange): Int? =
            json.reqInt(name)?.takeIf { it in range }
        return HarnessConfig.HistoryPolicyConfig(
            recentEntryLimit = field("recentEntryLimit", 1..64) ?: return null,
            summaryCharacterLimit = field("summaryCharacterLimit", 100..20_000) ?: return null,
            userEntryChars = field("userEntryChars", 1..10_000) ?: return null,
            assistantEntryChars = field("assistantEntryChars", 1..10_000) ?: return null,
            topicAnchorChars = field("topicAnchorChars", 1..10_000) ?: return null,
            contextCapChars = field("contextCapChars", 100..50_000) ?: return null,
            compactSnapshotTailChars = field("compactSnapshotTailChars", 1..20_000) ?: return null,
            compactSnapshotSummaryTailChars = field("compactSnapshotSummaryTailChars", 1..10_000) ?: return null
        )
    }

    fun parseMemoryEval(text: String?): HarnessConfig.MemoryEvalConfig? {
        val json = jsonObject(text) ?: return null
        return HarnessConfig.MemoryEvalConfig(
            saveRelevance = json.reqInt("saveRelevance")?.takeIf { it in 0..100 } ?: return null,
            saveConfidence = json.reqInt("saveConfidence")?.takeIf { it in 0..100 } ?: return null
        )
    }

    /**
     * Compose the effective config: each component comes from the live
     * files when they parse, else from the bundled files, else from the
     * compiled-in defaults.
     */
    fun resolve(version: Int, live: Map<String, String?>, bundled: Map<String, String?>): HarnessConfig {
        val defaults = HarnessConfig.DEFAULT
        return HarnessConfig(
            version = version,
            instructions = parseInstructions(live[FILE_INSTRUCTIONS])
                ?: parseInstructions(bundled[FILE_INSTRUCTIONS]) ?: defaults.instructions,
            voiceInstructions = parseVoiceInstructions(live[FILE_VOICE_INSTRUCTIONS])
                ?: parseVoiceInstructions(bundled[FILE_VOICE_INSTRUCTIONS]) ?: defaults.voiceInstructions,
            formatter = parseFormatter(live[FILE_FORMATTER])
                ?: parseFormatter(bundled[FILE_FORMATTER]) ?: defaults.formatter,
            toolDescriptions = parseToolDescriptions(live[FILE_TOOL_DESCRIPTIONS])
                ?: parseToolDescriptions(bundled[FILE_TOOL_DESCRIPTIONS]) ?: defaults.toolDescriptions,
            historyPolicy = parseHistoryPolicy(live[FILE_HISTORY_POLICY])
                ?: parseHistoryPolicy(bundled[FILE_HISTORY_POLICY]) ?: defaults.historyPolicy,
            memoryEval = parseMemoryEval(live[FILE_MEMORY_EVAL])
                ?: parseMemoryEval(bundled[FILE_MEMORY_EVAL]) ?: defaults.memoryEval
        )
    }

    private fun jsonObject(text: String?): JSONObject? {
        if (text == null) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun JSONObject.reqString(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONObject.reqInt(key: String): Int? = when (val value = opt(key)) {
        is Number -> value.toInt().takeIf { it.toDouble() == value.toDouble() }
        else -> null
    }

    private fun JSONObject.reqFloat(key: String): Float? =
        (opt(key) as? Number)?.toFloat()

    private fun JSONObject.reqBoolean(key: String): Boolean? = opt(key) as? Boolean
}
