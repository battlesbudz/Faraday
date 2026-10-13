package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.actions.NativeActionDecoder
import com.battlesbudz.jarvis.v2.ai.ToolCall
import kotlin.math.roundToInt

/**
 * JVM-pure scorer for the tool-call reliability suite (M8 early enabler).
 *
 * Scoring reuses the production strict boundary
 * (NativeActionDecoder.decodeStrict -> MobileToolCatalog.decodeStrict) — the
 * same entry ActionTurnRunner.validateBatch uses before any dispatch. A case
 * passes only when the model produced exactly one call, named the expected
 * tool, and its arguments survived strict decoding with values equal to the
 * fixture's expected arguments. No production behavior is modified here; this
 * only measures what the model emitted.
 */
data class CaseResult(
    val fixtureId: String,
    val utterance: String,
    val expectedTool: String,
    val expectedArguments: Map<String, String>,
    val actualCalls: List<ToolCall>,
    val correctTool: Boolean,
    val argumentsValid: Boolean,
    val noExtraCalls: Boolean
) {
    val passed: Boolean get() = correctTool && argumentsValid && noExtraCalls
}

data class ToolScore(
    val toolName: String,
    val total: Int,
    val passed: Int
) {
    val percent: Double get() = if (total == 0) 0.0 else passed * 100.0 / total
}

data class ModelReport(
    val modelId: String,
    val caseResults: List<CaseResult>,
    val toolScores: List<ToolScore>,
    /**
     * Identity of the actual model file measured. Scores are only presented
     * for this exact file; a replaced or deleted weight file invalidates them.
     */
    val modelFingerprint: String? = null,
    /**
     * Fixture/scorer version measured with. Scores are only presented for the
     * current suite version; changed fixtures or scoring invalidate them.
     */
    val suiteVersion: Int = ToolReliabilityFixtures.SUITE_VERSION
) {
    val total: Int get() = caseResults.size
    val passed: Int get() = caseResults.count { it.passed }
    val percent: Double get() = if (total == 0) 0.0 else passed * 100.0 / total
    val failedCases: List<CaseResult> get() = caseResults.filterNot { it.passed }

    /**
     * Plain-text summary, e.g.:
     *   E4B: 93% (26/28)
     *     read_battery: 100% (2/2)
     *     ...
     *     FAIL set_volume_2: expected set_volume, got [set_volume {"level":999}]
     */
    fun renderSummary(): String = buildString {
        appendLine("$modelId: ${percent.roundToInt()}% ($passed/$total)")
        toolScores.sortedBy { it.toolName }.forEach { score ->
            appendLine("  ${score.toolName}: ${score.percent.roundToInt()}% (${score.passed}/${score.total})")
        }
        failedCases.forEach { failed ->
            val actual = failed.actualCalls
                .joinToString("; ") { call -> "${call.name} ${call.arguments}" }
                .ifEmpty { "<no tool call>" }
            appendLine("  FAIL ${failed.fixtureId}: expected ${failed.expectedTool}, got [$actual]")
        }
    }
}

object ToolReliabilityScorer {
    fun scoreCase(fixture: ReliabilityFixture, actualCalls: List<ToolCall>): CaseResult {
        val noExtraCalls = actualCalls.size == 1
        val first = actualCalls.firstOrNull()
        val correctTool = first != null && first.name == fixture.expectedTool
        val decoded = first?.let { NativeActionDecoder.decodeStrict(it) }
        val argumentsValid = decoded != null &&
            decoded.name == fixture.expectedTool &&
            decoded.arguments == fixture.expectedArguments
        return CaseResult(
            fixtureId = fixture.id,
            utterance = fixture.utterance,
            expectedTool = fixture.expectedTool,
            expectedArguments = fixture.expectedArguments,
            actualCalls = actualCalls,
            correctTool = correctTool,
            argumentsValid = argumentsValid,
            noExtraCalls = noExtraCalls
        )
    }

    suspend fun scoreModel(
        modelId: String,
        fixtures: List<ReliabilityFixture>,
        runner: ToolCallRunner,
        modelFingerprint: String? = null,
        suiteVersion: Int = ToolReliabilityFixtures.SUITE_VERSION
    ): ModelReport {
        val results = fixtures.map { fixture ->
            scoreCase(fixture, runner.runUtterance(modelId, fixture.utterance))
        }
        val toolScores = results
            .groupBy { it.expectedTool }
            .map { (tool, toolResults) -> ToolScore(tool, toolResults.size, toolResults.count { it.passed }) }
        return ModelReport(modelId, results, toolScores, modelFingerprint, suiteVersion)
    }
}
