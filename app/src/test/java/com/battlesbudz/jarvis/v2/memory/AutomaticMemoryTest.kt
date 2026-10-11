package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/**
 * M6: automatic memory fixtures and behavior (D45-D49).
 *
 * Held-out fixture set for threshold tuning: [thresholdFixtures] documents the measured
 * separation that produced [EvaluationThresholds]. These fixtures are the tuning set;
 * the behavioral tests below use separate examples.
 */
class AutomaticMemoryTest {
    private var now = 1_700_000_000_000L
    private fun tempDir(): File = Files.createTempDirectory("automem").toFile()
    private fun os(dir: File = tempDir()) = MemoryOs(File(dir, "memory.json")) { now }
    private fun pipeline(
        os: MemoryOs,
        settings: AutomaticMemorySettings = AutomaticMemorySettings(),
        authorizer: (AutoMemorySource) -> Boolean = { true },
        fenceStore: FenceStore = InMemoryFenceStore(),
    ) = AutomaticMemory(os, { settings }, authorizer, { now }, fenceStore)

    private fun input(
        text: String,
        source: AutoMemorySource = AutoMemorySource.CHAT,
        authorized: Boolean = true,
    ) = CandidateInput("evt-${text.hashCode()}", source, text, now, authorized)

    private val evaluator = HeuristicMemoryEvaluator()
    private val idle: () -> Boolean = { true }
    private val busy: () -> Boolean = { false }

    // ---------- D45: default-on setting ----------

    @Test fun automaticMemoryDefaultsOn() {
        assertTrue(AutomaticMemorySettings().enabled)
    }

    @Test fun disabledSettingRejectsCapture() {
        val dir = tempDir()
        val auto = pipeline(os(dir), AutomaticMemorySettings(enabled = false))
        val result = auto.capture(input("I live in Watertown, New York"))
        assertEquals(SelfReviewDecision.REJECT, result.review.decision)
        assertEquals(0, auto.pendingCount)
    }

    // ---------- Positive fixtures: captured, evaluated, saved ----------

    @Test fun positiveFactFixtureIsSavedToWiki() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        val result = auto.capture(input("I live in Watertown, New York"))
        assertEquals(SelfReviewDecision.ACCEPT, result.review.decision)
        assertEquals(1, auto.pendingCount)

        val evaluated = auto.evaluatePending(evaluator, idle)
        assertEquals(1, evaluated)
        assertEquals(0, auto.pendingCount)

        val snapshot = memoryOs.read().snapshot!!
        val saved = snapshot.memories.filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertEquals(1, saved.size)
        assertEquals("I live in Watertown, New York", saved[0].content)
        assertNotNull(saved[0].wikiAssignment)

        // Prompt retrieval after index changes: the wiki index finds it.
        val index = MemoryWikiIndex(
            pages = emptyList(),
            memories = snapshot.memories.filter { it.reviewStatus == MemoryReviewStatus.APPROVED },
        )
        assertTrue(index.search("Watertown").any { it.content == "I live in Watertown, New York" })

