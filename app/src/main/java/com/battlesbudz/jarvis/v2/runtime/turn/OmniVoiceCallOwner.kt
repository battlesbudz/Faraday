package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.Lfm3ToolProtocol
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.OmniAudioEncoder
import com.battlesbudz.jarvis.v2.ai.OmniBundleComponents
import com.battlesbudz.jarvis.v2.ai.OmniChatPrompt
import com.battlesbudz.jarvis.v2.ai.OmniRuntime
import com.battlesbudz.jarvis.v2.ai.OmniSpeechGenerator
import com.battlesbudz.jarvis.v2.ai.OmniSpeechOutputFactory
import com.battlesbudz.jarvis.v2.ai.OmniSpeechRng
import com.battlesbudz.jarvis.v2.ai.OmniSpeechStack
import com.battlesbudz.jarvis.v2.ai.OmniSpeechTokens
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.voice.SpeechAudioTrackFactory
import com.battlesbudz.jarvis.v2.voice.VoiceCallResources
import com.battlesbudz.jarvis.v2.voice.VoiceCallService
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [OmniVoiceCallOwner] (LFM3-Omni U5 integration, seam 1).
 *
 * Composes the units that landed ahead of U5 against the real integrated
 * code — the U5 snapshot predates U2/U3/U4, so no production owner existed
 * in any unit's diff:
 * - U2: [OmniRuntime.openCallComponents] opens the bundle's runner and
 *   tokenizer on the one dedicated Omni worker; the engine adapter below
 *   drives it through [OmniCallEngine].
 * - U3: one [OmniTurnCapture] per turn over the call's borrowed microphone
 *   (the U3 capture is per-turn by construction; the wrapper bridges it to
 *   the call-scoped [OmniTurnCapture] interface U5's session expects).
 *   U3 supplies no transcript text, so the session's stated fallback
 *   stands: the turn records "[spoken audio; transcript unavailable]" and
 *   emits [OmniVoiceCallSession.Event.MissingInputTranscript].
 * - U4: the speech stack ([OmniSpeechOutputFactory]) drives the
 *   interleaved generator; its [com.battlesbudz.jarvis.v2.ai.OmniPcmSink]
 *   feeds a 24 kHz [AudioTrack] through [OmniAudioTrackOutput].
 * - U6: [OmniDuplexCallCapture] replaces the per-turn U3 wrapper with the
 *   duplex pump for the whole call: one communication-route microphone
 *   borrow, VAD-only barge-in that cancels generation and flushes the
 *   AudioTrack, and completed turns from the pump's `TurnReady` events.
 *   Rendered 24 kHz PCM is registered with the echo guard at the
 *   playback-head position, never as future queued chunks.
 * - U7: [Lfm3ToolProtocol] is wired into the call generation path (seam 5).
 *   The host loop watches the decoded text stream, halts generation on a
 *   complete `<tool_call>`, never plays audio belonging to a tool-call block
 *   (the speech sink drops chunks offered after the block begins), dispatches
 *   through [dispatchToolCalls] — the existing direct-audio action path
 *   installed by runtime composition — appends each result as a `tool` role
 *   message, and continues generation in the same conversation state. No
 *   `<tools>` block is declared in the voice system prompt yet and Omni
 *   `supportsTools` stays false until voice tool fixtures pass.
 *
 * The call stack is created lazily on the first Omni call start and kept
 * resident across calls of the same model (U5's `endCall` contract: clear
 * the conversation, retain residency). Model selection owns closing it:
 * [closeRuntime] cancels any loop and closes the graphs. Chat-on-Omni
 * (seam 7) must unify residency with this stack instead of opening a
 * second [com.battlesbudz.jarvis.v2.ai.OmniEngine] beside it.
 */
internal class OmniVoiceCallOwnerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val modelStore: ModelStore,
    private val voiceSessionController: VoiceSessionController,
    private val voiceResources: VoiceCallResources,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val report: (String) -> Unit,
    /**
     * U7: voice-tool dispatch installed by runtime composition. Given the
     * tool calls parsed from one generated round, dispatches them through
     * the existing direct-audio action path and returns one executor result
     * message per dispatched call, in order; empty when nothing was valid
     * or nothing executed. Null leaves tool calls unexecuted: the turn ends
     * as a normal answer with the tool-call markup stripped from its text.
     */
    private val dispatchToolCalls: (suspend (List<ToolCall>) -> List<String>)? = null,
) : OmniVoiceCallOwner {
    private val lock = Any()
    @Volatile private var activeCallId: String? = null
    private var loopJob: Job? = null
    private val stackMutex = Mutex()
    /** Mutated only under [stackMutex]; volatile so [isRuntimeResident] can read it lock-free. */
    @Volatile private var stack: OmniCallStack? = null

    override fun isRuntimeResident(): Boolean = stack != null

    override fun start() {
        val callId = voiceSessionController.currentCallId()
        if (callId == null) {
            // Defensive: the route establishes the call record before
            // start() (OmniVoiceCallStart), so this means a caller bypassed
            // it. Fail visibly — the build-1942 hang wrote only the
            // diagnostics line below and left the UI at Preparing forever.
            diagnosticRecorder.recordImportant("Omni Voice Call cannot start: no active call.")
            diagnosticRecorder.recordOmniPrep("startFailed=no-active-call")
            failCall("Voice Call turn failed: Omni Voice Call could not start. Please try again.")
            return
        }
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            activeCallId = callId
            loopJob = scope.launch { runLoop(callId) }
        }
    }

    override fun stop(callId: String?) {
        val job = synchronized(lock) {
            if (callId != null && activeCallId != null && activeCallId != callId) return
            activeCallId = null
            loopJob
        }
        // Schedule teardown on the worker rather than blocking the caller:
        // the loop's finally closes the call session, then the mic is
        // released; the join happens here, off the caller's thread.
        scope.launch { job?.cancelAndJoin() }
    }

    override fun closeRuntime() {
        val job = synchronized(lock) {
            activeCallId = null
            loopJob?.also { loopJob = null }
        }
        scope.launch {
            try {
                job?.cancelAndJoin()
            } finally {
                stackMutex.withLock {
                    stack?.let { old -> runCatching { old.close() } }
                    stack = null
                }
                runCatching { voiceResources.closeMicrophone() }
            }
        }
    }

    private suspend fun runLoop(callId: String) {
        val myJob = currentCoroutineContext()[Job]
        try {
            val callStack = try {
                ensureStack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val detail = failure.message ?: failure.javaClass.simpleName
                val message = "Omni Voice Call is unavailable: $detail"
                diagnosticRecorder.recordImportant(message)
                diagnosticRecorder.recordOmniPrep("stackFailed=$detail")
                failCall("Voice Call turn failed: $message")
                return
            }
            runCatching { VoiceCallService.updateStatus(LISTENING_STATUS) }
            val output = OmniAudioTrackOutput(
                log = { diagnosticRecorder.record("Voice output: $it") },
            )
            val capture = OmniDuplexCallCapture(
                scope = scope,
                assets = context.assets,
                borrowMicrophone = { label -> voiceResources.borrowMicrophone(label, communication = true) },
                output = output,
                log = { diagnosticRecorder.record("Voice input: $it") },
                // U8: accepted and rejected barge-in gate decisions go to the
                // Omni diagnostics evidence, not the generic call record.
                onBargeInEvent = { event, confirmedSpeechMs, stopLatencyMs ->
                    diagnosticRecorder.recordOmniBargeIn(event, confirmedSpeechMs, stopLatencyMs)
                },
            )
            val isActive = { voiceSessionController.currentCallId() == callId }
            val session = OmniVoiceCallSession(
                engine = callStack.engine,
                capture = capture,
                output = output,
                diagnostics = ::onSessionEvent,
                isCallActive = isActive,
                duplexHooks = capture,
            )
            try {
                OmniVoiceCallLoop(session, isActive, ::onAnswer).run()
            } finally {
                runCatching { voiceResources.closeMicrophone() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val detail = failure.message ?: failure.javaClass.simpleName
            diagnosticRecorder.recordImportant("Omni Voice Call ended with error: $detail")
            diagnosticRecorder.recordOmniPrep("loopFailed=$detail")
            failCall("Voice Call turn failed: Omni Voice Call ended with an error: $detail")
        } finally {
            synchronized(lock) {
                if (activeCallId == callId) activeCallId = null
                if (loopJob === myJob) loopJob = null
            }
        }
    }

    private fun onAnswer(answer: OmniCallAnswer) {
        if (answer.text.isNotBlank()) {
            runCatching { voiceSessionController.appendTranscript("Jarvis", answer.text) }
        }
        runCatching { VoiceCallService.updateStatus(LISTENING_STATUS) }
    }

    /**
     * Surface an Omni call failure the way the Gemma route surfaces a turn
     * failure (VoiceTurnRunner): a dismissible failure card scoped to the
     * call's conversation plus a terminal status — never only a diagnostics
     * line, which is what left build 1942 sitting at Preparing forever.
     * Without a call record there is no conversation to scope the card to,
     * so the status channel alone carries it.
     */
    private fun failCall(message: String) {
        val conversationId = voiceSessionController.currentConversationId()
        if (conversationId != null) VoiceSessionUi.reportFailure(conversationId, message)
        else report(message)
    }

    private fun onSessionEvent(event: OmniVoiceCallSession.Event) {
        when (event) {
            is OmniVoiceCallSession.Event.Turn -> {
                val latency = event.firstSoundAtMs?.let { it - event.endpointAtMs }
                // U8: endpoint-to-first-nonempty-playback latency on the
                // monotonic clock; both instants are elapsedRealtime.
                if (latency != null) diagnosticRecorder.recordOmniFirstSoundLatency(latency)
                diagnosticRecorder.recordImportant(
                    "Omni call turn: endpoint→first-sound ${latency?.let { "$it ms" } ?: "unknown"}; " +
                        "graphs=${event.graphTimingsMs.entries.joinToString { "${it.key}=${it.value}ms" }}"
                )
            }
            is OmniVoiceCallSession.Event.ContextRolloverStarted ->
                // U8: rollover begins; prefill has not run, so it reports in_progress.
                diagnosticRecorder.recordOmniRollover(
                    event.position, event.transcriptTokens, droppedTurnPairs = 0, prefillMs = null
                )
            is OmniVoiceCallSession.Event.ContextRollover -> {
                // U8: rollover completed, with the elapsed re-prefill time.
                diagnosticRecorder.recordOmniRollover(
                    event.oldPositions, event.prefilledPositions, event.droppedPairs, event.prefillMs
                )
                diagnosticRecorder.recordImportant(
                    "Omni call rollover: ${event.oldPositions} → ${event.prefilledPositions} positions, " +
                        "${event.droppedPairs} pair(s) dropped"
                )
            }
            is OmniVoiceCallSession.Event.MissingInputTranscript ->
                diagnosticRecorder.recordImportant(
                    "Omni call turn: no transcript for captured speech (endpoint ${event.endpointAtMs}); " +
                        "text-only rollover cannot retain the user's spoken words"
                )
        }
    }

    private suspend fun ensureStack(): OmniCallStack = stackMutex.withLock {
        val spec = modelStore.selectedModel()
        if (!OmniVoiceCallRoute.isOmni(spec.id)) {
            stack?.let { old -> runCatching { old.close() } }
            stack = null
            error("Selected model ${spec.id} is not LFM3-Omni-2B.")
        }
        stack?.let { return it }
        check(modelStore.isUsable(spec)) { "LFM3-Omni-2B is not installed or failed verification." }
        // Build-1942 fix: breadcrumb before the long, silent first-run build
        // (bundle hash/extract plus native graph creation) so a stuck or
        // failed preparation leaves Omni evidence and a live status instead
        // of an empty diagnostics section at Preparing.
        diagnosticRecorder.recordOmniPrep("stackBuildStarted model=${spec.id}")
        report("Preparing Omni Voice Call — loading the speech stack…")
        val bundle = modelStore.fileFor(spec)
        val components = OmniRuntime.openCallComponents(context, bundle)
        try {
            val audioSection = OmniRuntime.extractSection(context, bundle, "audio")
            val depthFile = companionFile(spec, DEPTH_FILE)
            val frameFile = companionFile(spec, FRAME_FILE)
            val codecFile = companionFile(spec, CODEC_FILE)
            // The codec graph's state input names come from its own 'decode'
            // signature in the file — no unit supplies a default list and
            // none is invented here.
            val codecStateNames = TfliteSignatureInputs.inputsFor(codecFile, "decode")
                .filter { it != "codes" && it != "start" }
            require(codecStateNames.isNotEmpty()) { "Codec decoder has no state inputs in its 'decode' signature." }
            val sink = OmniPcmQueueSink()
            val built = withContext(components.dispatcher) {
                val encoder = OmniAudioEncoder(audioSection, components.environment)
                val speechStack = OmniSpeechOutputFactory.createStack(
                    runner = components.runner,
                    depthFile = depthFile,
                    frameFile = frameFile,
                    codecFile = codecFile,
                    depthOptions = depthOptions(),
                    cpuOptions = OmniRuntime.cpuOptions(),
                    environment = components.environment,
                    codecStateNames = codecStateNames,
                    codecWindow = codecOverlapAddWindow(),
                    sink = sink,
                )
                encoder to speechStack
            }
            val (encoder, speechStack) = built
            val engine = OmniCallEngineImpl(components, encoder, speechStack, sink, dispatchToolCalls)
            // U8: record the concrete driver/accelerator actually selected for this call.
            diagnosticRecorder.recordOmniRuntimePath(
                "CompiledModel depth=${depthAcceleratorLabel()} lm=CPU audio-encoder=CPU codec=CPU"
            )
            diagnosticRecorder.recordOmniPrep("stackReady model=${spec.id}")
            OmniCallStack(components, engine, encoder, speechStack).also { stack = it }
        } catch (failure: Throwable) {
            runCatching { components.close() }
            throw failure
        }
    }

    private fun companionFile(spec: LocalModelSpec, fileName: String): File {
        val companion = spec.companionFiles.firstOrNull { it.fileName == fileName }
            ?: error("LFM3-Omni-2B catalog has no companion file $fileName.")
        return modelStore.companionFileFor(spec, companion).also {
            check(it.isFile) { "LFM3-Omni-2B companion file is missing: $fileName" }
        }
    }

    /** DESIGN section 2: depth runs on GPU fp16 on Qualcomm, CPU elsewhere. */
    private fun depthOptions(): CompiledModel.Options =
        if (depthAcceleratorLabel() == "GPU-fp16") CompiledModel.Options(Accelerator.GPU)
        else OmniRuntime.cpuOptions()

    /** U8: the depth decoder's accelerator label; the single source of truth for [depthOptions]. */
    private fun depthAcceleratorLabel(): String {
        val hardware = Build.HARDWARE ?: ""
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else ""
        val qualcomm = hardware.contains("qcom", ignoreCase = true) || soc.contains("SM8")
        return if (qualcomm) "GPU-fp16" else "CPU"
    }

    companion object {
        /**
         * The exact listening status the shared call UI maps to its listening
         * phase — the same string the Gemma route emits. A bare "Listening"
         * matched no mapping and left a working Omni call displaying
         * Preparing (build 1942).
         */
        const val LISTENING_STATUS = "Voice Call is listening — speak now."
        const val DEPTH_FILE = "LFM3-Omni-2B_depth_frame2_f32.tflite"
        const val FRAME_FILE = "LFM3-Omni-2B_frame_embed_f32.tflite"
        const val CODEC_FILE = "LFM3-Omni-2B_codec_decoder_f12_f32.tflite"
    }
}

/** The retained Omni call graphs: bundle runner/tokenizer, audio encoder, speech stack. */
internal class OmniCallStack(
    private val components: OmniBundleComponents,
    val engine: OmniCallEngineImpl,
    private val encoder: OmniAudioEncoder,
    private val speechStack: OmniSpeechStack,
) {
    suspend fun close() {
        try {
            withContext(components.dispatcher) {
                runCatching { speechStack.close() }
                runCatching { encoder.close() }
            }
        } finally {
            runCatching { components.close() }
        }
    }
}

/** The speech stack's sink: enqueues generated PCM for the active turn's drain. Never blocks. */
internal class OmniPcmQueueSink : com.battlesbudz.jarvis.v2.ai.OmniPcmSink {
    @Volatile var activeQueue: Channel<FloatArray>? = null
    /**
     * U7: while the current generation round's decoded text holds a
     * tool-call block, chunks offered here are dropped at the source and
     * never queued — audio belonging to a tool-call block is never played.
     * Set and cleared on the Omni worker (or before generation starts), so
     * no chunk can straddle the decision: the codec emits one chunk per
     * 12-frame audio block, and the watch runs during the text slots that
     * precede it. Dropped offers report success so the codec keeps streaming
     * the answer's state forward.
     */
    @Volatile var suppressOffers: Boolean = false
    override fun offer(pcm24k: FloatArray): Boolean {
        if (suppressOffers) return true
        return activeQueue?.trySend(pcm24k)?.isSuccess ?: false
    }
}

/**
 * [OmniCallEngine] over the integrated U2 runtime. All LiteRT calls run on
 * the bundle's dedicated worker; position accounting is mirrored in a
 * volatile field so the session's between-turn reads see the worker's last
 * completed value, while generation additionally stops on the live runner
 * position so a turn can never step past the 4,096 cap.
 *
 * Audio turns follow the sample's framing (see `OmniPrompt.asrText` and the
 * photo-voice-chat `ChatPrompt.ask/next`): the first turn carries the system
 * prompt, later turns continue the state with `<|im_end|>` closing the
 * previous assistant turn unless it already ended on `<|im_end|>`. Audio
 * embedding rows replace the single `<|audio|>` placeholder in the encoded
 * prompt; the interleaved generator starts from the final prefill's hidden
 * state.
 */
internal class OmniCallEngineImpl(
    private val components: OmniBundleComponents,
    private val audioEncoder: OmniAudioEncoder,
    private val speechStack: OmniSpeechStack,
    private val sink: OmniPcmQueueSink,
    private val toolDispatcher: (suspend (List<ToolCall>) -> List<String>)? = null,
) : OmniCallEngine {
    @Volatile private var observedPositions: Int = 0
    @Volatile private var lastTextToken: Int = -1

    override val contextPositions: Int get() = observedPositions

    override suspend fun estimateTextPositions(transcript: List<OmniCallMessage>): Int =
        withContext(components.dispatcher) {
            components.tokenizer.encode(renderTranscriptText(transcript)).size
        }

    override suspend fun resetConversation() {
        withContext(components.dispatcher) {
            components.runner.reset()
            lastTextToken = -1
            observedPositions = 0
        }
    }

    /** Clear the call conversation while the graphs stay resident for the next call. */
    override suspend fun endCall() = resetConversation()

    override suspend fun prefillText(transcript: List<OmniCallMessage>) {
        withContext(components.dispatcher) {
            val ids = components.tokenizer.encode(renderTranscriptText(transcript))
            if (ids.isEmpty()) {
                components.runner.reset()
                lastTextToken = -1
                observedPositions = 0
                return@withContext
            }
            val rows = ids.map { components.runner.embedToken(it) }
            // prefill() resets first, so the rebuilt text-only history starts at zero.
            components.runner.prefill(rows)
            // The rendered history ends on <|im_end|>, so the next audio turn
            // continues without closing the assistant turn again.
            lastTextToken = OmniSpeechTokens.IM_END
            observedPositions = components.runner.pos
        }
    }

    override suspend fun answerAudio(
        pcm16kMono: FloatArray,
        output: OmniCallSpeechOutput,
        shouldStop: () -> Boolean,
    ): OmniCallAnswer {
        val encodeStart = SystemClock.elapsedRealtime()
        val audioRows = withContext(components.dispatcher) { audioEncoder.encode(pcm16kMono) }
        val encodeMs = SystemClock.elapsedRealtime() - encodeStart
        require(audioRows.isNotEmpty()) { "No question was heard: the recording has no sound the model can read." }
        val prefillStart = SystemClock.elapsedRealtime()
        val hidden: FloatArray = withContext(components.dispatcher) {
            val ids = components.tokenizer.encode(audioPromptText())
            val audioAt = ids.indexOf(OmniSpeechTokens.AUDIO)
            require(audioAt >= 0) { "Omni audio prompt has no <|audio|> placeholder" }
            require(ids.count { it == OmniSpeechTokens.AUDIO } == 1) {
                "Omni audio prompt must hold exactly one <|audio|> placeholder"
            }
            val expanded = IntArray(ids.size - 1 + audioRows.size)
            var at = 0
            for (id in ids) {
                if (id == OmniSpeechTokens.AUDIO) {
                    repeat(audioRows.size) { expanded[at++] = OmniSpeechTokens.AUDIO }
                } else expanded[at++] = id
            }
            val rows = ArrayList<FloatArray>(expanded.size)
            for ((i, id) in expanded.withIndex()) {
                rows += if (i in audioAt until audioAt + audioRows.size) audioRows[i - audioAt]
                else components.runner.embedToken(id)
            }
            val state = if (components.runner.pos == 0) components.runner.prefill(rows)
            else components.runner.extend(rows, decodeLast = true)
                ?: error("Omni audio prefill produced no hidden state")
            observedPositions = components.runner.pos
            state
        }
        val prefillMs = SystemClock.elapsedRealtime() - prefillStart

        // U7 tool-call watch. The host loop decodes the round's text at the
        // end of each text block and whenever the text phase ends, so a
        // marker is always caught during the text slots that precede the
        // audio block that would speak it:
        // - `<tool_call>` (or a trailing fragment of it, so a marker split
        //   across text blocks cannot leak its first half as speech) sets the
        //   sink's suppression: chunks offered after the block began are
        //   dropped at the source and never played.
        // - a complete `</tool_call>` halts generation at the next step
        //   boundary, so the call block is never spoken and never completed
        //   past its closing tag.
        // A full decode per token is avoided: decoding rebuilds the whole
        // vocabulary map on every call, so the watch decodes only at text
        // block boundaries and phase ends. The slot counter resets at every
        // decode, so it is zero at each text run's start and a decode always
        // lands on or before the run's last token. The tool declarations
        // come from the same catalog the resident profile attaches for Gemma.
        val tools = MobileActionToolDefinitions.all()
        val textIds = ArrayList<Int>()
        var lastToken = -1
        // Decoded text of the current generation round only; the watch and
        // the per-round parse both read this, while textIds keeps the whole
        // turn for the returned answer.
        val roundTokens = ArrayList<Int>()
        // Text slots since the last watch decode; reset at every decode so
        // the count restarts at each text run.
        var tokensSinceWatch = 0
        var toolCallComplete = false
        // Runs on the Omni worker inside the token callback, so the sink flag
        // it sets is in place before the audio block that follows this text
        // block. Suppression is speculative on a trailing marker fragment
        // and is re-evaluated at the next boundary, so a fragment that never
        // becomes a call costs at most one audio block of speech.
        fun watchToolCallBoundary() {
            val roundText = components.tokenizer.decode(roundTokens.toIntArray())
            sink.suppressOffers = "<tool_call>" in roundText || endsWithToolCallPrefix(roundText)
            if ("</tool_call>" in roundText) toolCallComplete = true
            tokensSinceWatch = 0
        }
        var genMsTotal = 0L
        val queue = Channel<FloatArray>(capacity = 4)
        sink.activeQueue = queue
        try {
            coroutineScope {
                val drain = launch {
                    for (chunk in queue) {
                        if (shouldStop()) break
                        output.playChunk(chunk)
                    }
                }
                var hiddenState = hidden
                var toolRounds = 0
                while (true) {
                    toolCallComplete = false
                    roundTokens.clear()
                    tokensSinceWatch = 0
                    sink.suppressOffers = false
                    val roundStart = SystemClock.elapsedRealtime()
                    withContext(components.dispatcher) {
                        val maxSteps = (components.runner.cacheLength - components.runner.pos - 8)
                            .coerceIn(1, MAX_GENERATE_STEPS)
                        speechStack.generator.generate(
                            initialHidden = hiddenState,
                            initialMode = OmniSpeechGenerator.Mode.INTERLEAVED,
                            maxSteps = maxSteps,
                            rng = OmniSpeechRng(42),
                            onTextToken = { token ->
                                lastToken = token
                                // Every text-branch token consumes a text
                                // slot, control tokens included.
                                tokensSinceWatch++
                                if (token !in CONTROL_TOKENS) {
                                    textIds += token
                                    roundTokens += token
                                }
                                // TEXT_END / IM_END close the text phase;
                                // a full text block ends here. Other control
                                // tokens only consume their slot.
                                if (token == OmniSpeechTokens.TEXT_END || token == OmniSpeechTokens.IM_END ||
                                    tokensSinceWatch >= OmniSpeechTokens.TEXT_SLOTS) {
                                    watchToolCallBoundary()
                                }
                            },
                            stop = {
                                shouldStop() || toolCallComplete ||
                                    components.runner.pos >= MAX_CONTEXT_POSITIONS
                            },
                        )
                        observedPositions = components.runner.pos
                    }
                    genMsTotal += SystemClock.elapsedRealtime() - roundStart
                    // Barge-in, hang-up or the context cap ends the turn
                    // without dispatching: the user has taken the turn back.
                    if (shouldStop()) break
                    val roundText = components.tokenizer.decode(roundTokens.toIntArray())
                    val calls = Lfm3ToolProtocol.parseCalls(roundText, tools)
                    if (calls.isEmpty()) break
                    val dispatcher = toolDispatcher ?: break
                    if (toolRounds >= MAX_TOOL_ROUNDS) break
                    // Dispatch suspends (admission on Main, execution off
                    // Main, approval UI when gated) while the drain keeps
                    // playing the already-queued pre-detection speech.
                    // Nothing is executed from the parser or from
                    // OpenApiTool.execute: the dispatcher owns validation,
                    // approval, execution, receipts and audit.
                    val results = dispatcher(calls)
                    if (results.isEmpty()) break
                    // Continue generation in the same conversation state:
                    // close the assistant turn that emitted the call, append
                    // each executor result as a `tool` role message, and
                    // reopen the assistant turn for the spoken answer.
                    hiddenState = withContext(components.dispatcher) {
                        val continuation = buildString {
                            if (lastToken != OmniSpeechTokens.IM_END) append("<|im_end|>\n")
                            for (result in results) {
                                append(Lfm3ToolProtocol.renderToolResultMessage(result)).append('\n')
                            }
                            append("<|im_start|>assistant\n<think></think>")
                        }
                        val ids = components.tokenizer.encode(continuation)
                        val rows = ids.map { components.runner.embedToken(it) }
                        val state = components.runner.extend(rows, decodeLast = true)
                            ?: error("Omni tool-result prefill produced no hidden state")
                        observedPositions = components.runner.pos
                        state
                    }
                    toolRounds++
                }
                queue.close()
                drain.join()
            }
        } finally {
            sink.activeQueue = null
            sink.suppressOffers = false
        }
        val genMs = genMsTotal
        lastTextToken = lastToken
        // Tool-call markup is never answer text: strip complete blocks and
        // any trailing incomplete block, so neither the transcript nor the
        // spoken continuation can carry it.
        val text = stripToolCallMarkup(components.tokenizer.decode(textIds.toIntArray())).trim()
        return OmniCallAnswer(
            text = text,
            graphTimingsMs = mapOf(
                "audio_encode_ms" to encodeMs,
                "text_prefill_ms" to prefillMs,
                "speech_generate_ms" to genMs,
            ),
        )
    }

    private fun endsWithToolCallPrefix(text: String): Boolean {
        val marker = "<tool_call>"
        for (length in 1 until marker.length) {
            if (text.endsWith(marker.substring(0, length))) return true
        }
        return false
    }

    /** Removes complete `<tool_call>...</tool_call>` blocks and any trailing incomplete block. */
    private fun stripToolCallMarkup(text: String): String {
        var result = text
        while (true) {
            val start = result.indexOf("<tool_call>")
            if (start < 0) return result
            val end = result.indexOf("</tool_call>", start)
            result = if (end < 0) result.substring(0, start)
            else result.substring(0, start) + result.substring(end + "</tool_call>".length)
        }
    }

    private fun audioPromptText(): String {
        val system = OmniChatPrompt.SYSTEM
        val userTurn = "<|im_start|>user\n<|vision_start|><|audio|><|vision_end|><|im_end|>\n" +
            "<|im_start|>assistant\n<think></think>"
        return if (components.runner.pos == 0) {
            "<|startoftext|><|im_start|>system\n$system<|im_end|>\n$userTurn"
        } else {
            val close = if (lastTextToken == OmniSpeechTokens.IM_END) "" else "<|im_end|>\n"
            close + userTurn
        }
    }

    private fun renderTranscriptText(transcript: List<OmniCallMessage>): String {
        val sb = StringBuilder()
        sb.append("<|startoftext|><|im_start|>system\n")
            .append(OmniChatPrompt.SYSTEM).append("<|im_end|>\n")
        for (message in transcript) {
            when (message.role) {
                OmniCallRole.USER ->
                    sb.append("<|im_start|>user\n").append(message.text).append("<|im_end|>\n")
                OmniCallRole.ASSISTANT ->
                    sb.append("<|im_start|>assistant\n<think></think>")
                        .append(message.text).append("<|im_end|>\n")
            }
        }
        return sb.toString()
    }

    companion object {
        const val MAX_CONTEXT_POSITIONS = OmniVoiceCallSession.MAX_CONTEXT_POSITIONS
        const val MAX_GENERATE_STEPS = 1200
        /** U7: bounds the dispatch-and-continue cycles in one voice turn, so a model that keeps emitting calls cannot extend a call indefinitely. */
        const val MAX_TOOL_ROUNDS = 3
        val CONTROL_TOKENS = setOf(
            OmniSpeechTokens.IM_END,
            OmniSpeechTokens.TEXT_END,
            OmniSpeechTokens.MIXED_START,
            OmniSpeechTokens.AUDIO_START,
            OmniSpeechTokens.AUDIO_OUT,
        )
    }
}

/**
 * [OmniCallSpeechOutput] on a 24 kHz mono [AudioTrack] built by the
 * existing [SpeechAudioTrackFactory]. `firstSoundAtMonotonicMs` is the
 * [SystemClock.elapsedRealtime] instant the playback head first advances
 * past zero after the first chunk is written — actual audible start, not
 * generation or write-start time — in the same clock domain as the
 * capture endpoint. The track lives for the call; [close] releases it.
 */
internal class OmniAudioTrackOutput(
    private val log: (String) -> Unit = {},
) : OmniCallSpeechOutput {
    private val trackFactory = SpeechAudioTrackFactory(log)
    private val lock = Any()
    private var track: AudioTrack? = null
    @Volatile private var firstSound: Long? = null
    @Volatile private var stopped: Boolean = false
    @Volatile private var firstChunkPending: Boolean = true
    /** One diagnostics line per turn when playback writes start failing; reset every [beginTurn]. */
    @Volatile private var writeFailureLogged: Boolean = false
    /** PCM written to the track but not yet rendered, in write order. Each entry
     * covers [startFrame, startFrame + frames) of the cumulative frames written
     * since the track was created or last flushed — the same domain as
     * [AudioTrack.playbackHeadPosition]. Bytes reach the U6 echo guard only
     * once the head has passed them, so the guard's reference holds audio that
     * was actually audible, never future queued audio. */
    private val pendingPcm = ArrayDeque<PendingPcm>()
    private var writtenFrames = 0L
    private var releasedFrames = 0L

    private data class PendingPcm(val startFrame: Long, val pcm: ByteArray) {
        val frames: Long get() = pcm.size / 2L
    }

    override val firstSoundAtMonotonicMs: Long? get() = firstSound

    override fun beginTurn() {
        synchronized(lock) {
            stopped = false
            firstSound = null
            firstChunkPending = true
            writeFailureLogged = false
            ensureTrackLocked().play()
        }
    }

    override suspend fun playChunk(pcm24kMono: FloatArray) {
        if (pcm24kMono.isEmpty()) return
        val audioTrack = synchronized(lock) { if (stopped) null else track } ?: return
        val shorts = ShortArray(pcm24kMono.size) { i ->
            (pcm24kMono[i].coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        var offset = 0
        while (offset < shorts.size) {
            if (stopped) return
            val written = try {
                audioTrack.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_BLOCKING)
            } catch (error: IllegalStateException) {
                // A dead track drops the chunk rather than killing the call,
                // but never silently: one diagnostics line per turn.
                logWriteFailure("track write failed: ${error.message ?: "released"}")
                return
            }
            if (written < 0) {
                logWriteFailure("track write returned $written")
                return
            }
            if (written == 0) {
                delay(5)
                continue
            }
            offset += written
            recordWritten(shorts, offset - written, written)
            if (firstChunkPending) {
                firstChunkPending = false
                observeFirstSound(audioTrack)
            }
        }
    }

    /**
     * Hands U4's rendered PCM to [onRenderedPcm] in rendered playback order:
     * only the bytes whose frames the playback head has passed. The duplex
     * adapter polls this while the call runs; a flush resets the head and
     * drops the pending queue, since flushed audio is never audible.
     */
    fun releaseRendered(onRenderedPcm: (ByteArray) -> Unit) {
        val ready = synchronized(lock) {
            val audioTrack = track ?: return
            val head = try {
                audioTrack.playbackHeadPosition.toLong()
            } catch (_: IllegalStateException) {
                return
            }
            if (head < releasedFrames) {
                // The head moved backwards (flush): restart accounting in the
                // new head domain and drop audio that never rendered.
                pendingPcm.clear()
                writtenFrames = head
                releasedFrames = head
                return
            }
            val due = mutableListOf<ByteArray>()
            while (pendingPcm.isNotEmpty()) {
                val first = pendingPcm.first()
                if (head <= first.startFrame) break
                val end = first.startFrame + first.frames
                if (head >= end) {
                    due += pendingPcm.removeFirst().pcm
                    releasedFrames = end
                } else {
                    val cutFrames = (head - first.startFrame).toInt()
                    val cutBytes = cutFrames * 2
                    due += first.pcm.copyOfRange(0, cutBytes)
                    pendingPcm.removeFirst()
                    pendingPcm.addFirst(PendingPcm(head, first.pcm.copyOfRange(cutBytes, first.pcm.size)))
                    releasedFrames = head
                    break
                }
            }
            due
        }
        ready.forEach(onRenderedPcm)
    }

    private fun logWriteFailure(detail: String) {
        if (writeFailureLogged) return
        writeFailureLogged = true
        log("playback $detail")
    }

    private fun recordWritten(shorts: ShortArray, from: Int, frames: Int) {
        if (frames <= 0) return
        val bytes = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val value = shorts[from + i].toInt()
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        synchronized(lock) {
            pendingPcm.addLast(PendingPcm(writtenFrames, bytes))
            writtenFrames += frames
        }
    }

    override fun stopAndFlush() {
        synchronized(lock) {
            stopped = true
            // flush() resets the playback head to zero: flushed audio never
            // renders, so drop it and restart frame accounting.
            pendingPcm.clear()
            writtenFrames = 0
            releasedFrames = 0
            val audioTrack = track
            if (audioTrack != null) {
                runCatching { audioTrack.pause() }
                runCatching { audioTrack.flush() }
            }
        }
    }

    override fun close() {
        val audioTrack = synchronized(lock) {
            stopped = true
            pendingPcm.clear()
            writtenFrames = 0
            releasedFrames = 0
            track.also { track = null }
        }
        if (audioTrack != null) trackFactory.releaseAfterWriterStops(audioTrack)
    }

    private fun ensureTrackLocked(): AudioTrack {
        track?.let { return it }
        return trackFactory.create(SAMPLE_RATE_HZ, FIRST_CHUNK_FRAMES).also { track = it }
    }

    private suspend fun observeFirstSound(audioTrack: AudioTrack) {
        val deadline = SystemClock.elapsedRealtime() + FIRST_SOUND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopped) return
            val head = try {
                audioTrack.playbackHeadPosition
            } catch (_: IllegalStateException) {
                return
            }
            if (head != 0) {
                firstSound = SystemClock.elapsedRealtime()
                return
            }
            delay(5)
        }
        // The track never reported audible progress; first sound stays
        // unknown rather than reporting a write time as audible start.
    }

    companion object {
        const val SAMPLE_RATE_HZ = 24_000
        /** One codec chunk: 0.96 s of 24 kHz audio. */
        const val FIRST_CHUNK_FRAMES = 23_040
        const val FIRST_SOUND_TIMEOUT_MS = 2_000L
    }
}

