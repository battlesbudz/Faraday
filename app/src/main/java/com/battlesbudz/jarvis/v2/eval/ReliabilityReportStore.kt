package com.battlesbudz.jarvis.v2.eval

import kotlin.math.roundToInt

/**
 * Persisted result of one tool-call reliability run against a model.
 * [summary] is the full [ModelReport.renderSummary] text so the catalog can
 * show the per-model and per-tool breakdown without re-running.
 */
data class StoredReliabilityReport(
    val modelId: String,
    val percent: Int,
    val passed: Int,
    val total: Int,
    val summary: String,
    val ranAtMs: Long,
    /**
     * Identity of the exact model file measured. Null for scores saved before
     * binding existed; those are never presented as current.
     */
    val modelFingerprint: String? = null,
    /** Fixture/scorer version measured with. */
    val suiteVersion: Int = -1
) {
    /**
     * A stored score is only presentable when it was measured against this
     * exact model file and the current suite version. Replacing or deleting
     * the weights, or changing the fixtures/scorer, invalidates old scores
     * instead of leaving them presented as current.
     */
    fun isCurrent(modelFingerprint: String?, suiteVersion: Int): Boolean =
        modelFingerprint != null &&
            this.modelFingerprint == modelFingerprint &&
            this.suiteVersion == suiteVersion
}

/**
 * Storage for the last reliability report per model. The catalog reads from
 * here so scores survive restarts without re-running the suite.
 */
interface ReliabilityReportStore {
    fun save(report: ModelReport, ranAtMs: Long = System.currentTimeMillis())
    fun load(modelId: String): StoredReliabilityReport?
    fun clear(modelId: String)

    /**
     * Returns the stored report only when it was measured against this exact
     * model file and suite version — never a stale score presented as current.
     */
    fun loadCurrent(
        modelId: String,
        modelFingerprint: String?,
        suiteVersion: Int
    ): StoredReliabilityReport? =
        load(modelId)?.takeIf { it.isCurrent(modelFingerprint, suiteVersion) }
}

/** In-memory implementation for JVM tests and Compose previews. */
class InMemoryReliabilityReportStore : ReliabilityReportStore {
    private val reports = mutableMapOf<String, StoredReliabilityReport>()

    override fun save(report: ModelReport, ranAtMs: Long) {
        reports[report.modelId] = StoredReliabilityReport(
            modelId = report.modelId,
            percent = report.percent.roundToInt(),
            passed = report.passed,
            total = report.total,
            summary = report.renderSummary(),
            ranAtMs = ranAtMs,
            modelFingerprint = report.modelFingerprint,
            suiteVersion = report.suiteVersion
        )
    }

    override fun load(modelId: String): StoredReliabilityReport? = reports[modelId]

    override fun clear(modelId: String) {
        reports.remove(modelId)
    }
}
