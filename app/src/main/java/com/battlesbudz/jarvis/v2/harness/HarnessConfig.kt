package com.battlesbudz.jarvis.v2.harness

/**
 * The editable harness, as data (self-improving harness project, Phase 1).
 *
 * Everything in here used to be a hardcoded string or constant in the code
 * that consumes it. Phase 1 moves the five editable surfaces behind this
 * config with byte-identical default content ([DEFAULT]), so loading the
 * shipped v1 harness files changes no live behavior at all:
 *
 *  1. instruction wording — [InstructionsConfig] plus [voiceInstructions],
 *     consumed by ConversationPromptBuilder and VoiceResponsePolicy;
 *  2. the vision formatter — [FormatterConfig], consumed by
 *     VisionContextFormatter, ServerDetections and PhoneVisionClient;
 *  3. tool descriptions — [toolDescriptions], consumed by MobileToolCatalog
 *     (names, parameters, routing and gates are locked core, never here);
 *  4. history numbers — [HistoryPolicyConfig], consumed by
 *     ShortTermConversationContext;
 *  5. memory-eval guidance — [MemoryEvalConfig], the save thresholds
 *     consumed by HeuristicMemoryEvaluator (MemoryPolicy's restricted
 *     content rules are locked core, never here).
 *
 * The never-refuse stance, privacy rules and identity minimums are NOT in
 * this config: they stay compiled into the prompt assembly code, and the
 * editable templates are inserted around them exactly where the old
 * literals sat.
 */
data class HarnessConfig(
    /** Harness file version this config was loaded from (1 = shipped defaults). */
    val version: Int,
    val instructions: InstructionsConfig,
    val voiceInstructions: String,
    val formatter: FormatterConfig,
    val toolDescriptions: Map<String, String>,
    val historyPolicy: HistoryPolicyConfig,
    val memoryEval: MemoryEvalConfig
) {
    data class InstructionsConfig(
        /** Full text-turn template with {{...}} tokens (see [HarnessTemplate]). */
        val mainTemplate: String,
        val compactText: String,
        val dialogueRecallInstruction: String,
        /** Tool-result context template with {{...}} tokens. */
        val toolResultTemplate: String,
        val statusSucceeded: String,
        val statusFailed: String,
        val statusRejectedValidation: String,
        val statusDeniedPermission: String,
        val statusUnknownCompletion: String,
        val statusNeedsUnlock: String,
        /** Wraps the memory section on voice turns; the builder adds the newlines. */
        val supportingContextPrefix: String,
        val supportingContextSuffix: String,
        /** Tail kept from the session context on the compact-instruction paths. */
        val compactContextTailChars: Int
    )

    data class FormatterConfig(
        /** ServerDetections containment threshold (fraction of a box inside a bigger one). */
        val containmentThreshold: Float,
        /** PhoneVisionClient default confidence cutoff for server detections. */
        val serverConfidence: Float,
        val emitSegmentsLine: Boolean,
        val emitSceneLine: Boolean,
        val emitCountsLine: Boolean,
        val emitPeopleCountLine: Boolean,
        /** {{MS}} is replaced with the whole-milliseconds total. */
        val nativeHeader: String,
        val serverHeader: String,
        /** {{ERROR}} is replaced with the (truncated) failure message. */
        val failureNote: String,
        /** {{MS}} templates for the user-visible one-line summary. */
        val summaryEmpty: String,
        val summaryPrefix: String,
        val summarySuffix: String,
        val objectsLinePrefix: String,
        val objectsLineJoiner: String,
        val noObjectsText: String,
        val countsLinePrefix: String,
        val peopleCountPrefix: String,
        val segmentsLinePrefix: String,
        val peopleLinePrefix: String,
        val sceneLinePrefix: String
    )

    data class HistoryPolicyConfig(
        val recentEntryLimit: Int,
        val summaryCharacterLimit: Int,
        val userEntryChars: Int,
        val assistantEntryChars: Int,
        val topicAnchorChars: Int,
        val contextCapChars: Int,
        val compactSnapshotTailChars: Int,
        val compactSnapshotSummaryTailChars: Int
    )

    data class MemoryEvalConfig(
        val saveRelevance: Int,
        val saveConfidence: Int
    )

    companion object {
        /** The compiled-in defaults: byte-identical to the shipped v1 files. */
        val DEFAULT: HarnessConfig get() = HarnessDefaults.CONFIG

        const val TOKEN_SESSION_CONTEXT = "{{SESSION_CONTEXT}}"
        const val TOKEN_MEMORY_SECTION = "{{MEMORY_SECTION}}"
        const val TOKEN_DIALOGUE_INSTRUCTION = "{{DIALOGUE_INSTRUCTION}}"
        const val TOKEN_USER_PROMPT = "{{USER_PROMPT}}"
        const val TOKEN_ACTION_CONTEXT = "{{ACTION_CONTEXT}}"
        const val TOKEN_TOOL_NAME = "{{TOOL_NAME}}"
        const val TOKEN_STATUS_TEXT = "{{STATUS_TEXT}}"
        const val TOKEN_RESULT_MESSAGE = "{{RESULT_MESSAGE}}"
        const val TOKEN_MS = "{{MS}}"
        const val TOKEN_ERROR = "{{ERROR}}"
    }
}

/**
 * Single-pass {{TOKEN}} substitution. Inserted values are never re-scanned,
 * so user text that happens to contain a token cannot trigger a second
 * substitution round.
 */
object HarnessTemplate {
    fun render(template: String, values: Map<String, String>): String {
        if (!template.contains("{{")) return template
        val out = StringBuilder(template.length + 64)
        var i = 0
        while (i < template.length) {
            val start = template.indexOf("{{", i)
            if (start < 0) {
                out.append(template, i, template.length)
                break
            }
            val end = template.indexOf("}}", start + 2)
            if (end < 0) {
                out.append(template, i, template.length)
                break
            }
            val token = template.substring(start, end + 2)
            val value = values[token]
            if (value == null) {
                out.append(template, i, end + 2)
            } else {
                out.append(template, i, start)
                out.append(value)
            }
            i = end + 2
        }
        return out.toString()
    }
}