/**
 * Reads a model's signature input names from the `.tflite` file itself.
 * The LiteRT Kotlin [CompiledModel] API exposes no name enumeration — only
 * name-based lookup — so the names the [com.battlesbudz.jarvis.v2.ai.CompiledCodecDecoder]
 * must bind come from the file's `SignatureDef` table, exactly as the Wren
 * reference reads them from the loaded graph signature (`sig.inputs`).
 * Minimal flatbuffer reader: root table → `Model.signature_defs` (field 7)
 * → `SignatureDef.inputs` (field 2) → `TensorMap.name` (field 0).
 */
internal object TfliteSignatureInputs {
    private const val HEAD_BYTES = 256 * 1024

    fun inputsFor(file: File, signatureKey: String): List<String> {
        require(file.isFile) { "Model file is missing: ${file.path}" }
        val head = ByteArray(HEAD_BYTES)
        RandomAccessFile(file, "r").use { input ->
            val read = input.read(head)
            require(read == HEAD_BYTES) { "Model file too small to carry signatures: ${file.name}" }
        }
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val rootPos = u32(buffer, 4).toInt()
        val (defsPos, defsLen) = vectorAt(buffer, rootPos, 7)
        require(defsLen in 0 until 10_000) { "Model file ${file.name} has an implausible signature count" }
        for (i in 0 until defsLen) {
            val defPos = tableAt(buffer, defsPos, i)
            if (stringAt(buffer, defPos, 0) != signatureKey) continue
            val (inputsPos, inputsLen) = vectorAt(buffer, defPos, 2)
            require(inputsLen in 0 until 10_000) { "Model file ${file.name} has an implausible input count" }
            return (0 until inputsLen).map { stringAt(buffer, tableAt(buffer, inputsPos, it), 0) }
        }
        error("Model file ${file.name} has no '$signatureKey' signature")
    }