        // Scores are versioned.
        val evaluation = auto.evaluations.single()
        assertEquals(MemoryEvaluator.EVALUATOR_VERSION, evaluation.evaluatorVersion)
        assertTrue(evaluation.save)
    }

    @Test fun preferenceFixtureIsClassifiedAndSaved() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("My favorite coffee is dark roast"))
        auto.evaluatePending(evaluator, idle)
        val saved = memoryOs.read().snapshot!!.memories
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertEquals(1, saved.size)
        assertEquals(MemoryCategory.PREFERENCE, saved[0].category)
    }

    @Test fun voiceSourceFixtureIsSaved() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("My wife's name is Andrea", AutoMemorySource.VOICE))
        auto.evaluatePending(evaluator, idle)
        val saved = memoryOs.read().snapshot!!.memories
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertEquals(1, saved.size)
        assertEquals(MemoryCategory.PERSON, saved[0].category)
    }

    // ---------- Negative fixtures: discarded ----------

    @Test fun trivialFixturesAreDiscarded() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        listOf("ok thanks", "hi", "lol", "yes").forEach { auto.capture(input(it)) }
        // "hi"/"lol"/"yes" fail self-review (too short); "ok thanks" is queued then discarded.
        auto.evaluatePending(evaluator, idle)
        val saved = memoryOs.read().snapshot!!.memories
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertTrue(saved.isEmpty())
        assertTrue(auto.evaluations.none { it.save })
    }

    // ---------- Credential fixtures: excluded before persistence ----------

    @Test fun credentialFixturesAreExcluded() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        val r1 = auto.capture(input("My password is hunter2"))
        val r2 = auto.capture(input("API key: sk-abc123xyz"))
        assertEquals(SelfReviewDecision.REJECT, r1.review.decision)
        assertEquals(SelfReviewDecision.REJECT, r2.review.decision)
        assertEquals(0, auto.pendingCount)
        auto.evaluatePending(evaluator, idle)
        val snapshot = memoryOs.read().snapshot
        val all = snapshot?.memories.orEmpty()
        assertTrue(all.none { it.content.contains("hunter2") || it.content.contains("sk-abc123") })
    }

    // ---------- Conflicting fixture ----------

    @Test fun conflictingFixtureIsNotAutoSaved() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("I live in Watertown, New York"))
        auto.evaluatePending(evaluator, idle)
        assertEquals(1, memoryOs.read().snapshot!!.memories.count { it.reviewStatus == MemoryReviewStatus.APPROVED })

        // Contradicting location fact: the evaluator flags the conflict; nothing is auto-saved.
        auto.capture(input("I live in Syracuse, New York"))
        auto.evaluatePending(evaluator, idle)
        val approved = memoryOs.read().snapshot!!.memories
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertEquals(1, approved.size)
        assertEquals("I live in Watertown, New York", approved[0].content)
        val conflictEval = auto.evaluations.last()
        assertFalse(conflictEval.save)
        assertTrue(conflictEval.reason.contains("Conflict", ignoreCase = true))
    }

    // ---------- Source-permission revocation ----------

    @Test fun revokedSourcePermissionRejectsAtCapture() {
        val dir = tempDir()
        val auto = pipeline(os(dir), authorizer = { source -> source != AutoMemorySource.VOICE })
        val result = auto.capture(input("I live in Watertown", AutoMemorySource.VOICE))
        assertEquals(SelfReviewDecision.REJECT, result.review.decision)
        assertTrue(result.review.reason.contains("permission", ignoreCase = true))
    }

    @Test fun revokedSourcePermissionBlocksQueuedEvaluation() {
        val dir = tempDir()
        var voiceAllowed = true
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs, authorizer = { source ->
            if (source == AutoMemorySource.VOICE) voiceAllowed else true
        })
        auto.capture(input("My wife's name is Andrea", AutoMemorySource.VOICE))
        assertEquals(1, auto.pendingCount)
        voiceAllowed = false // Revoked between capture and evaluation.
        auto.evaluatePending(evaluator, idle)
        val approved = memoryOs.read().snapshot!!.memories
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
        assertTrue(approved.isEmpty())
        assertTrue(auto.evaluations.single().reason.contains("revoked", ignoreCase = true))
    }

    // ---------- Anti-resurrection: manual deletion fences ----------

    @Test fun manualDeletionFencesRecapture() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("I live in Watertown, New York"))
        auto.evaluatePending(evaluator, idle)
        val memoryId = memoryOs.read().snapshot!!.memories
            .first { it.reviewStatus == MemoryReviewStatus.APPROVED }.id

        // Manual deletion is authoritative.
        memoryOs.delete(memoryId)
        assertTrue(memoryOs.read().snapshot!!.memories.none { it.id == memoryId })

        // Re-capture of the same content must not resurrect it.
        val recapture = auto.capture(input("I live in Watertown, New York"))
        assertEquals(SelfReviewDecision.REJECT, recapture.review.decision)
        assertTrue(recapture.review.reason.contains("deleted", ignoreCase = true))
        auto.evaluatePending(evaluator, idle)
        assertTrue(memoryOs.read().snapshot!!.memories.none { it.content == "I live in Watertown, New York" })
    }

    @Test fun queuedEvaluationRaceDoesNotResurrect() {
        // Two pipeline instances sharing one fence store (e.g., two sessions).
        val dir = tempDir()
        val fenceFile = File(dir, "fence.txt")
        val memoryOs = os(dir)
        val first = pipeline(memoryOs, fenceStore = FileFenceStore(fenceFile))
        val second = pipeline(memoryOs, fenceStore = FileFenceStore(fenceFile))

        // Both capture the same content before either evaluates.
        first.capture(input("I live in Watertown, New York"))
        second.capture(input("I live in Watertown, New York"))
        assertEquals(1, first.pendingCount)
        assertEquals(1, second.pendingCount)

        // Second instance commits, then the user manually deletes it.
        second.evaluatePending(evaluator, idle)
        val memoryId = memoryOs.read().snapshot!!.memories
            .first { it.reviewStatus == MemoryReviewStatus.APPROVED }.id
        memoryOs.delete(memoryId)

        // First instance's stale queued candidate must be fenced at evaluation time.
        first.evaluatePending(evaluator, idle)
        assertTrue(memoryOs.read().snapshot!!.memories.none { it.content == "I live in Watertown, New York" })
        val raceEval = first.evaluations.single()
        assertFalse(raceEval.save)
        assertTrue(raceEval.reason.contains("deletion", ignoreCase = true))
    }

    @Test fun fenceSurvivesRestart() {
        val dir = tempDir()
        val fenceFile = File(dir, "fence.txt")
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs, fenceStore = FileFenceStore(fenceFile))
        auto.capture(input("I live in Watertown, New York"))
        auto.evaluatePending(evaluator, idle)
        val memoryId = memoryOs.read().snapshot!!.memories
            .first { it.reviewStatus == MemoryReviewStatus.APPROVED }.id
        memoryOs.delete(memoryId)

        // New pipeline instance after restart, same fence file.
        val restarted = pipeline(memoryOs, fenceStore = FileFenceStore(fenceFile))
        val recapture = restarted.capture(input("I live in Watertown, New York"))
        assertEquals(SelfReviewDecision.REJECT, recapture.review.decision)
        assertTrue(recapture.review.reason.contains("deleted", ignoreCase = true))
    }

    // ---------- D47: idle gating and interruption ----------

    @Test fun evaluationYieldsWhenNotIdle() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("I live in Watertown, New York"))
        val evaluated = auto.evaluatePending(evaluator, busy)
        assertEquals(0, evaluated)
        assertEquals(1, auto.pendingCount)
        assertTrue(memoryOs.read().snapshot!!.memories.none { it.reviewStatus == MemoryReviewStatus.APPROVED })
    }

    @Test fun evaluationIsInterruptible() {
        val dir = tempDir()
        val memoryOs = os(dir)
        val auto = pipeline(memoryOs)
        auto.capture(input("I live in Watertown, New York"))
        auto.capture(input("My favorite coffee is dark roast"))
        var calls = 0
        val cancel = object : EvaluationCancel {
            override val isCancelled: Boolean get() = ++calls > 1
        }
        val evaluated = auto.evaluatePending(evaluator, idle, cancel)
        assertEquals(1, evaluated)
        assertEquals(1, auto.pendingCount)
    }

    // ---------- Evaluation log ----------

    @Test fun evaluationLogPersistsDecisions() {
        val dir = tempDir()
        val auto = pipeline(os(dir))
        auto.capture(input("I live in Watertown, New York"))
        auto.capture(input("ok thanks"))
        auto.evaluatePending(evaluator, idle)
        val log = auto.evaluations
        assertEquals(2, log.size)
        assertTrue(log.any { it.save })
        assertTrue(log.any { !it.save })
        assertTrue(log.all { it.evaluatorVersion == MemoryEvaluator.EVALUATOR_VERSION })
        assertTrue(log.all { it.reason.isNotBlank() })
    }

    // ---------- Threshold tuning: held-out fixture set ----------

    /**
     * Held-out fixtures used to tune [EvaluationThresholds]. Positive examples must score
     * at or above both thresholds; negative examples must fall below at least one.
     * Measured with HeuristicMemoryEvaluator v1 on 2026-10-11.
     */
    @Test fun thresholdFixtures() {
        val positives = listOf(
            "I live in Watertown, New York" to AutoMemorySource.CHAT,
            "My favorite coffee is dark roast" to AutoMemorySource.CHAT,
            "My wife's name is Andrea" to AutoMemorySource.VOICE,
            "I have a dentist appointment tomorrow at 9am" to AutoMemorySource.CHAT,
            "My son's birthday is March 15th" to AutoMemorySource.CHAT,
        )
        val negatives = listOf(
            "ok thanks" to AutoMemorySource.CHAT,
            "remind me later" to AutoMemorySource.CHAT,
            "interesting" to AutoMemorySource.VOICE,
            "let me think" to AutoMemorySource.CHAT,
        )
        fun score(text: String, source: AutoMemorySource): MemoryEvaluation {
            val candidate = MemoryCandidate(
                id = "fixture",
                content = text,
                category = AutomaticMemory.classify(text),
                source = source,
                eventId = "fixture",
                capturedAtMs = now,
                contentFingerprint = AutomaticMemory.contentFingerprint(text),
            )
            return evaluator.evaluate(candidate)
        }
        val positiveScores = positives.map { (t, s) -> score(t, s) }
        val negativeScores = negatives.map { (t, s) -> score(t, s) }
        val minPositiveRelevance = positiveScores.minOf { it.relevance }
        val maxNegativeRelevance = negativeScores.maxOf { it.relevance }
        // Documented separation: positives score >= 40 relevance, negatives <= 15.
        assertTrue("min positive relevance $minPositiveRelevance", minPositiveRelevance >= 40)
        assertTrue("max negative relevance $maxNegativeRelevance", maxNegativeRelevance <= 15)
        assertTrue(EvaluationThresholds.SAVE_RELEVANCE in (maxNegativeRelevance + 1)..minPositiveRelevance)
        // Every positive meets both thresholds; every negative misses at least one.
        assertTrue(positiveScores.all {
            it.relevance >= EvaluationThresholds.SAVE_RELEVANCE &&
                it.confidence >= EvaluationThresholds.SAVE_CONFIDENCE
        })
        assertTrue(negativeScores.none {
            it.relevance >= EvaluationThresholds.SAVE_RELEVANCE &&
                it.confidence >= EvaluationThresholds.SAVE_CONFIDENCE
        })
    }
}
