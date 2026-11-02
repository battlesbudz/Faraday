package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.conversation.ConversationBackend
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable fake-engine test double for the reliability epic (item 8,
 * pattern 2). One class implements every engine seam a test can consume —
 * [LocalModelEngine], [ToolCallEngine] and the conversation layer's
 * [ConversationBackend] — so a test scripts behavior once and hands the
 * same fake to whichever consumer it drives, instead of each test class
 * hand-rolling its own partial stub (the pattern this replaces: the
 * per-class Backend/RecordingEngine copies in the conversation and eval
 * test packages).
 *
 * Behavior is scripted per generation call. Scripts are consumed from a
 * FIFO queue in call order, across ALL generate variants (text, image,
 * audio, sealed audio and tool-results submissions share one queue,
 * matching the single native conversation they would hit in production);
 * when the queue is empty, [fallbackScript] runs. A script is an ordered
 * list of steps:
 *
 *  - `emit(token)` delivers one token chunk through the call's onToken
 *    callback and appends it to the returned result text;
 *  - `holdAt(gate)` / `hangOn(gate)` parks the generation mid-stream
 *    until the test releases the gate (or the caller is cancelled);
 *  - `fail(error)` throws at exactly that point, after any tokens already
 *    emitted, so a test can script a failure mid-stream, not just at
 *    entry.
 *
 * A [Gate] is the deterministic timing harness: [Gate.entered] completes
 * when a generation reaches the hold, [Gate.release] lets it continue.
 * Holding is a coroutine suspension, so a held generation never blocks
 * the test thread, and cancelling the caller interrupts the hold and is
 * recorded on the call (see [RecordedCall.outcome]).
 *
 * Everything the fake does is recorded for assertions: every call (kind,
 * prompt, inputs, tokens emitted, outcome), the global emitted-token
 * order across concurrent calls, tool-policy requests, and the
 * initialize / reset / close lifecycle. Recording is thread-safe; the
 * snapshot getters return copies.
 *
 * Test sources only: this class never ships in the app.
 */
