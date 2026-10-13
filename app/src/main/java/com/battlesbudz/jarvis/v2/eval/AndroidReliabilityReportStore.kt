package com.battlesbudz.jarvis.v2.eval

import android.content.Context
import kotlin.math.roundToInt

/**
 * [ReliabilityReportStore] backed by SharedPreferences, keyed per model id.
 * Written by the user-triggered reliability check; read by the model catalog
 * so scores display without re-running the suite.
 */
class AndroidReliabilityReportStore(context: Context) : ReliabilityReportStore {
    private val prefs = context.getSharedPreferences("tool_reliability", Context.MODE_PRIVATE)

    private fun key(modelId: String, field: String) = "reliability_${modelId}_$field"

    override fun save(report: ModelReport, ranAtMs: Long) {
        val editor = prefs.edit()
            .putInt(key(report.modelId, "percent"), report.percent.roundToInt())
            .putInt(key(report.modelId, "passed"), report.passed)
            .putInt(key(report.modelId, "total"), report.total)
            .putString(key(report.modelId, "summary"), report.renderSummary())
            .putLong(key(report.modelId, "ran_at"), ranAtMs)
            .putInt(key(report.modelId, "suite_version"), report.suiteVersion)
        val fingerprint = report.modelFingerprint
        if (fingerprint != null) editor.putString(key(report.modelId, "fingerprint"), fingerprint)
        else editor.remove(key(report.modelId, "fingerprint"))
        editor.apply()
    }

    override fun load(modelId: String): StoredReliabilityReport? {
        if (!prefs.contains(key(modelId, "percent"))) return null
        return StoredReliabilityReport(
            modelId = modelId,
            percent = prefs.getInt(key(modelId, "percent"), 0),
            passed = prefs.getInt(key(modelId, "passed"), 0),
            total = prefs.getInt(key(modelId, "total"), 0),
            summary = prefs.getString(key(modelId, "summary"), "") ?: "",
            ranAtMs = prefs.getLong(key(modelId, "ran_at"), 0L),
            modelFingerprint = prefs.getString(key(modelId, "fingerprint"), null),
            // Entries saved before version binding default to -1 and never match.
            suiteVersion = prefs.getInt(key(modelId, "suite_version"), -1)
        )
    }

    override fun clear(modelId: String) {
        prefs.edit()
            .remove(key(modelId, "percent"))
            .remove(key(modelId, "passed"))
            .remove(key(modelId, "total"))
            .remove(key(modelId, "summary"))
            .remove(key(modelId, "ran_at"))
            .remove(key(modelId, "fingerprint"))
            .remove(key(modelId, "suite_version"))
            .apply()
    }
}
