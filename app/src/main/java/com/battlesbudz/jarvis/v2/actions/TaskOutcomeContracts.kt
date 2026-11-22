package com.battlesbudz.jarvis.v2.actions

/**
 * Stable judged outcome vocabulary shared by tool execution, route gates,
 * skill gates, and release records. These are classifications, not executor
 * receipts: a task or acting model must not write its own classification.
 */
enum class TaskOutcomeClass {
    DONE,
    PARTIAL,
    NOT_POSSIBLE,
    UNKNOWN
}

enum class TaskOutcomeJudgeRole {
    DETERMINISTIC,
    ASYNC_MODEL
}

/** A post-task judgment, keyed to the ledger evidence that supports it. */
data class TaskOutcomeClassification(
    val taskId: String,
    val outcome: TaskOutcomeClass,
    val classifiedAtMs: Long,
    val evidenceIds: List<String>,
    val judgeRole: TaskOutcomeJudgeRole
) {
    init {
        require(taskId.isNotBlank())
        require(classifiedAtMs >= 0)
        require(evidenceIds.distinct().size == evidenceIds.size)
    }
}

/**
 * Evidence that a run actually produced. There is intentionally no model
 * claim or final-answer variant: the acting model's say-so is not evidence.
 * Keep values to short receipts/references rather than raw tool payloads.
 */
sealed interface ProducedTaskEvidence {
    val taskId: String
    val evidenceId: String
    val observedAtMs: Long

    data class ToolResult(
        override val taskId: String,
        override val evidenceId: String,
        override val observedAtMs: Long,
        val outcome: ExecutionResult.Outcome,
        val receipt: String
    ) : ProducedTaskEvidence

    data class ScreenObservation(
        override val taskId: String,
        override val evidenceId: String,
        override val observedAtMs: Long,
        val observation: String
    ) : ProducedTaskEvidence

    data class FileArtifact(
        override val taskId: String,
        override val evidenceId: String,
        override val observedAtMs: Long,
        val reference: String
    ) : ProducedTaskEvidence

    data class RuntimeObservation(
        override val taskId: String,
        override val evidenceId: String,
        override val observedAtMs: Long,
        val observation: String
    ) : ProducedTaskEvidence
}

/**
 * Evidence-ledger interface for the existing ToolTaskLedger/ToolTaskJournal
 * seam. Implementations accept only produced evidence and return evidence
 * for one task; they must not synthesize facts from the acting model's response.
 */
interface TaskEvidenceLedger {
    fun recordProduced(evidence: ProducedTaskEvidence)
    fun evidenceFor(taskId: String): List<ProducedTaskEvidence>
}

/**
 * Contract for future classifier wiring: enqueue only after task completion,
 * asynchronously and outside answer delivery. Classification may append a
 * record, but must never block, delay, rewrite, or replace a spoken answer.
 * Phase 0 declares this boundary; it deliberately supplies no judge runtime.
 */
interface TaskOutcomeClassificationSink {
    /** Must enqueue and return without waiting for classification. */
    fun enqueueAfterTask(taskId: String)
}
