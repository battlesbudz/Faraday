package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ToolCallEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolReliabilityBenchmarkTest {

    private val spec = LocalModelSpec(
        id = "TEST-MODEL",
        fileName = "test-model.litertlm",
        recommendedGpu = false
    )

    /** Owner-path fake mirroring the ModelSetupOperations ownership contract. */
    private open class FakeOwner(
        var busy: Boolean = false,
        var integrityOk: Boolean = true,
        var fingerprint: String? = "fingerprint",
        val events: MutableList<String> = mutableListOf(),
        val teardownIssues: MutableList<String> = mutableListOf()
    ) : ToolReliabilityBenchmark.ModelOwner {
        override open fun tryBeginModel(spec: LocalModelSpec): Boolean {
            events += "tryBegin"
            return !busy
        }
        override open fun endModelOperation() {
            events += "end"
        }
        override open fun closeIdleEngine() {
            events += "closeIdle"
        }
        override open fun verifyModelFile(spec: LocalModelSpec): Boolean {
            events += "verify"
            return integrityOk
        }
        override fun modelFingerprint(spec: LocalModelSpec): String? = fingerprint
        override fun reportTeardownIssue(message: String) {
            teardownIssues += message
        }
    }

    /** Engine fake that records conversation history to detect leakage. */
    private class RecordingEngine(
        private val script: Map<String, List<ToolCall>> = emptyMap(),
        private val failAtGenerate: Int = -1,
        private val failOnInitialize: Boolean = false,
        private val failOnClose: Boolean = false,
        private val generateDelayMs: Long = 0L,
        private val events: MutableList<String> = mutableListOf()
    ) : ToolCallEngine {
        val historyAtGenerate = mutableListOf<List<String>>()
        private val history = mutableListOf<String>()
        private var generateCalls = 0
        var resets = 0
        var closes = 0

        override suspend fun initialize() {
            events += "initialize"
            if (failOnInitialize) throw RuntimeException("init boom")
        }
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            events += "setToolsEnabled"
            return true
        }
        override suspend fun resetConversation() {
            events += "reset"
            resets++
            history.clear()
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult {
            events += "generate"
            if (generateDelayMs > 0) delay(generateDelayMs)
            // Trigger on call count, not history size: the runner resets the
            // conversation before every utterance, so history never
            // accumulates and a history-size trigger would never fire.
            if (generateCalls == failAtGenerate) throw RuntimeException("boom")
            generateCalls++
            historyAtGenerate += history.toList()
            history += prompt
            return GenerationResult(
                text = "",
                timeToFirstTokenMs = 0,
                decodeTokensPerSecond = null,
                toolCalls = script[prompt] ?: emptyList()
            )
        }
        override fun close() {
            events += "close"
            closes++
            if (failOnClose) throw RuntimeException("close boom")
        }
    }

    /** Benchmark with no Android dependencies: engine factory is never called here. */
    private fun benchmark(
        store: ReliabilityReportStore,
        owner: ToolReliabilityBenchmark.ModelOwner = FakeOwner()
    ): ToolReliabilityBenchmark =
        ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { error("no engine in JVM tests") },
            reportStore = store
        )

    @Test fun singleModelReportScoresAndPersists() = runBlocking {
        val fixtures = ToolReliabilityFixtures.all()
        val store = InMemoryReliabilityReportStore()
        val script = fixtures.associate { fixture ->
            fixture.utterance to listOf(ToolCall(fixture.expectedTool, "{}"))
        }
        // Note: "{}" args only pass strict decode for no-parameter tools; this
        // test checks orchestration (progress + persistence), not scoring.
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val report = benchmark(store).runSingleModelReport(
            spec,
            FakeToolCallRunner(script)
        ) { done, total -> progressCalls += done to total }
        assertEquals(fixtures.size, report.total)
        assertEquals(fixtures.size, progressCalls.size)
        assertEquals((1..fixtures.size).toList(), progressCalls.map { it.first })
        assertTrue(progressCalls.all { it.second == fixtures.size })
        val stored = store.load(spec.id)!!
        assertEquals(report.passed, stored.passed)
        assertEquals(report.total, stored.total)
    }

    @Test fun singleModelReportCountsRealPasses() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val runner = FakeToolCallRunner(
            mapOf(
                "How much battery does my phone have?" to
                    listOf(ToolCall("read_battery", "{}"))
            )
        )
        val report = benchmark(store).runSingleModelReport(spec, runner) { _, _ -> }
        val batteryCases = report.caseResults.filter { it.expectedTool == "read_battery" }
        assertEquals(2, batteryCases.size)
        assertEquals(1, batteryCases.count { it.passed })
        assertTrue(report.percent < 100.0)
        assertEquals(report.percent.toInt(), store.load(spec.id)!!.percent)
    }

    @Test fun conversationResetsBetweenFixtures() = runBlocking {
        // Regression: repeated generate calls on one retained conversation
        // contaminate later cases. Every fixture must start with no history.
        val store = InMemoryReliabilityReportStore()
        val engine = RecordingEngine()
        benchmark(store).runSingleModelReport(spec, LiteRtLmToolCallRunner(engine))
        val fixtures = ToolReliabilityFixtures.all()
        assertEquals(fixtures.size, engine.historyAtGenerate.size)
        assertEquals(fixtures.size, engine.resets)
        assertTrue(
            "earlier fixtures leaked into later cases",
            engine.historyAtGenerate.all { it.isEmpty() }
        )
    }

    @Test fun skippedWhenOwnerBusyTouchesNothing() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(busy = true, events = events)
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        val result = benchmark.runModels(listOf(spec))
        assertEquals(listOf(spec.id), result.skipped)
        assertTrue(result.reports.isEmpty())
        assertEquals(0, factoryCalls)
        // Nothing was acquired, so nothing is released.
        assertEquals(listOf("tryBegin"), events)
    }

    @Test fun idleEngineClosedBeforeEngineAllocated() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> events += "factory"; engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        benchmark.runModels(listOf(spec))
        assertTrue(
            "the retained idle engine must close before the benchmark engine is allocated",
            events.indexOf("closeIdle") < events.indexOf("factory")
        )
        assertTrue(events.indexOf("verify") < events.indexOf("factory"))
    }

    @Test fun integrityFailureReleasesOwnerWithoutAllocatingEngine() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(integrityOk = false, events = events)
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected integrity failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("integrity verification"))
        }
        assertEquals(0, factoryCalls)
        assertEquals(listOf("tryBegin", "closeIdle", "verify", "end"), events)
    }

    @Test fun engineInitFailureClosesEngineAndReleasesOwner() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failOnInitialize = true, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected init failure")
        } catch (e: RuntimeException) {
            assertEquals("init boom", e.message)
        }
        assertEquals(1, engine.closes)
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun scoringFailureClosesEngineAndReleasesOwner() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failAtGenerate = 3, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = store
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected scoring failure")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, engine.closes)
        assertNull("no report is persisted for the failed model", store.load(spec.id))
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun cancellationClosesEngineAndReleasesOwner() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(generateDelayMs = 10_000L, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec)) }
        delay(100)
        job.cancelAndJoin()
        assertEquals(1, engine.closes)
        assertTrue(events.contains("end"))
        // Cleanup ordering is preserved: the engine closes before the gate is released.
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun scoresBindModelFileIdentityAndSuiteVersion() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val report = ToolReliabilityScorer.scoreModel(
            "M", ToolReliabilityFixtures.all(), FakeToolCallRunner(),
            modelFingerprint = "fp-1"
        )
        store.save(report, ranAtMs = 1L)
        val stored = store.load("M")!!
        assertEquals("fp-1", stored.modelFingerprint)
        assertEquals(ToolReliabilityFixtures.SUITE_VERSION, stored.suiteVersion)
        assertNotNull(store.loadCurrent("M", "fp-1", ToolReliabilityFixtures.SUITE_VERSION))
    }

    @Test fun staleScoresAreNotPresentedAsCurrent() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val report = ToolReliabilityScorer.scoreModel(
            "M", ToolReliabilityFixtures.all(), FakeToolCallRunner(),
            modelFingerprint = "fp-1"
        )
        store.save(report, ranAtMs = 1L)
        val version = ToolReliabilityFixtures.SUITE_VERSION
        assertNotNull(store.loadCurrent("M", "fp-1", version))
        assertNull("replaced weights must invalidate the score", store.loadCurrent("M", "fp-2", version))
        assertNull("changed fixtures/scorer must invalidate the score", store.loadCurrent("M", "fp-1", version + 1))
        assertNull("unknown model identity must invalidate the score", store.loadCurrent("M", null, version))
        // A score saved without binding (legacy) is never presented as current.
        val legacy = ToolReliabilityScorer.scoreModel("L", ToolReliabilityFixtures.all(), FakeToolCallRunner())
        store.save(legacy, ranAtMs = 1L)
        assertNull(store.loadCurrent("L", "fp-1", version))
    }

    @Test fun admissionAcquiresGateBeforeConsultingBusy() {
        // Regression for the admission race: the gate must be acquired BEFORE
        // the session-busy state is consulted, so the two cannot interleave on
        // Dispatchers.Default and the check can never close the idle engine
        // out from under a session that became busy between check and acquire.
        val order = mutableListOf<String>()
        val admitted = admitReliabilityCheck(
            acquireGate = { order += "acquire"; true },
            releaseGate = { order += "release" },
            isBusy = { order += "busy"; false }
        )
        assertTrue(admitted)
        assertEquals(listOf("acquire", "busy"), order)
    }

    @Test fun admissionReleasesGateWhenSessionBusy() {
        val order = mutableListOf<String>()
        val admitted = admitReliabilityCheck(
            acquireGate = { order += "acquire"; true },
            releaseGate = { order += "release" },
            isBusy = { order += "busy"; true }
        )
        assertTrue(!admitted)
        assertEquals(listOf("acquire", "busy", "release"), order)
    }

    @Test fun admissionSkipsBusyCheckWhenGateUnavailable() {
        var busyConsulted = false
        val admitted = admitReliabilityCheck(
            acquireGate = { false },
            releaseGate = { fail("gate was never acquired") },
            isBusy = { busyConsulted = true; false }
        )
        assertTrue(!admitted)
        assertTrue(!busyConsulted)
    }

    @Test fun idleRecheckAbortsWhenSessionBecameBusy() {
        try {
            checkIdleBeforeClose { true }
            fail("expected abort when the session became busy after admission")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("became busy"))
        }
        // Idle still passes silently.
        checkIdleBeforeClose { false }
    }

    @Test fun busyAfterAdmissionAbortsBeforeEngineClose() = runBlocking {
        // The session became busy between admission and the idle-engine close:
        // the check must abort instead of tearing down the live engine.
        val events = mutableListOf<String>()
        val owner = object : FakeOwner(events = events) {
            override fun tryBeginModel(spec: LocalModelSpec): Boolean {
                events += "tryBegin"
                return true
            }
            override fun closeIdleEngine() {
                events += "closeIdle"
                checkIdleBeforeClose { true } // session went busy after admission
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected abort")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("became busy"))
        }
        assertEquals(0, factoryCalls)
        assertEquals(listOf("tryBegin", "closeIdle", "end"), events)
    }

    @Test fun cancellationDuringVerificationNeverAllocatesEngine() = runBlocking {
        // Regression: a cancellation landing during the synchronous model-file
        // verification must not be followed by native engine allocation/init.
        val events = mutableListOf<String>()
        var jobToCancel: Job? = null
        val owner = object : FakeOwner(events = events) {
            override fun verifyModelFile(spec: LocalModelSpec): Boolean {
                events += "verify"
                jobToCancel?.cancel()
                return true
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec)) }
        jobToCancel = job
        job.join()
        // The post-verification ensureActive() fired: cancelling the child
        // does not make the active parent's join() throw, so cancellation is
        // asserted directly — and no native engine was allocated afterwards.
        assertTrue(job.isCancelled)
        assertEquals(0, factoryCalls)
        assertEquals(listOf("tryBegin", "closeIdle", "verify", "end"), events)
    }

    @Test fun engineCloseFailureStopsRunAndSurfacesError() = runBlocking {
        // A close failure with no primary failure in flight stops the
        // benchmark run and surfaces: the error propagates, the failure is
        // reported to diagnostics, and the gate is still released.
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failOnClose = true, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected the close failure to surface")
        } catch (e: RuntimeException) {
            assertEquals("close boom", e.message)
        }
        assertEquals(1, owner.teardownIssues.size)
        assertTrue(owner.teardownIssues.single().contains("TEST-MODEL"))
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun engineCloseFailureStopsBenchmarkBeforeNextEngineAllocated() = runBlocking {
        // Regression (item 6): a teardown failure must stop the benchmark
        // before another native engine is allocated.
        val spec2 = LocalModelSpec(
            id = "TEST-MODEL-2",
            fileName = "test-model-2.litertlm",
            recommendedGpu = false
        )
        val owner = FakeOwner()
        var factoryCalls = 0
        val failingEngine = RecordingEngine(failOnClose = true)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec ->
                factoryCalls++
                if (factoryCalls == 1) failingEngine else error("no engine")
            },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec, spec2))
            fail("expected the close failure to surface")
        } catch (e: RuntimeException) {
            assertEquals("close boom", e.message)
        }
        assertEquals(1, factoryCalls)
        assertEquals(1, owner.teardownIssues.size)
    }

    @Test fun engineCloseFailureDoesNotMaskCancellation() = runBlocking {
        // Cancellation is the primary failure: the close failure is reported
        // but must not mask the cancellation.
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(generateDelayMs = 10_000L, failOnClose = true, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec)) }
        delay(100)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, owner.teardownIssues.size)
        assertTrue(owner.teardownIssues.single().contains("close boom"))
        assertTrue(events.contains("end"))
    }

    @Test fun engineCloseFailureDoesNotMaskOriginalFailure() = runBlocking {
        // Init fails AND close fails: the original failure propagates, the
        // close failure is reported, and the gate is still released.
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failOnInitialize = true, failOnClose = true, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected init failure")
        } catch (e: RuntimeException) {
            assertEquals("init boom", e.message)
        }
        assertEquals(1, owner.teardownIssues.size)
        assertTrue(owner.teardownIssues.single().contains("close boom"))
        assertTrue(events.contains("end"))
    }

    @Test fun conversationAdmissionAcquiresGateBeforeMarkingActive() {
        // The gate must be acquired BEFORE the session marks itself active,
        // and released once the claim is taken: this is the single atomic
        // admission mechanism shared with the reliability check.
        val order = mutableListOf<String>()
        val admitted = admitConversationTurn(
            acquireGate = { order += "acquire"; true },
            releaseGate = { order += "release" },
            markActive = { order += "mark"; true }
        )
        assertEquals(ConversationAdmission.ADMITTED, admitted)
        assertEquals(listOf("acquire", "mark", "release"), order)
    }

    @Test fun conversationAdmissionReportsGateBusyWithoutMarking() {
        var marked = false
        val admitted = admitConversationTurn(
            acquireGate = { false },
            releaseGate = { fail("gate was never acquired") },
            markActive = { marked = true; true }
        )
        assertEquals(ConversationAdmission.GATE_BUSY, admitted)
        assertTrue(!marked)
    }

    @Test fun conversationAdmissionReleasesGateWhenSessionBusy() {
        val order = mutableListOf<String>()
        val admitted = admitConversationTurn(
            acquireGate = { order += "acquire"; true },
            releaseGate = { order += "release" },
            markActive = { order += "mark"; false }
        )
        assertEquals(ConversationAdmission.SESSION_BUSY, admitted)
        assertEquals(listOf("acquire", "mark", "release"), order)
    }

    @Test fun cancellationBetweenModelsStopsLoop() = runBlocking {
        // Regression: a cancellation that lands between model attempts
        // (after one model's gate release) must stop the loop at the
        // model-loop entry check before the next model is admitted.
        val spec2 = LocalModelSpec("TEST-MODEL-2", "test-model-2.litertlm", recommendedGpu = false)
        val events = mutableListOf<String>()
        var jobToCancel: Job? = null
        var attempts = 0
        val owner = object : FakeOwner(events = events) {
            override fun endModelOperation() {
                super.endModelOperation()
                attempts++
                if (attempts == 1) jobToCancel?.cancel()
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; RecordingEngine() },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec, spec2)) }
        jobToCancel = job
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, factoryCalls)
        assertTrue(events.contains("end"))
    }

    @Test fun cancellationDuringAllocationNeverInitializes() = runBlocking {
        // Regression: a cancellation that lands during the synchronous
        // engine allocation must be caught by the initialization-boundary
        // check before the native engine is initialized.
        val events = mutableListOf<String>()
        var jobToCancel: Job? = null
        val owner = FakeOwner(events = events)
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec ->
                factoryCalls++
                jobToCancel?.cancel()
                RecordingEngine(events = events)
            },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec)) }
        jobToCancel = job
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, factoryCalls)
        assertTrue(
            "initialize must not run after cancellation during allocation",
            events.none { it == "initialize" }
        )
        assertTrue(events.contains("end"))
    }

    @Test fun closeCancellationDoesNotReplacePrimaryFailure() = runBlocking {
        // Ordinary primary failure plus cancellation during close: the
        // primary propagates and the close cancellation is attached as
        // suppressed, never replacing the primary.
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = object : ToolCallEngine by RecordingEngine(failOnInitialize = true, events = events) {
            override fun close() {
                events += "close"
                throw CancellationException("close cancelled")
            }
        }
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected init failure")
        } catch (e: RuntimeException) {
            assertEquals("init boom", e.message)
            assertTrue(
                "close cancellation must be suppressed on the primary",
                e.suppressed.any { it is CancellationException && it.message == "close cancelled" }
            )
        }
        assertEquals(1, owner.teardownIssues.size)
        assertTrue(owner.teardownIssues[0].contains("close cancelled"))
        assertTrue(events.contains("end"))
    }

    @Test fun primaryFailureCloseErrorAndThrowingDiagnosticsKeepWinnerIdentity() = runBlocking {
        // Primary failure + close error + a diagnostics recorder that throws:
        // the exact primary instance propagates, and the close error and the
        // diagnostic error are attached to it as distinct suppressed errors —
        // a throwing recorder never replaces the primary. The gate is still
        // released and no further engine is allocated.
        val events = mutableListOf<String>()
        val primary = RuntimeException("init boom")
        val closeError = RuntimeException("close boom")
        val diagnostic = IllegalStateException("diagnostics boom")
        val owner = object : FakeOwner(events = events) {
            override fun reportTeardownIssue(message: String) {
                throw diagnostic
            }
        }
        val spec2 = LocalModelSpec("TEST-MODEL-2", "test-model-2.litertlm", recommendedGpu = false)
        val engine = object : ToolCallEngine by RecordingEngine(events = events) {
            override suspend fun initialize() {
                throw primary
            }
            override fun close() {
                events += "close"
                throw closeError
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { factoryCalls++; engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val caught: Throwable = try {
            benchmark.runModels(listOf(spec, spec2))
            fail("expected init failure")
            throw AssertionError("unreachable")
        } catch (e: RuntimeException) {
            e
        }
        assertTrue("the exact primary instance must propagate", caught === primary)
        assertEquals(2, caught.suppressed.size)
        assertTrue("close error must be suppressed on the primary", caught.suppressed[0] === closeError)
        assertTrue("diagnostic error must be suppressed on the primary", caught.suppressed[1] === diagnostic)
        assertTrue(caught.suppressed[0] !== caught.suppressed[1])
        assertEquals("no next native engine is allocated after the teardown failure", 1, factoryCalls)
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun cancellationCloseErrorAndThrowingDiagnosticsKeepCancellation() = runBlocking {
        // Cancellation is the primary failure: with a close error and a
        // throwing diagnostics recorder, the cancellation still propagates —
        // neither the close error nor the diagnostic error may replace it.
        val events = mutableListOf<String>()
        val closeError = RuntimeException("close boom")
        val diagnostic = IllegalStateException("diagnostics boom")
        val owner = object : FakeOwner(events = events) {
            override fun reportTeardownIssue(message: String) {
                throw diagnostic
            }
        }
        val spec2 = LocalModelSpec("TEST-MODEL-2", "test-model-2.litertlm", recommendedGpu = false)
        val engine = object : ToolCallEngine by RecordingEngine(generateDelayMs = 10_000L, events = events) {
            override fun close() {
                events += "close"
                throw closeError
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { factoryCalls++; engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec, spec2)) }
        delay(100)
        job.cancelAndJoin()
        assertTrue("the primary cancellation must survive the close and diagnostic errors", job.isCancelled)
        assertEquals(1, factoryCalls)
        assertTrue(events.contains("end"))
    }

    @Test fun closeOnlyFailureWithThrowingDiagnosticsSurfacesTeardown() = runBlocking {
        // No primary failure: the close error is thrown, and the diagnostic
        // error from the throwing recorder is suppressed on it — never
        // replacing it. The gate is released and the run stops before the
        // next engine is allocated.
        val events = mutableListOf<String>()
        val closeError = RuntimeException("close boom")
        val diagnostic = IllegalStateException("diagnostics boom")
        val owner = object : FakeOwner(events = events) {
            override fun reportTeardownIssue(message: String) {
                throw diagnostic
            }
        }
        val spec2 = LocalModelSpec("TEST-MODEL-2", "test-model-2.litertlm", recommendedGpu = false)
        val engine = object : ToolCallEngine by RecordingEngine(events = events) {
            override fun close() {
                events += "close"
                throw closeError
            }
        }
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { factoryCalls++; engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val caught: Throwable = try {
            benchmark.runModels(listOf(spec, spec2))
            fail("expected the close failure to surface")
            throw AssertionError("unreachable")
        } catch (e: RuntimeException) {
            e
        }
        assertTrue("the exact teardown error must be thrown when there is no primary", caught === closeError)
        assertEquals(1, caught.suppressed.size)
        assertTrue("the diagnostic error must be suppressed on the teardown error", caught.suppressed[0] === diagnostic)
        assertEquals("no next native engine is allocated after the teardown failure", 1, factoryCalls)
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun identicalPrimaryAndTeardownIsNotSelfSuppressed() = runBlocking {
        // The close throws the identical Throwable instance as the primary
        // failure: self-suppression is guarded, so the winner propagates
        // unchanged instead of the guard itself throwing and masking it.
        val events = mutableListOf<String>()
        val shared = RuntimeException("shared boom")
        val owner = FakeOwner(events = events)
        val engine = object : ToolCallEngine by RecordingEngine(events = events) {
            override suspend fun initialize() {
                throw shared
            }
            override fun close() {
                events += "close"
                throw shared
            }
        }
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val caught: Throwable = try {
            benchmark.runModels(listOf(spec))
            fail("expected the shared failure to surface")
            throw AssertionError("unreachable")
        } catch (e: RuntimeException) {
            e
        }
        assertTrue("the exact winner instance must propagate", caught === shared)
        assertEquals("identical primary and teardown must not be attached", 0, caught.suppressed.size)
        assertEquals(1, owner.teardownIssues.size)
        assertTrue(owner.teardownIssues[0].contains("shared boom"))
        assertTrue(events.contains("end"))
    }
}