class FakeModelEngine(
    override val modelId: String = "fake-model"
) : LocalModelEngine, ToolCallEngine, ConversationBackend {

    /** How one generation call ended. */
    enum class Outcome { COMPLETED, FAILED, CANCELLED }

    /** One recorded generation call, in the order the fake received it. */
    class RecordedCall(
        val kind: String,
        val prompt: String,
        val inputBytes: ByteArray? = null,
        val sealedAudio: Content.SealedAudioEmbeddings? = null,
        val toolResults: List<Pair<ToolCall, String>> = emptyList(),
        val benchmarkPurpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
    ) {
        private val emittedBacking = mutableListOf<String>()
        /** Tokens this call emitted, in order. Snapshot copy. */
        val emitted: List<String> get() = synchronized(emittedBacking) { emittedBacking.toList() }
        @Volatile var outcome: Outcome? = null
            internal set
        @Volatile var failure: Throwable? = null
            internal set

        internal fun recordEmitted(token: String) {
            synchronized(emittedBacking) { emittedBacking += token }
        }
    }

    /**
     * A one-shot hold point for a scripted generation. [entered] completes
     * when a generation reaches the hold; [release] lets it continue.
     * Create one per hold via [newGate] (or directly) so concurrent holds
     * never share state.
     */
    class Gate {
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        /** True once a generation is parked at this gate. */
        val isEntered: Boolean get() = entered.isCompleted
        /** True once [release] has been called. */
        val isReleased: Boolean get() = released.isCompleted

        fun release() {
            released.complete(Unit)
        }

        internal suspend fun hold() {
            entered.complete(Unit)
            released.await()
        }
    }

    /** One scripted step of a generation. See the class doc for semantics. */
    sealed interface Step {
        class Emit(val token: String) : Step
        class Hold(val gate: Gate) : Step
        class Fail(val error: Throwable) : Step
    }

    /**
     * The script for one generation call: ordered [Step]s plus the shape
     * of the returned [GenerationResult]. The result text defaults to the
     * concatenation of emitted tokens; [resultText] overrides it (needed
     * for tool-call-only results, whose text is empty while tokens may
     * still have streamed).
     */
    class GenerationScript {
        internal val steps = mutableListOf<Step>()
        var toolCalls: List<ToolCall> = emptyList()
        var resultText: String? = null
        var timeToFirstTokenMs: Long = 1L
        var totalGenerationTimeMs: Long = -1L

        fun emit(token: String): GenerationScript = apply { steps += Step.Emit(token) }
        fun holdAt(gate: Gate): GenerationScript = apply { steps += Step.Hold(gate) }
        /** A hold the script never intends to pass on its own: the
         * generation hangs until the test releases [gate] or cancels. */
        fun hangOn(gate: Gate): GenerationScript = apply { steps += Step.Hold(gate) }
        fun fail(error: Throwable): GenerationScript = apply { steps += Step.Fail(error) }
        fun toolCalls(vararg calls: ToolCall): GenerationScript = apply { toolCalls = calls.toList() }
        fun resultText(text: String): GenerationScript = apply { resultText = text }
    }

    private val lock = Any()
    private val scriptQueue = ArrayDeque<GenerationScript>()
    private val recordedCalls = mutableListOf<RecordedCall>()
    private val allEmitted = mutableListOf<String>()
    private val toolPolicyBacking = mutableListOf<Boolean>()

    /** Script run when the queue is empty. Defaults to an empty reply. */
    @Volatile var fallbackScript: GenerationScript = GenerationScript()

    /** When set, [initialize] throws this instead of succeeding. */
    @Volatile var initializeFailure: Throwable? = null

    /** When set, [setToolsEnabled] throws this and changes nothing. */
    @Volatile var setToolsEnabledFailure: Throwable? = null

    @Volatile var closed: Boolean = false
        private set

    private val initializeCount = AtomicInteger(0)
    private val resetCount = AtomicInteger(0)
    private val closeCount = AtomicInteger(0)

    val initializeCalls: Int get() = initializeCount.get()
    val resetCalls: Int get() = resetCount.get()
    val closeCalls: Int get() = closeCount.get()

    /** Every generation call received, in receive order. Snapshot copy. */
    val calls: List<RecordedCall> get() = synchronized(lock) { recordedCalls.toList() }

    /** Prompts of every generation call, in receive order. */
    val prompts: List<String> get() = calls.map { it.prompt }

    /** Every token emitted by any call, in emission order across calls. */
    val emittedTokens: List<String> get() = synchronized(lock) { allEmitted.toList() }

    /** Every tool-policy value requested of [setToolsEnabled], in order. */
    val toolPolicyRequests: List<Boolean> get() = synchronized(lock) { toolPolicyBacking.toList() }

    fun newGate(): Gate = Gate()

    /** Queue [script] for the next generation call. Returns this fake. */
    fun enqueue(script: GenerationScript): FakeModelEngine = apply {
        synchronized(lock) { scriptQueue.addLast(script) }
    }

    /** Queue a simple reply: each argument is one emitted token chunk. */
    fun enqueueReply(vararg tokens: String): FakeModelEngine =
        enqueue(GenerationScript().apply { tokens.forEach { emit(it) } })

    /** Queue a generation that emits [afterTokens] and then throws [error]. */
    fun enqueueFailure(error: Throwable, vararg afterTokens: String): FakeModelEngine =
        enqueue(GenerationScript().apply { afterTokens.forEach { emit(it) }; fail(error) })

    // --- ToolCallEngine lifecycle -------------------------------------------

    override suspend fun initialize() {
        initializeCount.incrementAndGet()
        initializeFailure?.let { throw it }
    }

    override suspend fun resetConversation() {
        resetCount.incrementAndGet()
    }

    override fun close() {
        closeCount.incrementAndGet()
        closed = true
    }

    // --- ConversationBackend surface ----------------------------------------

    override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
    override var onInferenceProgress: (InferenceProgress) -> Unit = {}
    override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
    override var benchmarkPurpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN

    @Volatile private var toolsEnabledState: Boolean = false

    override val toolsEnabled: Boolean get() = toolsEnabledState

    override fun inputContextDescription(): String = "fake:$modelId"

    /** Real flip semantics, mirroring LiteRtLmEngine: true only on a change. */
    override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
        synchronized(lock) { toolPolicyBacking += enabled }
        setToolsEnabledFailure?.let { throw it }
        val changed = toolsEnabledState != enabled
        toolsEnabledState = enabled
        return changed
    }

    // --- Generation ----------------------------------------------------------

    override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult =
        runScript(RecordedCall("text", prompt, benchmarkPurpose = benchmarkPurpose), onToken)

    override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
        runScript(RecordedCall("image", prompt, inputBytes = imageBytes, benchmarkPurpose = benchmarkPurpose), onToken)

    override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult =
        runScript(RecordedCall("audio", prompt, inputBytes = audioBytes, benchmarkPurpose = benchmarkPurpose), onToken)

    override suspend fun generateSealedAudio(prompt: String, audio: Content.SealedAudioEmbeddings,
                                             onToken: (String) -> Unit): GenerationResult =
        runScript(RecordedCall("sealed-audio", prompt, sealedAudio = audio, benchmarkPurpose = benchmarkPurpose), onToken)

    override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>,
                                         onToken: (String) -> Unit): GenerationResult =
        runScript(RecordedCall("tool-results", prompt = "", toolResults = results,
            benchmarkPurpose = benchmarkPurpose), onToken)

    private fun nextScript(): GenerationScript =
        synchronized(lock) { if (scriptQueue.isEmpty()) fallbackScript else scriptQueue.removeFirst() }

    private suspend fun runScript(call: RecordedCall, onToken: (String) -> Unit): GenerationResult {
        check(!closed) { "FakeModelEngine is closed" }
        synchronized(lock) { recordedCalls += call }
        val script = nextScript()
        val emittedHere = StringBuilder()
        try {
            for (step in script.steps) {
                when (step) {
                    is Step.Emit -> {
                        call.recordEmitted(step.token)
                        synchronized(lock) { allEmitted += step.token }
                        emittedHere.append(step.token)
                        onToken(step.token)
                    }
                    is Step.Hold -> step.gate.hold()
                    is Step.Fail -> throw step.error
                }
            }
        } catch (error: CancellationException) {
            call.outcome = Outcome.CANCELLED
            call.failure = error
            throw error
        } catch (error: Throwable) {
            call.outcome = Outcome.FAILED
            call.failure = error
            throw error
        }
        call.outcome = Outcome.COMPLETED
        return GenerationResult(
            text = script.resultText ?: emittedHere.toString(),
            timeToFirstTokenMs = script.timeToFirstTokenMs,
            decodeTokensPerSecond = null,
            outputTokens = null,
            totalGenerationTimeMs = script.totalGenerationTimeMs,
            streamEvents = call.emitted.size,
            toolCalls = script.toolCalls
        )
    }
}

/** Builds a [FakeModelEngine.GenerationScript] from a step block. */
fun fakeGenerationScript(block: FakeModelEngine.GenerationScript.() -> Unit): FakeModelEngine.GenerationScript =
    FakeModelEngine.GenerationScript().apply(block)
