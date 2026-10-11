package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * M6: automatic memory (D45-D49).
 *
 * Captures minimal memory candidates from authorized chat/voice/tool sources, self-reviews
 * them, and evaluates them in a separate interruptible background pass that only runs when
 * the local model is idle and yields the moment the user needs Jarvis (D47). Candidates
 * scoring above the measured thresholds are committed through [MemoryOs] without routine
 * user review (D45, D46); routine/skill activation still requires review, and memory facts
 * never create execution grants.
 *
 * Credential-bearing content is excluded before any persistence (D49, D38). Manual
 * corrections and deletions are authoritative: a content fence records every committed
 * candidate, and deleted content can never be resurrected by re-capture — not by queued
 * evaluations, not by old sources, not after restart (D48). The fence persists through
 * [FenceStore] so it survives process death.
 */
enum class AutoMemorySource { CHAT, VOICE, TOOL }

/** Global automatic-memory setting. Enabled by default across chat, voice, and tools (D45). */
data class AutomaticMemorySettings(val enabled: Boolean = true)

data class CandidateInput(
    val eventId: String,
    val source: AutoMemorySource,
    val text: String,
    val capturedAtMs: Long,
    /** False when the source's permission was revoked before capture. */
    val sourceAuthorized: Boolean = true,
)

data class MemoryCandidate(
    val id: String,
    val content: String,
    val category: MemoryCategory,
    val source: AutoMemorySource,
    val eventId: String,
    val capturedAtMs: Long,
    /** SHA-256 of normalized content; the anti-resurrection fence key. */
    val contentFingerprint: String,
)

enum class SelfReviewDecision { ACCEPT, REJECT }

data class SelfReview(val decision: SelfReviewDecision, val reason: String)

data class CaptureResult(val review: SelfReview, val candidate: MemoryCandidate? = null)

/** Cooperative cancellation for the background evaluation pass. */
interface EvaluationCancel {
    val isCancelled: Boolean
}

object NoCancel : EvaluationCancel {
    override val isCancelled: Boolean = false
}

/** Persistence for the anti-resurrection fence (contentFingerprint -> memoryId). */
interface FenceStore {
    fun load(): Map<String, String>
    fun save(committed: Map<String, String>)
}

class InMemoryFenceStore : FenceStore {
    private var state: Map<String, String> = emptyMap()
    override fun load(): Map<String, String> = state
    override fun save(committed: Map<String, String>) {
        state = committed.toMap()
    }
}

