package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import com.google.ai.edge.litertlm.*
import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.os.Looper
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Android adapter for a .litertlm model.
 *
 * Initialization and generation must be called from a background coroutine.
 * The model file is supplied by setup/model delivery and is never committed
 * to the repository.
 */
class LiteRtLmEngine(
    override val modelId: String,
    modelPath: String,
    cacheDir: String,
    useGpu: Boolean,
    private val tools: List<OpenApiTool> = emptyList(),
    val visionEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
    private val speculativeDecoding: Boolean? = null
) : LocalModelEngine, Closeable, ToolCallEngine {
    private companion object {
        val initializationLock = Any()
        val callbackDepth = ThreadLocal.withInitial { 0 }
        val quarantineLock = Any()
        val retainedNativeEngines = mutableSetOf<LiteRtLmEngine>()
        var quarantineAdmissionRetained = false
        fun checkNativeWorkerThread() {
            check(callbackDepth.get() == 0) { "Native lifecycle control is forbidden in model callbacks" }
            check(Looper.myLooper() != Looper.getMainLooper()) { "Native lifecycle control requires a worker thread" }
        }
        inline fun nativeCallback(block: () -> Unit) {
            callbackDepth.set(callbackDepth.get() + 1)
            try { block() } finally { callbackDepth.set(callbackDepth.get() - 1) }
        }
    }
    private val modelSpec = ModelCatalog.resolve(modelId)
    @OptIn(ExperimentalApi::class)
    private val engine = synchronized(initializationLock) {
        // The SDK reads this global flag while constructing the native engine.
        // Keep it scoped to our constructor and restore it on every exit.
        val previousBenchmark = ExperimentalFlags.enableBenchmark
        try {
            ExperimentalFlags.enableBenchmark = true
            Engine(EngineConfig(
                modelPath = modelPath,
                cacheDir = java.io.File(cacheDir, modelId).apply { mkdirs() }.path,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                visionBackend = if (visionEnabled) Backend.GPU() else null,
                audioBackend = if (audioEnabled) Backend.CPU() else null,
                maxNumImages = if (visionEnabled) 1 else null,
                maxNumTokens = modelSpec.contextTokens
            ))
        } finally { ExperimentalFlags.enableBenchmark = previousBenchmark }
    }
    private val lifecycle = CheckedConversationLifecycle(
        createConversation = ::createConversation,
        cancelConversation = { it.cancelProcess() },
        awaitIdle = { it.awaitIdle() },
        closeConversation = { it.close() },
        closeEngine = { if (engine.isInitialized()) engine.close() },
        checkWorkerThread = ::checkNativeWorkerThread,
        onQuarantined = { retainProcessQuarantine() }
    )

    /**
     * Ordinary text jobs release their admission count in invokeOnCompletion too. Keep one
     * additional process reservation and the actual Engine strongly retained before that can
     * happen. This safety latch deliberately lasts until process exit, including after an
     * explicit disposal retry; it never permits a new inference or model-file replacement.
     */
    private fun retainProcessQuarantine() = synchronized(quarantineLock) {
        if (!quarantineAdmissionRetained) {
            ProcessConversationAdmission.activeJobs.incrementAndGet()
            quarantineAdmissionRetained = true
        }
        retainedNativeEngines.add(this)
        Unit
    }

    /** A terminal callback is never proof that the model lease can be released. */
    val nativeResourcesSafeToRelease: Boolean get() = lifecycle.safeToRelease
    val isNativeQuarantined: Boolean get() = lifecycle.isQuarantined
    /** True once engine close has been requested; a closed engine must not be reset or reused. */
    val isNativeClosed: Boolean get() = lifecycle.isClosed
    val requiresConfirmedHistoryRebuild: Boolean get() = lifecycle.requiresConfirmedHistoryRebuild

    /** Reports actual submissions, including incremental input, retries and recognition fallback. */
    var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
    /** Native submission/raw-callback timing; intentionally carries no generated text. */
    var onInferenceProgress: (InferenceProgress) -> Unit = {}
    /** Classification and text-free completion observer; copied before every native submission. */
    var benchmarkPurpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
    var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
    private val benchmarkNativeWorkStarted = AtomicBoolean(false)
    private var benchmarkInitializationMs: Long? = null
    private fun takeBenchmarkInitializationMs(): Long? = synchronized(this) {
        benchmarkInitializationMs.also { benchmarkInitializationMs = null }
    }

    private var nativeSession = 0L
    private var nativeSubmissions = 0
    fun inputContextDescription(): String =
        "nativeSession=$nativeSession nativePriorSubmissions=$nativeSubmissions toolsEnabled=$toolsEnabled " +
            "fixedVoicePreface=${if (fixedVoicePreface) GemmaVoicePreface.POLICY else "none"} " +
            "systemInstructionChars=${if (fixedVoicePreface) GemmaVoicePreface.instructions.length else 0}"

    internal fun createVoicePrefillSession(): com.battlesbudz.jarvis.v2.voice.VoicePrefillSession {
        checkNativeWorkerThread()
        val purpose = benchmarkPurpose
        val sink = onBenchmarkSubmission
        val progressSink = onInferenceProgress
        if (!modelSpec.incrementalGemmaInput) {
            return TemplateVoiceSession { prompt, onToken ->
                setToolsEnabled(false)
                resetConversation()
                onPromptSubmitted(prompt, 0)
                generateWithContents(Contents.of(prompt), onToken, prompt.length, "text", purpose = purpose, sink = sink)
            }
        }
        // Voice owns this engine exclusively. Do not allocate a second idle KV cache.
        lifecycle.resetForConfirmedHistory()
        val child = lifecycle.createChild({ engine.createSession() }) { it.awaitIdle(); it.close() }
        val native = LiteRtNativeVoiceSession(child.child,
            checkWorkerThread = ::checkNativeWorkerThread,
            runCallback = { block -> nativeCallback(block) })
        return LiteRtVoicePrefillSession(
            session = object : VoiceNativeSession by native {
                override fun awaitIdle() {
                    try { native.awaitIdle() }
                    catch (error: Throwable) { lifecycle.quarantineChild(child, error); throw error }
                }
                override fun close() = lifecycle.closeChild(child)
            },
            benchmarkModelId = modelId,
            benchmarkPurpose = purpose,
            benchmarkSink = sink,
            benchmarkInitializationMs = takeBenchmarkInitializationMs(),
            onBenchmarkNativeWorkStarted = { benchmarkNativeWorkStarted.set(true) },
            onInferenceProgress = progressSink
        )
    }

    private var fixedVoicePreface = false
    internal val hasFixedVoicePreface: Boolean get() = fixedVoicePreface
    private var toolsEnabled = false
    /** Read-only view of the native tool policy; never resets the conversation. */
    internal val toolsEnabledState: Boolean get() = toolsEnabled

    /** Synchronous SDK prefill; caller serializes encoder compute behind this operation. */
    internal suspend fun prepareVoiceConversation(isCurrent: () -> Boolean): Boolean {
        checkNativeWorkerThread()
        currentCoroutineContext().ensureActive()
        check(modelSpec.incrementalGemmaInput && audioEnabled && !toolsEnabled)
        if (!fixedVoicePreface) { resetConversation(); fixedVoicePreface = true }
        return try {
            val prepared = lifecycle.prepareConversation(isCurrent)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) { resetConversation(); false } else prepared
        } catch (cancelled: CancellationException) {
            resetConversation()
            throw cancelled
        }
    }

    /** Never preserve a used history merely to save preparation time. */
    internal suspend fun resetForVoiceSpeculation() {
        if (!lifecycle.hasUnusedPreparedConversation) resetConversation()
    }

    /** Called by the session owner before deciding whether to seed confirmed history. */
    internal suspend fun prepareOrdinaryConversation(): Boolean {
        if (!fixedVoicePreface) return false
        resetConversation()
        return true
    }

    private fun ordinaryPrompt(prompt: String): String {
        check(!fixedVoicePreface) { "Ordinary input mode must be prepared before prompt assembly" }
        return prompt
    }

    private suspend fun audioPrompt(prompt: String): String {
        val dynamic = if (modelSpec.incrementalGemmaInput && !toolsEnabled) GemmaVoicePreface.userText(prompt) else null
        val requested = dynamic != null
        if (fixedVoicePreface != requested) {
            check(!lifecycle.hasConversationHistory) { "Audio input mode change requires confirmed-history reset before prompt assembly" }
            resetConversation()
            fixedVoicePreface = requested
        }
        return dynamic ?: prompt
    }

    override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
        if (toolsEnabled == enabled && !(enabled && fixedVoicePreface)) return false
        resetConversation()
        if (enabled) fixedVoicePreface = false
        toolsEnabled = enabled
        return true
    }

    private fun createConversation() = engine.createConversation(
        modelConversationConfig(modelSpec, tools, toolsEnabled, fixedVoicePreface)
    )

    @OptIn(ExperimentalApi::class)
    override suspend fun initialize() {
        currentCoroutineContext().ensureActive()
        val initializationBeganAt = System.nanoTime()
        lifecycle.initialize {
            // initialize(), rather than Engine's constructor, reads this process-global flag.
            synchronized(initializationLock) {
                val previous = ExperimentalFlags.enableSpeculativeDecoding
                try {
                    ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
                    engine.initialize()
                } finally { ExperimentalFlags.enableSpeculativeDecoding = previous }
            }
        }
        benchmarkInitializationMs = (System.nanoTime() - initializationBeganAt) / 1_000_000
    }

    /** The next prompt must be rebuilt from confirmed history and actual tool receipts. */
    override suspend fun resetConversation() = withContext(NonCancellable) {
        lifecycle.resetForConfirmedHistory()
        fixedVoicePreface = false
        nativeSession++
        nativeSubmissions = 0
    }

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): GenerationResult {
        val userPrompt = ordinaryPrompt(prompt)
        onPromptSubmitted(userPrompt, 0)
        return generateWithContents(Contents.of(userPrompt), onToken, userPrompt.length, "text")
    }

    suspend fun generate(
        prompt: String,
        imageBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult {
        require(visionEnabled && modelSpec.supportsVision) { "$modelId does not support image input. Select a vision model." }
        val userPrompt = ordinaryPrompt(prompt)
        onPromptSubmitted(userPrompt, 0)
        return generateWithContents(Contents.of(Content.ImageBytes(imageBytes), Content.Text(userPrompt)), onToken,
            userPrompt.length, "image_text", imageBytes = imageBytes.size)
    }

    /**
     * Sends audio directly to the multimodal Gemma conversation.
     * The byte array should contain a supported audio file, preferably a
     * 16 kHz mono WAV for predictable on-device preprocessing.
     */
    suspend fun generateAudio(
        prompt: String,
        audioBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult {
        require(audioEnabled && modelSpec.supportsAudio) { "$modelId requires Whisper for speech recognition." }
        val userPrompt = audioPrompt(prompt)
        onPromptSubmitted(userPrompt, audioBytes.size)
        return generateWithContents(audioMessageContents(userPrompt, audioBytes), onToken,
            userPrompt.length, "audio_text", audioBytes = audioBytes.size)
    }

    /** Consume only a completed, fenced capture; SDK validation also forbids automatic tools. */
    suspend fun generateSealedAudio(
        prompt: String,
        artifact: Content.SealedAudioEmbeddings,
        onToken: (String) -> Unit,
        maxPendingCallbacks: Int = Channel.UNLIMITED
    ): GenerationResult {
        require(audioEnabled && modelSpec.supportsAudio) { "$modelId does not support native audio input." }
        val pcmBytes = artifact.pcmSampleCount * 2
        val userPrompt = audioPrompt(prompt)
        onPromptSubmitted(userPrompt, pcmBytes)
        return generateWithContents(Contents.of(Content.Text(userPrompt), artifact), onToken,
            userPrompt.length, "sealed_audio_text", audioBytes = pcmBytes, maxPendingCallbacks = maxPendingCallbacks)
    }

    private suspend fun generateWithContents(
        contents: Contents,
        onToken: (String) -> Unit,
        promptChars: Int,
        mode: String,
        audioBytes: Int = 0,
        imageBytes: Int = 0,
        purpose: PipelineBenchmarkPurpose = benchmarkPurpose,
        sink: (PipelineBenchmarkSubmission) -> Unit = onBenchmarkSubmission,
        maxPendingCallbacks: Int = Channel.UNLIMITED
    ): GenerationResult = generateWithMessage(Message.user(contents), onToken,
        promptChars, mode, audioBytes, imageBytes, purpose, sink, maxPendingCallbacks)

    suspend fun sendToolResult(
        call: ToolCall,
        resultMessage: String,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(
        Message.tool(Contents.of(Content.ToolResponse(call.name, resultMessage))),
        onToken, resultMessage.length, "tool_response", purpose = PipelineBenchmarkPurpose.TOOL
    )

    /** Keep a batch in one native tool message so no call/result is discarded. */
    suspend fun sendToolResults(
        results: List<Pair<ToolCall, String>>,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(
        Message.tool(Contents.of(*results.map { Content.ToolResponse(it.first.name, it.second) }.toTypedArray())),
        onToken, results.sumOf { it.second.length }, "tool_response", purpose = PipelineBenchmarkPurpose.TOOL
    )

    private suspend fun generateWithMessage(
        message: Message,
        onToken: (String) -> Unit,
        promptChars: Int,
        mode: String,
        audioBytes: Int = 0,
        imageBytes: Int = 0,
        purpose: PipelineBenchmarkPurpose = benchmarkPurpose,
        sink: (PipelineBenchmarkSubmission) -> Unit = onBenchmarkSubmission,
        maxPendingCallbacks: Int = Channel.UNLIMITED
    ): GenerationResult {
        require(maxPendingCallbacks == Channel.UNLIMITED || maxPendingCallbacks in 1..128)
        checkNativeWorkerThread()
        currentCoroutineContext().ensureActive()
        val startedAt = System.nanoTime()
        val progressSink = onInferenceProgress
        val benchmark = NativeInferenceBenchmark(modelId, purpose,
            if (benchmarkNativeWorkStarted.compareAndSet(false, true)) PipelineBenchmarkWarmState.COLD else PipelineBenchmarkWarmState.WARM,
            mode, audioBytes, imageBytes, takeBenchmarkInitializationMs(), sink)
        var firstTokenAt: Long? = null
        val output = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var streamEvents = 0
        val firstCallbackAt = java.util.concurrent.atomic.AtomicLong()
        val firstRawTextReported = java.util.concurrent.atomic.AtomicBoolean(false)
        var nativeSubmitMs: Long? = null
        var benchmarkOutcome = PipelineBenchmarkOutcome.ERROR
        var benchmarkError: Throwable? = null
        var nativeTokens: NativeTokenTelemetry? = null

        val responses = Channel<Message>(maxPendingCallbacks)
        val preparationStartedAt = System.nanoTime()
        val wasEarlyPrepared = lifecycle.hasUnusedPreparedConversation
        val nativeTurn = lifecycle.beginTurn()
        benchmark.measurement.conversationReady(preparationStartedAt, wasEarlyPrepared)
        val activeConversation = nativeTurn.conversation
        val ownerJob = currentCoroutineContext()[Job]
        nativeSubmissions++
        var completed = false
        try {
            try {
            currentCoroutineContext().ensureActive()
            val submittedAt = System.nanoTime() / 1_000_000
            progressSink(InferenceProgress(submittedAtMs = submittedAt))
            val nativeSubmitBeganAt = System.nanoTime()
            benchmark.measurement.submissionStarted(nativeSubmitBeganAt)
            try { activeConversation.sendMessageAsync(message, object : MessageCallback {
                override fun onMessage(message: Message) = nativeCallback {
                    val hasText = message.toString().isNotEmpty()
                    val callbackAt = benchmark.measurement.callback(hasText)
                    firstCallbackAt.compareAndSet(0L, callbackAt)
                    if (hasText && firstRawTextReported.compareAndSet(false, true))
                        progressSink(InferenceProgress(firstRawTokenAtMs = callbackAt / 1_000_000))
                    if (responses.trySend(message).isFailure)
                        responses.close(IllegalStateException("native_response_queue_capacity"))
                }
                override fun onDone() = nativeCallback {
                    try { benchmark.measurement.terminal() } finally { responses.close() }
                }
                override fun onError(throwable: Throwable) = nativeCallback {
                    try { benchmark.measurement.terminal() } finally { responses.close(throwable) }
                }
            }) } finally {
                benchmark.measurement.submitted(nativeSubmitBeganAt)
                nativeSubmitMs = (System.nanoTime() - nativeSubmitBeganAt) / 1_000_000
            }
            } catch (error: Throwable) {
                benchmark.measurement.terminal()
                throw error
            }
            for (response in responses) {
                val messageText = response.toString()
                benchmark.measurement.consumed(messageText.isNotEmpty())
                response.toolCalls.forEach {
                    toolCalls += ToolCall(it.name, JSONObject(it.arguments).toString())
                }
                if (messageText.isNotEmpty()) {
                    benchmark.measurement.visibleText()
                    firstTokenAt = firstTokenAt ?: System.nanoTime()
                    streamEvents++
                    output.append(messageText)
                    onToken(messageText)
                }
            }
            benchmark.measurement.consumerDrained()
            completed = true
        } catch (error: Throwable) {
            benchmarkError = error
            benchmarkOutcome = if (error is CancellationException) PipelineBenchmarkOutcome.CANCELLED else PipelineBenchmarkOutcome.ERROR
            throw error
        } finally {
            try {
                // This blocking SDK call includes the last native callback returning. Never
                // replace it with a callback latch, coroutine timeout, or unchecked destructor.
                val accepted = withContext(NonCancellable) {
                    lifecycle.finishTurn(nativeTurn,
                        successful = { completed && ownerJob?.isActive != false },
                        onDrained = { nativeTokens = readNativeTelemetry(it) })
                }
                if (accepted) benchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE
                else if (benchmarkError == null) {
                    throw CancellationException("Native turn cancelled while draining")
                }
            } catch (cleanupError: Throwable) {
                if (benchmarkError != null) {
                    if (cleanupError !== benchmarkError) benchmarkError.addSuppressed(cleanupError)
                } else {
                    benchmarkError = cleanupError
                    benchmarkOutcome = if (cleanupError is CancellationException)
                        PipelineBenchmarkOutcome.CANCELLED else PipelineBenchmarkOutcome.ERROR
                    throw cleanupError
                }
            } finally {
                responses.cancel()
                benchmark.finish(benchmarkOutcome, output.length, promptChars, streamEvents, error = benchmarkError,
                    nativeTokens = nativeTokens)
            }
        }

        val finishedAt = System.nanoTime()
        val firstTokenMs = firstTokenAt?.let { (it - startedAt) / 1_000_000 } ?: -1L
        val totalMs = (finishedAt - startedAt) / 1_000_000
        // LiteRT-LM currently exposes streamed text rather than token IDs on
        // Android. Four characters per token is a useful English estimate;
        // keep streamEvents separately so diagnostics remain honest.
        val estimatedTokens = output.toString().estimateTokenCount()
        val decodeMs = firstTokenAt?.let { finishedAt - it } ?: 0L
        return GenerationResult(
            text = output.toString(),
            timeToFirstTokenMs = firstTokenMs,
            decodeTokensPerSecond = if (decodeMs > 0 && estimatedTokens > 0) {
                estimatedTokens * 1_000.0 / (decodeMs / 1_000_000.0)
            } else null,
            outputTokens = estimatedTokens,
            totalGenerationTimeMs = totalMs,
            streamEvents = streamEvents,
            toolCalls = toolCalls,
            nativeSubmitMs = nativeSubmitMs,
            firstCallbackMs = firstCallbackAt.get().takeIf { it != 0L }?.let { (it - startedAt) / 1_000_000 }
        )
    }

    private fun String.estimateTokenCount(): Int =
        if (isBlank()) 0 else ((trim().length + 3) / 4).coerceAtLeast(streamEventsFallback())

    private fun String.streamEventsFallback(): Int =
        trim().split(Regex("\\s+")).count().coerceAtLeast(1)

    @OptIn(ExperimentalApi::class)
    private fun readNativeTelemetry(active: com.google.ai.edge.litertlm.Conversation): NativeTokenTelemetry? {
        val began = System.nanoTime()
        return runCatching {
            val measured = active.getBenchmarkInfo()
            NativeTokenTelemetry.checked(measured.lastPrefillTokenCount, measured.lastDecodeTokenCount,
                measured.timeToFirstTokenInSecond, measured.lastPrefillTokensPerSecond,
                measured.lastDecodeTokensPerSecond, (System.nanoTime() - began) / 1_000_000.0)
        }.getOrNull()
    }

    /** Must be called by the resource owner after child jobs join, off UI and callbacks. */
    override fun close() = lifecycle.close()

    /** Explicit recovery only; a failed attempt keeps both native resources and leases retained. */
    fun retryQuarantinedClose() = lifecycle.retryQuarantinedClose()
}

/**
 * The backend fallback ladder for native engine creation (reliability epic
 * M5, item 1). Engine creation used to be a single attempt on the catalog's
 * recommended backend: on a device where GPU creation fails, every creation
 * site failed every turn the same way, forever. The ladder tries the
 * requested backend once, retries once on CPU, and surfaces the FIRST
 * failure when both attempts fail — the requested backend's error is the
 * actionable one, so the fallback's error is kept as suppressed evidence.
 * Vision is never moved to CPU by the ladder: an engine created with vision
 * keeps its GPU vision backend on both rungs (the engine's own
 * configuration does that), so a fallback costs text speed, never vision
 * capability.
 *
 * A GPU failure is latched per model for the process: later creations of
 * the same model start on CPU instead of repaying the failed GPU attempt
 * on every rebuild (model switch, capability escalation). Cancellation is
 * never latched and never retried — it is the caller's decision, not a
 * backend verdict.
 */
internal class EngineBackendLadder {
    private val gpuFailedModelIds = mutableSetOf<String>()

    suspend fun <T> run(
        modelId: String,
        gpuRequested: Boolean,
        attempt: suspend (useGpu: Boolean) -> T
    ): T {
        if (!gpuRequested) return attempt(false)
        if (synchronized(gpuFailedModelIds) { modelId in gpuFailedModelIds }) return attempt(false)
        val firstFailure = try {
            return attempt(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error
        }
        synchronized(gpuFailedModelIds) { gpuFailedModelIds += modelId }
        try {
            return attempt(false)
        } catch (cancelled: CancellationException) {
            if (cancelled !== firstFailure) cancelled.addSuppressed(firstFailure)
            throw cancelled
        } catch (second: Throwable) {
            if (second !== firstFailure) firstFailure.addSuppressed(second)
            throw firstFailure
        }
    }
}

/** Creates [LiteRtLmEngine] instances through the [EngineBackendLadder]. */
internal object LiteRtLmEngineFactory {
    private val ladder = EngineBackendLadder()

    /** Construct only; the caller initializes (and owns closing) the engine. */
    suspend fun create(
        modelId: String,
        modelPath: String,
        cacheDir: String,
        useGpu: Boolean,
        tools: List<OpenApiTool> = emptyList(),
        visionEnabled: Boolean = false,
        audioEnabled: Boolean = false,
        speculativeDecoding: Boolean? = null
    ): LiteRtLmEngine = ladder.run(modelId, useGpu) { gpu ->
        LiteRtLmEngine(modelId, modelPath, cacheDir, gpu, tools, visionEnabled, audioEnabled, speculativeDecoding)
    }

    /**
     * Construct and initialize as one attempt: an attempt that fails either
     * step is closed before the ladder retries or rethrows, so a failed
     * initialize never leaks a native engine.
     */
    suspend fun createInitialized(
        modelId: String,
        modelPath: String,
        cacheDir: String,
        useGpu: Boolean,
        tools: List<OpenApiTool> = emptyList(),
        visionEnabled: Boolean = false,
        audioEnabled: Boolean = false,
        speculativeDecoding: Boolean? = null
    ): LiteRtLmEngine = ladder.run(modelId, useGpu) { gpu ->
        val engine = LiteRtLmEngine(modelId, modelPath, cacheDir, gpu, tools, visionEnabled, audioEnabled, speculativeDecoding)
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
        engine
    }
}
