package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.conversation.ConversationSessionState
import com.battlesbudz.jarvis.v2.diagnostics.*
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.runtime.AcceptedActionConversation
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceActionCoordinator
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.voice.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the shipping turn runner/finalizer/controller. Only native preparation is injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceTurnRunnerTest {
    @After fun resetUi() {
        VoiceSessionUi.failure.value = null
        VoiceSessionUi.status.value = ""
        VoiceSessionUi.phase.value = VoicePhase.IDLE
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
        VoiceSessionUi.paused.value = false
    }

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        private val suffix = UUID.randomUUID().toString()
        private fun preferences(name: String) = context.getSharedPreferences("$name-$suffix", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = ConversationHistory(preferences("history"))
        private val persistedCalls = SharedPreferencesVoiceCallStore(preferences("calls"))
        var failNextCheckpoint = false
        var failedCheckpointCallId: String? = null
        val store = object : VoiceCallStore by persistedCalls {
            override fun save(call: VoiceCallRecord) {
                if (failNextCheckpoint) {
                    failNextCheckpoint = false
                    failedCheckpointCallId = call.id
                    throw IOException("checkpoint write failed")
                }
                persistedCalls.save(call)
            }
        }
        val controller = VoiceSessionController(store)
        val state = VoiceCallState()
        private val posted = ConcurrentLinkedQueue<() -> Unit>()
        private val firstPost = CompletableDeferred<Unit>()
        val completions = mutableListOf<String>()
        val order = mutableListOf<String>()
        var serviceStops = 0
        var restarts = 0
        var failures = 0
        var submissions = 0
        var releases = 0
        var microphoneStops = 0
        lateinit var observation: VoiceTurnObservation
        lateinit var lifetime: VoiceTurnLifetime
        val call = VoiceCallAccess(state, controller, VoiceCallEvents(
            post = { posted.add(it); firstPost.complete(Unit) },
            report = {}, serviceStatus = VoiceSessionUi::report,
            transcript = { _, _, _ -> error("Startup failure must not deliver a transcript") },
            finished = { completions += it; order += "finished" }, startDiagnostics = {},
            endCall = {
                state.armed = false
                VoiceSessionUi.sessionAlive.value = false
                controller.currentCallId()?.let { controller.end() }
            }, returnToWake = {},
            stopService = { serviceStops++; order += "service stopped" }, restartTurn = { restarts++ }))
        private val recorder = DiagnosticRecorder(preferences("diagnostics"))
        private val benchmarkStore = AndroidPipelineBenchmarkStore(context)
        private val benchmarks = PipelineBenchmarks(
            { PipelineBenchmarkInput(LocalModelSpec("test", "test.litertlm", recommendedGpu = false), 0,
                history.current.value.id) },
            object : PipelineBenchmarkResources {
                override fun provenance(models: Map<String, PipelineBenchmarkModel>, configuration: Map<String, String>) =
                    PipelineBenchmarkProvenance("startup-test", 1, models = models, configuration = configuration)
                override fun sample() = emptyMap<String, Double?>()
                override fun batteryPercent(): Int? = null
            })
        private val actions = AcceptedVoiceActionCoordinator(scope, { error("No accepted work") }, {},
            { controller }, { store }, { benchmarkStore },
            { id, channel -> benchmarks.create(id, channel) }, benchmarks::finishResources, {}, {},
            AcceptedActionConversation { _, _, _ -> error("No accepted work") })
        private val conversation = VoiceConversationAccess(VoiceConversationDispatch { _, _ ->
            submissions++
            error("Startup failure must not submit to Gemma")
        }, { null }, {})
        private val memory = VoiceMemoryAccess(MemoryDeliveryFence(), VoiceMemoryDeliveryOwner()) { _, _, _, _, _, _ -> false }
        private val resources = RuntimeVoiceResources(context, scope, {}, {})
        private val nativeState = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = null
            override var hasContext = false
            override var characters = 0
        }
        private val orchestrator = TurnOrchestrator(ReferenceGroundingClient(
            readBytes = { error("Startup failure must not perform network lookup") }))
        private val typedInputs = VoiceTypedInputOwnership(call)
        private val createLease = { VoiceTurnModelLease({ true }, { releases++; order += "lease released" }) }
        private val asr = AsrComparisonStore(preferences("asr"))
        private val tts = TtsComparisonStore(preferences("tts"))
        private val replyCapture = ReplyCaptureBenchmark(context, benchmarks, benchmarkStore, controller::currentCallId, {})
        private val microphone = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = emptyFlow<ByteArray>()
            override suspend fun start() {}
            override suspend fun stop() { microphoneStops++; order += "microphone stopped" }
        }

        suspend fun acquireStartupResources(lifetime: VoiceTurnLifetime) {
            check(lifetime.modelLease.acquireWhenIdle(true))
            lifetime.microphone = microphone
            microphone.start()
            VoiceTurnPreparation.beginOwnedCall(controller, history.current.value.id, lifetime)
            lifetime.wokeThisTurn = true
        }

        fun start(prepare: suspend (VoiceTurnLifetime) -> PreparedVoiceTurn) {
            state.armed = true
            VoiceSessionUi.sessionAlive.value = true
            val runner = VoiceTurnRunner(scope, call, actions, orchestrator, { emptyList() },
                selectRequest = { VoiceTurnRequest(null, null, true, false, AsrEngine.WHISPER,
                    TtsEngine.PIPER_NORTHERN, UUID.randomUUID().toString()) },
                createObservation = { request -> VoiceTurnObservation(context, request, history.current.value.id,
                    benchmarkStore, benchmarks, recorder, asr, tts, store).also { observation = it } },
                createModelLease = createLease,
                typedStage = TypedVoiceInputStage(scope, call, conversation, history, memory, createLease, { false }, recorder),
                typedInputs = typedInputs, preparation = { _, _, ownedLifetime ->
                    lifetime = ownedLifetime
                    prepare(ownedLifetime)
                },
                recognition = VoiceTurnRecognition(call, conversation, resources, history, memory, orchestrator, recorder, asr),
                acceptedReplies = AcceptedVoiceFollowupStage(call, scope, actions, replyCapture, benchmarks,
                    resources, history, memory, orchestrator, recorder),
                ordinaryReplies = OrdinaryVoiceReplyStage(call, scope, conversation, replyCapture, resources,
                    history, memory, orchestrator, recorder, asr),
                finalizer = VoiceTurnFinalizer(call, conversation, nativeState, actions, resources, memory,
                    orchestrator, typedInputs, recorder), diagnosticRecorder = recorder,
                currentConversationId = { history.current.value.id },
                onTerminalFailure = { failures++; order += "failure published" })
            runner.start()
        }

        fun startMissingAsset() = start { lifetime ->
            acquireStartupResources(lifetime)
            // The real Android adapter throws before any Gemma request, as on the reported phone.
            context.assets.open("gemma_streaming/missing-startup-regression.bin").use { }
            error("The deliberately absent startup asset must fail")
        }

        suspend fun awaitCleanup(expectPost: Boolean = true) = withTimeout(5_000) {
            requireNotNull(state.turnJob).join()
            if (expectPost) firstPost.await()
        }
        fun publishPosted() { while (true) (posted.poll() ?: break).invoke() }
        override fun close() { scope.cancel() }
    }

    @Test fun actualAssetOpenFailureCleansUpAndRemainsVisibleWithoutInferenceOrRestart() = runBlocking {
        Fixture().use { f ->
            f.startMissingAsset()
            f.awaitCleanup()
            assertEquals(1, f.releases)
            assertEquals(1, f.microphoneStops)
            assertNull(f.controller.currentCallId())
            assertNotNull(f.store.list().single().endedAtMs)
            assertEquals(f.store.list().single().id, f.lifetime.expectedResourceCall)
            assertNull(VoiceSessionUi.failure.value)
            f.publishPosted()

            val failure = requireNotNull(VoiceSessionUi.failure.value)
            assertEquals(f.history.current.value.id, failure.conversationId)
            assertTrue(failure.message.contains("missing-startup-regression.bin"))
            assertEquals(failure.message, f.completions.single())
            assertEquals(PipelineBenchmarkOutcome.ERROR, f.observation.outcome)
            assertEquals(FileNotFoundException::class.java.simpleName, f.observation.failure)
            assertFalse(f.state.armed)
            assertFalse(VoiceSessionUi.sessionAlive.value)
            assertEquals(1, f.serviceStops)
            assertEquals(1, f.failures)
            assertEquals(0, f.restarts)
            assertEquals(0, f.submissions)
            assertEquals(listOf("microphone stopped", "lease released", "failure published", "finished", "service stopped"), f.order)
            VoiceSessionUi.report("Jarvis session stopped — microphone off.")
            assertEquals(failure, VoiceSessionUi.failure.value)
        }
    }

    @Test fun checkpointFailureStillRegistersAndClosesTheAttemptedCall() = runBlocking {
        Fixture().use { f ->
            f.failNextCheckpoint = true
            f.startMissingAsset()
            f.awaitCleanup()
            assertNotNull(f.failedCheckpointCallId)
            assertEquals(f.failedCheckpointCallId, f.lifetime.expectedResourceCall)
            assertNull(f.controller.currentCallId())
            val ended = f.store.list().single()
            assertEquals(f.failedCheckpointCallId, ended.id)
            assertNotNull(ended.endedAtMs)
            assertEquals(1, f.releases)
            assertEquals(1, f.microphoneStops)
            f.publishPosted()
            assertEquals(IOException::class.java.simpleName, f.observation.failure)
            assertEquals(PipelineBenchmarkOutcome.ERROR, f.observation.outcome)
            assertTrue(requireNotNull(VoiceSessionUi.failure.value).message.contains("checkpoint write failed"))
            assertEquals(1, f.serviceStops)
            assertEquals(0, f.restarts)
            assertEquals(0, f.submissions)
        }
    }

    @Test fun replacementPresentBeforeOwnedBeginIsNeverAdoptedOrEnded() = runBlocking {
        Fixture().use { f ->
            var replacementId: String? = null
            f.start { lifetime ->
                replacementId = f.controller.beginCall(f.history.current.value.id).id
                VoiceTurnPreparation.beginOwnedCall(f.controller, f.history.current.value.id, lifetime)
                error("An existing replacement must reject this attempted start")
            }
            f.awaitCleanup()
            f.publishPosted()
            assertNull(f.lifetime.expectedResourceCall)
            assertEquals(replacementId, f.controller.currentCallId())
            assertNull(f.store.list().single().endedAtMs)
            assertTrue(f.state.turnJob!!.isCancelled)
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, f.observation.outcome)
            assertTrue(f.state.armed)
            assertNull(VoiceSessionUi.failure.value)
            assertEquals(0, f.releases)
            assertEquals(0, f.serviceStops)
            assertEquals(0, f.failures)
            assertEquals(0, f.submissions)
        }
    }

    @Test fun explicitStopPropagatesCancellationAndNeverPublishesAFailure() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            f.start { lifetime ->
                f.acquireStartupResources(lifetime)
                started.complete(Unit)
                awaitCancellation()
            }
            withTimeout(5_000) { started.await() }
            f.state.armed = false
            VoiceSessionUi.sessionAlive.value = false
            f.controller.end()
            f.state.turnJob!!.cancel(CancellationException("user stopped call"))
            f.awaitCleanup(expectPost = false)
            f.publishPosted()
            assertTrue(f.state.turnJob!!.isCancelled)
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, f.observation.outcome)
            assertEquals(1, f.releases)
            assertEquals(1, f.microphoneStops)
            assertNull(VoiceSessionUi.failure.value)
            assertEquals(0, f.failures)
            assertEquals(0, f.restarts)
        }
    }

    @Test fun explicitStopAlsoWinsWhenCancelledPreparationThrowsAnIoFailure() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            f.start { lifetime ->
                f.acquireStartupResources(lifetime)
                started.complete(Unit)
                try { awaitCancellation() }
                finally { throw FileNotFoundException("interrupted blocking setup") }
            }
            withTimeout(5_000) { started.await() }
            f.state.armed = false
            VoiceSessionUi.sessionAlive.value = false
            f.controller.end()
            f.state.turnJob!!.cancel()
            f.awaitCleanup(expectPost = false)
            f.publishPosted()
            assertTrue(f.state.turnJob!!.isCancelled)
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, f.observation.outcome)
            assertEquals(1, f.releases)
            assertNull(VoiceSessionUi.failure.value)
            assertEquals(0, f.failures)
            assertEquals(0, f.serviceStops)
        }
    }

    @Test fun delayedFailureCannotDisarmOrReportAgainstAReplacementCallOrTurn() = runBlocking {
        for ((replaceCall, replaceTurn) in listOf(true to false, false to true, true to true)) Fixture().use { f ->
            f.startMissingAsset()
            f.awaitCleanup()
            val replacement = if (replaceCall) f.controller.beginCall(f.history.current.value.id) else null
            if (replaceTurn) f.state.turnJob = Job().apply { complete() }
            f.publishPosted()
            assertEquals(replacement?.id, f.controller.currentCallId())
            assertTrue(f.state.armed)
            assertTrue(VoiceSessionUi.sessionAlive.value)
            assertNull(VoiceSessionUi.failure.value)
            assertEquals(0, f.failures)
            assertEquals(0, f.serviceStops)
            assertTrue(f.completions.isEmpty())
        }
    }

    @Test fun failureBeforeWakeDoesNotInventACallButStillExplainsWhyStartupStopped() = runBlocking {
        Fixture().use { f ->
            f.start { throw IllegalStateException("Model initialization failed") }
            f.awaitCleanup()
            f.publishPosted()
            assertNull(f.controller.currentCallId())
            assertTrue(f.store.list().isEmpty())
            assertEquals(0, f.releases)
            assertEquals(0, f.microphoneStops)
            assertEquals(0, f.submissions)
            assertEquals(1, f.serviceStops)
            assertTrue(requireNotNull(VoiceSessionUi.failure.value).message.contains("Model initialization failed"))
        }
    }

    @Test fun explicitStopBeforeQueuedFailurePublicationDoesNotShowAStaleError() = runBlocking {
        Fixture().use { f ->
            f.startMissingAsset()
            f.awaitCleanup()
            f.state.armed = false
            VoiceSessionUi.sessionAlive.value = false
            f.publishPosted()
            assertNull(VoiceSessionUi.failure.value)
            assertTrue(f.completions.isEmpty())
            assertEquals(0, f.failures)
            assertEquals(0, f.serviceStops)
        }
    }

    @Test fun unexpectedChildCancellationKeepsExistingRecoveryAndDoesNotBecomeAnError() = runBlocking {
        Fixture().use { f ->
            f.start { lifetime ->
                f.acquireStartupResources(lifetime)
                throw CancellationException("capture interrupted")
            }
            f.awaitCleanup()
            f.publishPosted()
            assertTrue(f.state.turnJob!!.isCancelled)
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, f.observation.outcome)
            assertEquals(1, f.releases)
            assertEquals(1, f.restarts)
            assertEquals(0, f.serviceStops)
            assertTrue(f.state.armed)
            assertNotNull(f.controller.currentCallId())
            assertNull(VoiceSessionUi.failure.value)
        }
    }

    @Test fun replacedCallIsNotInterruptedWhenOldPreparationFails() = runBlocking {
        Fixture().use { f ->
            var replacementId: String? = null
            f.start { lifetime ->
                f.acquireStartupResources(lifetime)
                f.controller.end()
                replacementId = f.controller.beginCall(f.history.current.value.id).id
                throw FileNotFoundException("late failure from old setup")
            }
            f.awaitCleanup()
            f.publishPosted()
            assertEquals(replacementId, f.controller.currentCallId())
            assertTrue(f.state.armed)
            assertNull(VoiceSessionUi.failure.value)
            assertEquals(0, f.serviceStops)
        }
    }
}