    private fun u32(buffer: ByteBuffer, pos: Int): Long {
        requireRange(buffer, pos, 4)
        return buffer.getInt(pos).toLong() and 0xffffffffL
    }

    private fun u16(buffer: ByteBuffer, pos: Int): Int {
        requireRange(buffer, pos, 2)
        return buffer.getShort(pos).toInt() and 0xffff
    }

    private fun i32(buffer: ByteBuffer, pos: Int): Int {
        requireRange(buffer, pos, 4)
        return buffer.getInt(pos)
    }

    private fun requireRange(buffer: ByteBuffer, pos: Int, len: Int) {
        require(pos >= 0 && pos + len <= buffer.limit()) { "tflite offset $pos out of range" }
    }

    /** Absolute position of a table field's value, or 0 when the field is absent. */
    private fun tableField(buffer: ByteBuffer, tablePos: Int, fieldId: Int): Int {
        val vtable = tablePos - i32(buffer, tablePos)
        require(vtable >= 0) { "tflite vtable before table at $tablePos" }
        val vtableLen = u16(buffer, vtable)
        val fieldPos = vtable + 4 + fieldId * 2
        if (fieldPos + 2 > vtable + vtableLen) return 0
        return u16(buffer, fieldPos)
    }

    private fun stringAt(buffer: ByteBuffer, tablePos: Int, fieldId: Int): String {
        val off = tableField(buffer, tablePos, fieldId)
        require(off != 0) { "tflite table at $tablePos has no field $fieldId" }
        val strPos = tablePos + off
        val len = u32(buffer, strPos).toInt()
        requireRange(buffer, strPos + 4, len)
        val bytes = ByteArray(len)
        for (i in bytes.indices) bytes[i] = buffer.get(strPos + 4 + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** (element position, length) of a vector field. */
    private fun vectorAt(buffer: ByteBuffer, tablePos: Int, fieldId: Int): Pair<Int, Int> {
        val off = tableField(buffer, tablePos, fieldId)
        require(off != 0) { "tflite table at $tablePos has no field $fieldId" }
        val vecPos = tablePos + off
        val len = u32(buffer, vecPos).toInt()
        requireRange(buffer, vecPos + 4, len * 4)
        return vecPos + 4 to len
    }

    private fun tableAt(buffer: ByteBuffer, vecPos: Int, index: Int): Int {
        val elemPos = vecPos + index * 4
        return elemPos + u32(buffer, elemPos).toInt()
    }
}

/**
 * The codec overlap-add window. The file `speech/codec_window_f32.npy`
 * (Hugging Face revision 7c22c5187003d0cff8e5d1dc6ed4b45da48ee456,
 * SHA-256 e83a39f565c8edd9d516989d4998631bac2d257734ea11166a407959440591b6,
 * shape (1280,) float32) is a periodic Hann window: verified against
 * `0.5 - 0.5*cos(2πn/N)` with max deviation 2.1e-7 (float32 rounding), so
 * it is generated here instead of downloaded — DESIGN section 6 pins it at
 * implementation. Payload SHA-256 of the downloaded file:
 * 2eec6e903417baa1c6befe141e0c0bb52e8480530f3ab0d0940a413de2904188.
 */
internal fun codecOverlapAddWindow(size: Int = 1280): FloatArray =
    FloatArray(size) { n -> (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * n / size)).toFloat() }