/** File-backed fence store: one line per entry, `fingerprint \t memoryId`. */
class FileFenceStore(private val file: File) : FenceStore {
    override fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                parts[0] to parts[1]
            } else {
                null
            }
        }.toMap()
    }

    override fun save(committed: Map<String, String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(committed.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * Content fence against resurrection (D48). Records the content fingerprint of every
 * candidate this pipeline commits. A fingerprint whose memory no longer exists in the
 * snapshot was manually deleted and must never be re-committed — even after restart,
 * even across pipeline instances sharing the same [FenceStore].
 */
class AutomaticMemoryFence(private val store: FenceStore = InMemoryFenceStore()) {
    fun recordCommitted(contentFingerprint: String, memoryId: String) {
        val updated = store.load().toMutableMap()
        updated[contentFingerprint] = memoryId
        store.save(updated)
    }

    fun isFenced(contentFingerprint: String, snapshot: MemorySnapshot?): Boolean {
        val memoryId = store.load()[contentFingerprint] ?: return false
        if (snapshot == null) return true // Cannot verify; fence closed.
        return snapshot.memories.none { it.id == memoryId }
    }

    fun committedCount(): Int = store.load().size
}

class AutomaticMemory(
    private val memoryOs: MemoryOs,
    private val settings: () -> AutomaticMemorySettings = { AutomaticMemorySettings() },
    private val sourceAuthorizer: (AutoMemorySource) -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    fenceStore: FenceStore = InMemoryFenceStore(),
) {
    private val pending = ArrayDeque<MemoryCandidate>()
    private val evaluationLog = mutableListOf<MemoryEvaluation>()
    private val fence = AutomaticMemoryFence(fenceStore)

    val pendingCount: Int get() = pending.size
    val evaluations: List<MemoryEvaluation> get() = evaluationLog.toList()
    val fencedCount: Int get() = fence.committedCount()

    /**
     * Captures a candidate from an authorized source and self-reviews it.
     * Accepted candidates are queued for background evaluation; nothing is persisted yet.
     */
    fun capture(input: CandidateInput): CaptureResult {
        if (!settings().enabled) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Automatic memory is disabled."))
        }
        if (!input.sourceAuthorized || !sourceAuthorizer(input.source)) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Source permission revoked."))
        }
        val content = input.text.trim()
        // Credential exclusion happens before any persistence (D49).
        if (MemoryPolicy.containsRawRestrictedContent(content)) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Credential-bearing content is excluded."))
        }
        if (content.length < MIN_CANDIDATE_CHARS) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Content too short to be a memory."))
        }
        if (content.length > MemoryPolicy.MAX_CONTENT_CHARS) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Content exceeds maximum length."))
        }
        val fingerprint = contentFingerprint(content)
        // Anti-resurrection: never re-capture content this pipeline committed and the user deleted.
        if (fence.isFenced(fingerprint, memoryOs.read().snapshot)) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Content was manually deleted; will not resurrect."))
        }
        // Duplicate suppression against live approved memories.
        val snapshot = memoryOs.read().snapshot
        if (snapshot != null && snapshot.memories.any { it.reviewStatus == MemoryReviewStatus.APPROVED && contentFingerprint(it.content) == fingerprint }) {
            return CaptureResult(SelfReview(SelfReviewDecision.REJECT, "Already recorded."))
        }
        val candidate = MemoryCandidate(
            id = UUID.randomUUID().toString(),
            content = content,
            category = classify(content),
            source = input.source,
            eventId = input.eventId,
            capturedAtMs = input.capturedAtMs,
            contentFingerprint = fingerprint,
        )
        pending.addLast(candidate)
        return CaptureResult(SelfReview(SelfReviewDecision.ACCEPT, "Candidate queued for evaluation."), candidate)
    }

    /**
     * Evaluates queued candidates. Runs only while [isIdle] is true and stops immediately
     * when [cancel] fires or the user needs Jarvis (D47). Returns the number evaluated.
     */
    fun evaluatePending(
        evaluator: MemoryEvaluator,
        isIdle: () -> Boolean,
        cancel: EvaluationCancel = NoCancel,
    ): Int {
        var evaluated = 0
        while (pending.isNotEmpty()) {
            if (cancel.isCancelled) break
            if (!isIdle()) break
            val candidate = pending.removeFirst()
            val snapshot = memoryOs.read().snapshot
            // Queued-evaluation race: the content was deleted while waiting; fence it.
            if (fence.isFenced(candidate.contentFingerprint, snapshot)) {
                evaluationLog.add(
                    MemoryEvaluation(
                        candidateId = candidate.id,
                        relevance = 0,
                        confidence = 0,
                        evaluatorVersion = MemoryEvaluator.EVALUATOR_VERSION,
                        evaluatedAtMs = clock(),
                        save = false,
                        reason = "Superseded by manual deletion while queued.",
                    )
                )
                continue
            }
            // Source permission may have been revoked between capture and evaluation.
            if (!sourceAuthorizer(candidate.source)) {
                evaluationLog.add(
                    MemoryEvaluation(
                        candidateId = candidate.id,
                        relevance = 0,
                        confidence = 0,
                        evaluatorVersion = MemoryEvaluator.EVALUATOR_VERSION,
                        evaluatedAtMs = clock(),
                        save = false,
                        reason = "Source permission revoked before evaluation.",
                    )
                )
                continue
            }
            val context = EvaluationContext(
                existingMemories = snapshot?.memories.orEmpty(),
                tombstones = snapshot?.tombstones.orEmpty(),
            )
            val evaluation = evaluator.evaluate(candidate, context)
            evaluationLog.add(evaluation)
            if (evaluation.save) commit(candidate, evaluation)
            evaluated++
        }
        return evaluated
    }

    /** Commits a qualifying candidate through MemoryOs; the wiki/index update automatically. */
    private fun commit(candidate: MemoryCandidate, evaluation: MemoryEvaluation) {
        val source = MemorySource(
            eventId = candidate.eventId,
            eventSource = "auto-memory/${candidate.source.name.lowercase()}",
            createdAtMs = candidate.capturedAtMs,
        )
        val proposal = MemoryProposal(
            content = candidate.content,
            source = source,
            category = candidate.category,
            confidence = evaluation.confidence,
            wikiAssignment = MemoryWiki.suggest(candidate.content, candidate.category),
        )
        val result = memoryOs.propose(proposal)
        val record = result.memory
        if (result.outcome == MemoryOutcome.CREATED && record != null) {
            val approved = memoryOs.approve(record.id)
            if (approved.outcome == MemoryOutcome.APPROVED) {
                fence.recordCommitted(candidate.contentFingerprint, record.id)
            }
        }
        // Other outcomes (ALREADY_RECORDED, EXCLUDED, etc.) simply drop the candidate;
        // the evaluation log preserves the decision for audit.
    }

    companion object {
        const val MIN_CANDIDATE_CHARS = 8

        fun contentFingerprint(content: String): String {
            val normalized = content.trim().replace(Regex("\\s+"), " ").lowercase()
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun classify(content: String): MemoryCategory {
            val lower = content.lowercase()
            return when {
                Regex("\\b(i like|i love|i prefer|i hate|i dislike|my favorit\\w*|i can't stand)\\b").containsMatchIn(lower) -> MemoryCategory.PREFERENCE
                Regex("\\b(my (wife|husband|son|daughter|mom|dad|mother|father|brother|sister|friend|boss|partner))\\b").containsMatchIn(lower) -> MemoryCategory.PERSON
                Regex("\\b(my (goal|plan|resolution)|i want to|i need to)\\b").containsMatchIn(lower) -> MemoryCategory.GOAL
                else -> MemoryCategory.FACT
            }
        }
    }
}
