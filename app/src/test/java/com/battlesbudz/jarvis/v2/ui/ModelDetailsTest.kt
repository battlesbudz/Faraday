package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

internal object ModelDetailsFixtures {
    val model = ModelCatalog.gemma4E2b
    fun submission(id: String = "submission", model: LocalModelSpec = this.model,
        ttft: Long? = null, decode: Double? = null,
        outcome: PipelineBenchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE,
        purpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.ANSWER) = PipelineBenchmarkSubmission(
        submissionId = id, purpose = purpose, outcome = outcome, modelId = model.id,
        firstTokenMs = ttft, estimatedDecodeTokensPerSecond = decode)
    fun turn(vararg submissions: PipelineBenchmarkSubmission, id: String = "turn") = PipelineBenchmarkTurn(
        turnId = id, channel = "text", capturedAtEpochMs = System.currentTimeMillis(),
        provenance = PipelineBenchmarkProvenance("fixture", 1),
        outcome = PipelineBenchmarkOutcome.COMPLETE, submissions = submissions.toList())
}

class ModelDetailsTest {
    private val model = ModelDetailsFixtures.model

    @Test fun emptyOrMissingMetricsNeverInventSpeed() {
        assertNull(modelSpeedLabel(emptyList(), model))
        assertNull(modelSpeedLabel(listOf(ModelDetailsFixtures.turn(ModelDetailsFixtures.submission())), model))
    }

    @Test fun observedTtftAndEstimatedDecodeHaveIndependentSampleCounts() {
        val turn = ModelDetailsFixtures.turn(
            ModelDetailsFixtures.submission("both", ttft = 100, decode = 8.0),
            ModelDetailsFixtures.submission("ttft", ttft = 200))
        assertEquals("Observed TTFT median: 150 ms (2 samples)\nEstimated decode median: 8.0 tok/s (1 sample)",
            modelSpeedLabel(listOf(turn), model))
    }

    @Test fun disjointMetricsAreNotMisrepresentedAsSharedRuns() {
        val turn = ModelDetailsFixtures.turn(
            ModelDetailsFixtures.submission("ttft", ttft = 123),
            ModelDetailsFixtures.submission("decode", decode = 9.25))
        assertEquals("Observed TTFT median: 123 ms (1 sample)\nEstimated decode median: 9.3 tok/s (1 sample)",
            modelSpeedLabel(listOf(turn), model))
    }

    @Test fun multipleCompletedSubmissionsPerTurnAreSamplesIncludingDraftsAndRetries() {
        val turn = ModelDetailsFixtures.turn(
            ModelDetailsFixtures.submission("answer", ttft = 30),
            ModelDetailsFixtures.submission("draft", ttft = 10, purpose = PipelineBenchmarkPurpose.DRAFT),
            ModelDetailsFixtures.submission("retry", ttft = 20, purpose = PipelineBenchmarkPurpose.RETRY))
        assertEquals("Observed TTFT median: 20 ms (3 samples)", modelSpeedLabel(listOf(turn), model))
    }

    @Test fun failedCancelledUnknownAndOtherModelsDoNotEnterCounts() {
        val excluded = PipelineBenchmarkOutcome.entries.filter { it != PipelineBenchmarkOutcome.COMPLETE }.map {
            ModelDetailsFixtures.submission(it.name, ttft = 999, decode = 999.0, outcome = it)
        } + ModelDetailsFixtures.submission("other-model", model = ModelCatalog.gemma4E4b, ttft = 999, decode = 999.0)
        val turn = ModelDetailsFixtures.turn(*excluded.toTypedArray(), ModelDetailsFixtures.submission("valid", ttft = 5))
        assertEquals("Observed TTFT median: 5 ms (1 sample)", modelSpeedLabel(listOf(turn), model))
    }

    @Test fun invalidMetricsAreIgnoredIndependentlyButZeroIsAnObservation() {
        val turn = ModelDetailsFixtures.turn(
            ModelDetailsFixtures.submission("negative", ttft = -1, decode = -5.0),
            ModelDetailsFixtures.submission("nan", decode = Double.NaN),
            ModelDetailsFixtures.submission("infinite", decode = Double.POSITIVE_INFINITY),
            ModelDetailsFixtures.submission("zero", ttft = 0, decode = 0.0))
        assertEquals("Observed TTFT median: 0 ms (1 sample)\nEstimated decode median: 0.0 tok/s (1 sample)",
            modelSpeedLabel(listOf(turn), model))
        assertNull(modelSpeedLabel(listOf(turn.copy(submissions = turn.submissions.dropLast(1))), model))
    }

    @Test fun evenMediansRetainHalfMillisecondsAndStayLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val turn = ModelDetailsFixtures.turn(
                ModelDetailsFixtures.submission("first", ttft = 101, decode = 11.0),
                ModelDetailsFixtures.submission("second", ttft = 100, decode = 10.0))
            assertEquals("Observed TTFT median: 100.5 ms (2 samples)\nEstimated decode median: 10.5 tok/s (2 samples)",
                modelSpeedLabel(listOf(turn), model))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun decodeOnlyNeverClaimsMeasuredOrObservedNativeTokenThroughput() {
        assertEquals("Estimated decode median: 12.0 tok/s (1 sample)", modelSpeedLabel(
            listOf(ModelDetailsFixtures.turn(ModelDetailsFixtures.submission(decode = 12.0))), model))
    }
}
