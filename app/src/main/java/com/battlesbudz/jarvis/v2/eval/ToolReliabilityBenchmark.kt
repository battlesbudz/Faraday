package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ToolCallEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * On-device driver for the tool-call reliability suite (M8 early enabler).
 *
 * [runModels] loads each model in turn — exactly one resident at a time —
 * runs every canonical fixture through the production conversation path, and
 * persists one [ModelReport] per model. Each model attempt goes through the
 * [ModelOwner] path, which mirrors ModelSetupOperations' model-test
 * ownership: exclusive admission, the retained idle chat engine closed before
 * the benchmark allocates its own native engine (two native engines never
 * coexist), model-file integrity verified before the engine loads, and the
 * gate released afterwards. Each engine is initialized, tool-enabled,
 * exercised, and closed before the next model loads. A model that cannot
 * claim the gate is reported as skipped, not failed. Cancellation propagates;
 * the engine is always closed and ownership always released.
 *
 * Scores measure exact agreement with the fixture set and the tool schema —
 * not whether a requested phone action would succeed. Each saved report is
 * bound to the measured model file's identity and the suite version so a
 * replaced or deleted weight file, or a changed fixture set, never leaves an
 * old score presented as current.
 *
 * The [engineFactory] builds an uninitialized [ToolCallEngine] for a spec
 * (model file, cache dir, GPU choice, and the catalog's OpenApiTool
 * declarations are the caller's responsibility). [runSingleModelReport] is the
 * JVM-testable core: it needs only a [ToolCallRunner].
 */
class ToolReliabilityBenchmark(
    private val owner: ModelOwner,
    private val engineFactory: suspend (LocalModelSpec) -> ToolCallEngine,
    private val reportStore: ReliabilityReportStore
) {
    /**
     * Ownership path for one benchmark model attempt. Production implements
     * this against ModelStore and the session (see ModelSetupOperations);
     * JVM tests use a fake.
     */
    interface ModelOwner {
        /** Acquires exclusive model-operation ownership for [spec]; false when another operation owns it. */
        fun tryBeginModel(spec: LocalModelSpec): Boolean
        /** Releases ownership. Always runs after each model attempt. */
        fun endModelOperation()
        /** Closes the retained idle chat engine so the benchmark owns the only native engine. */
        fun closeIdleEngine()
        /** Verifies the installed model file is unchanged and intact before the engine loads it. */
        fun verifyModelFile(spec: LocalModelSpec): Boolean
        /** Identity of the actual installed model file, used to bind saved scores; null when unknown. */
        fun modelFingerprint(spec: LocalModelSpec): String?
        /**
         * Records a teardown problem (for example an engine close failure)
         * to diagnostics. Recording never replaces the failure being
         * handled: with no primary failure in flight the teardown error is
         * reported here and then rethrown so the run stops before another
         * native engine is allocated; when a primary failure already exists
         * it propagates and the teardown error is attached to it as
         * suppressed instead of replacing it — even when either side is a
         * cancellation. The call is fully guarded: if recording itself throws,
         * the recording error is attached to the winning exception as
         * suppressed instead of replacing it.
         */
        fun reportTeardownIssue(message: String)
    }

    data class Progress(
        val modelId: String,
        val finishedModels: Int,
        val totalModels: Int,
        val finishedFixtures: Int,
        val totalFixtures: Int
    )

    data class Result(
        val reports: Map<String, ModelReport>,
        val skipped: List<String>
    )

    suspend fun runModels(
        models: List<LocalModelSpec>,
        onProgress: suspend (Progress) -> Unit = {}
    ): Result {
        val reports = mutableMapOf<String, ModelReport>()
        val skipped = mutableListOf<String>()
        models.forEachIndexed { index, spec ->
            // Model-loop entry and admission boundary: never start a model
            // attempt for a cancelled run, and never consult admission state
            // after cancellation.
            currentCoroutineContext().ensureActive()
            if (!owner.tryBeginModel(spec)) {
                skipped += spec.id
                return@forEachIndexed
            }
            try {
                // Before idle close: a cancellation that landed during
                // admission must not be followed by tearing down the chat
                // engine.
                currentCoroutineContext().ensureActive()
                // The idle chat engine is the live owner of the native
                // runtime. Close it before allocating the benchmark engine so
                // a second native engine is never allocated over it.
                owner.closeIdleEngine()
                // Before hashing: a cancellation that landed during the idle
                // close must not be followed by the synchronous integrity
                // verification.
                currentCoroutineContext().ensureActive()
                check(owner.verifyModelFile(spec)) {
                    "The ${spec.id} model file changed or failed integrity verification. Re-import it."
                }
                // Verification is synchronous and slow (a multi-GB hash). This is
                // the allocation boundary too: a cancellation that landed
                // during verification must never be followed by native engine
                // allocation or initialization.
                currentCoroutineContext().ensureActive()
                val engine = engineFactory(spec)
                var primaryFailure: Throwable? = null
                try {
                    // Initialization boundary: a cancellation that landed
                    // during allocation must not be followed by init.
                    currentCoroutineContext().ensureActive()
                    engine.initialize()
                    // Recheck after synchronous initialize: a cancellation
                    // that landed during init must not be followed by
                    // enabling tools or scoring.
                    currentCoroutineContext().ensureActive()
                    engine.setToolsEnabled(true)
                    val report = runSingleModelReport(
                        spec,
                        LiteRtLmToolCallRunner(engine),
                        modelFingerprint = owner.modelFingerprint(spec)
                    ) { done, total ->
                        onProgress(Progress(spec.id, index, models.size, done, total))
                    }
                    reports[spec.id] = report
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    try {
                        engine.close()
                    } catch (teardown: Throwable) {
                        // One teardown catch. The winner is chosen first: the
                        // primary failure (or cancellation) outranks the close
                        // error, and the close error is thrown only when there
                        // is no primary, so a teardown failure still stops the
                        // run before another native engine is allocated. A
                        // distinct close error is attached to the primary as
                        // suppressed; when close threw the identical Throwable
                        // the identity is guarded, because self-suppression
                        // would itself throw and mask the winner. The whole
                        // diagnostic report call is guarded too: production
                        // recording allocates JSON/preferences with no
                        // no-throw guarantee, and a recording failure must
                        // never replace the winner — any distinct recording
                        // error is attached to the same winner as suppressed.
                        // The thrown winner keeps its exact identity, so a
                        // primary cancellation still cancels. The gate below is
                        // still released.
                        val primary = primaryFailure
                        val winner: Throwable = primary ?: teardown
                        if (primary != null && teardown !== primary) {
                            primary.addSuppressed(teardown)
                        }
                        try {
                            owner.reportTeardownIssue(
                                "Tool reliability check: closing the ${spec.id} benchmark engine failed: ${teardown.message}"
                            )
                        } catch (diagnostic: Throwable) {
                            if (diagnostic !== winner) {
                                winner.addSuppressed(diagnostic)
                            }
                        }
                        throw winner
                    }
                }
            } finally {
                owner.endModelOperation()
            }
        }
        return Result(reports, skipped)
    }

    /**
     * Scores every fixture against [runner] with per-fixture progress, saves
     * the report, and returns it. JVM-testable with [FakeToolCallRunner].
     */
    suspend fun runSingleModelReport(
        spec: LocalModelSpec,
        runner: ToolCallRunner,
        modelFingerprint: String? = null,
        onProgress: suspend (finished: Int, total: Int) -> Unit = { _, _ -> }
    ): ModelReport {
        val fixtures = ToolReliabilityFixtures.all()
        var finished = 0
        val counting = object : ToolCallRunner {
            override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> {
                // Fixture boundary: a cancellation that landed while the
                // previous fixture was scored stops the suite before the
                // next utterance runs.
                currentCoroutineContext().ensureActive()
                return runner.runUtterance(modelId, utterance).also {
                    finished++
                    onProgress(finished, fixtures.size)
                }
            }
        }
        val report = ToolReliabilityScorer.scoreModel(
            spec.id, fixtures, counting, modelFingerprint = modelFingerprint
        )
        reportStore.save(report)
        return report
    }
}

/**
 * Atomic admission for the reliability check.
 *
 * The gate MUST be acquired before the session-busy state is consulted:
 * checking busy first and acquiring second leaves a race on
 * Dispatchers.Default where the session becomes busy between the check and
 * the acquisition, and the check would then close the idle engine out from
 * under a live session. Holding the gate while checking makes
 * check-and-acquire atomic with respect to every other gate holder. The gate
 * is released when the session is busy. The conversation side admits through
 * [admitConversationTurn] on this same gate, so the two admissions serialize
 * against each other instead of racing as two independent checks.
 */
internal fun admitReliabilityCheck(
    acquireGate: () -> Boolean,
    releaseGate: () -> Unit,
    isBusy: () -> Boolean
): Boolean {
    if (!acquireGate()) return false
    if (isBusy()) {
        releaseGate()
        return false
    }
    return true
}

/**
 * Aborts the check when the session became busy between admission and the
 * idle-engine close. Closing the conversation under a live session would tear
 * down its engine mid-turn; failing loudly here is always safer.
 */
internal fun checkIdleBeforeClose(isBusy: () -> Boolean) {
    check(!isBusy()) {
        "The Jarvis session became busy after the reliability check was admitted. Aborting the check."
    }
}

/** Outcome of [admitConversationTurn]. */
internal enum class ConversationAdmission {
    /** The turn was admitted: the gate was acquired and the session marked active. */
    ADMITTED,
    /** A model operation (for example the reliability check) holds the gate. */
    GATE_BUSY,
    /** The gate was acquired but a conversation was already active. */
    SESSION_BUSY
}

/**
 * The single atomic admission mechanism shared with [admitReliabilityCheck].
 *
 * The conversation side must not read the model gate's state and then mark
 * itself active as two separate steps: on Dispatchers.Default the
 * reliability check can acquire the gate, observe idle, and close the idle
 * engine after the read but before the mark — starting a conversation on a
 * closed engine. Acquiring the gate first makes the whole check-and-mark
 * atomic with respect to the check's admission window (which holds the same
 * gate from acquisition through the idle-engine close): either this turn wins
 * the gate and the check later observes the active session, or the check
 * holds the gate and this acquire fails. A second independent busy check
 * cannot close this race; the gate is the one serialization point.
 *
 * The gate is held only for the admission instant and released before this
 * returns. Afterwards the active-session flag is what the check's [isBusy]
 * observes, so the run still refuses to start over a live session.
 */
internal fun admitConversationTurn(
    acquireGate: () -> Boolean,
    releaseGate: () -> Unit,
    markActive: () -> Boolean
): ConversationAdmission {
    if (!acquireGate()) return ConversationAdmission.GATE_BUSY
    return try {
        if (markActive()) ConversationAdmission.ADMITTED else ConversationAdmission.SESSION_BUSY
    } finally {
        releaseGate()
    }
}
