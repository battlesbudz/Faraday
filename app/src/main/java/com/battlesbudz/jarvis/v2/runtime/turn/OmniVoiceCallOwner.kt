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
import com.battlesbudz.jarvis.v2.ai.OmniCodecStatePlan
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *   endpoint PCM16 is retained by U6 and submitted to the app's selected
 *   Whisper stack on a call-owned background job; its result updates the
 *   paired durable and rollover entries without delaying Omni generation.
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
 *   complete `<tool_call>`, preserves every generated PCM chunk through the
 *   lossless queue, dispatches
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
    private val pendingOmniTranscripts = java.util.concurrent.ConcurrentHashMap<String, String>()
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
            // Unblock a codec callback suspended in the bounded PCM handoff
            // before cancelling the drain's parent coroutine.
            stack?.engine?.discardQueuedAudio()
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
            stack?.engine?.discardQueuedAudio()
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
                transcribePcm = { bytes -> transcribeEndpointPcm(callId, bytes) },
                // U8: accepted and rejected barge-in gate decisions go to the
                // Omni diagnostics evidence, not the generic call record.
                onBargeInEvent = { event, confirmedSpeechMs, stopLatencyMs ->
                    if (event == "accepted") callStack.engine.discardQueuedAudio()
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
                runCatching { voiceResources.closeModels() }
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
        runCatching { VoiceCallService.updateStatus(LISTENING_STATUS) }
    }

    /** Existing selected Whisper stack, loaded and driven away from Jarvis-Omni. */
    private suspend fun transcribeEndpointPcm(callId: String, pcm16: ByteArray): String? = withContext(Dispatchers.IO) {
        if (pcm16.isEmpty() || pcm16.size % 2 != 0) return@withContext null
        val asr = com.battlesbudz.jarvis.v2.voice.AsrEngine.selected(context)
        val models = voiceResources.modelsFor("$callId:omni:${asr.id}")
        diagnosticRecorder.recordOmniTurn("asr_submitted bytes=${pcm16.size} engine=${asr.id}")
        val directory = asr.prepare(context) { status ->
            diagnosticRecorder.recordOmniTurn("asr_model status=${status.take(100)}")
        }
        val segmented = com.battlesbudz.jarvis.v2.voice.SegmentedTranscriber(
            create = { asr.create(directory, live = false, log = { line -> diagnosticRecorder.record("Omni ASR: $line") }, modelSession = models) },
            log = { line -> diagnosticRecorder.record("Omni ASR: $line") },
        )
        try {
            var offset = 0
            // Feed bounded one-second windows. SegmentedTranscriber rotates its
            // Whisper owner at the existing 15/22 second contracts.
            while (offset < pcm16.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(pcm16.size, offset + 32_000)
                val alignedEnd = if (end == pcm16.size) end else end - (end % 2)
                segmented.accept(pcm16.copyOfRange(offset, alignedEnd), allowPartial = false)
                offset = alignedEnd
            }
            segmented.finish().trim().takeIf { it.isNotBlank() }
        } finally {
            segmented.close()
        }
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
                val latencyMs = event.firstSoundAtMs?.let { (it - event.endpointDecisionMs).coerceAtLeast(0) }
                // U8: endpoint-to-first-nonempty-playback latency on the
                // monotonic clock; both instants are elapsedRealtime.
                if (latencyMs != null) diagnosticRecorder.recordOmniFirstSoundLatency(latencyMs)
                val answer = event.answer
                val turnLatency = answer?.let {
                    OmniTurnLatency.build(
                        endpointAtMs = event.endpointAtMs,
                        endpointToFirstSoundMs = latencyMs,
                        graphTimingsMs = event.graphTimingsMs,
                        totalMs = event.totalMs,
                        answer = it,
                    )
                }
                if (answer != null && turnLatency != null) {
                    diagnosticRecorder.recordSummary("Omni ${turnLatency.summary()}")
                    val turnId = event.turnId.ifBlank { OmniTurnLatency.turnId(event.endpointAtMs) }
                    val timings = event.graphTimingsMs.entries.joinToString { "${it.key}=${it.value}ms" }
                    diagnosticRecorder.recordTurnEvidence(
                        turnId,
                        "omni",
                        "endpointToFirstSoundMs=${latencyMs ?: "unknown"} totalMs=${event.totalMs} " +
                            "supplyGaps=${event.supplyGaps} graphTimings={$timings} " +
                            "endpointDecisionMs=${event.endpointDecisionMs} audioEncodeMs=${event.graphTimingsMs["audio_encode_ms"] ?: "unknown"} " +
                            "textPrefillMs=${event.graphTimingsMs["text_prefill_ms"] ?: "unknown"} firstChunkArrivalMs=${event.firstChunkArrivalMs ?: "unknown"} " +
                            "gateReleaseMs=${event.gateReleaseMs ?: "unknown"} generationRealtimeFactor=${event.generationRealtimeFactor ?: "unknown"} " +
                            "maxChunkIntervalMs=${event.maxChunkIntervalMs} rebufferCount=${event.rebufferCount} rebufferTotalMs=${event.rebufferTotalMs} droppedChunks=${event.droppedChunks} " +
                            "playCalledMs=${event.playCalledAtMs ?: "unknown"} firstHeadAdvanceMs=${event.firstSoundAtMs ?: "unknown"} firstWriteCompleteMs=${event.firstWriteCompleteAtMs ?: "unknown"} " +
                            "speechFrames=${answer.speechFrames} textTokens=${answer.textTokens}",
                    )
                    if (answer.text.isNotBlank()) {
                        runCatching {
                            val callId = activeCallId
                            val userText = event.transcriptText ?: pendingOmniTranscripts.remove(turnId).orEmpty()
                            if (callId != null) voiceSessionController.appendOmniTurn(
                                callId, turnId, userText, answer.text, turnLatency,
                            )
                        }
                    }
                }
                diagnosticRecorder.recordImportant(
                    "Omni call turn: endpoint→first-sound ${latencyMs?.let { "$it ms" } ?: "unknown"}; " +
                        "graphs=${event.graphTimingsMs.entries.joinToString { "${it.key}=${it.value}ms" }}"
                )
                answer?.let { answer ->
                    val answerText = answer.text.take(120).replace('\n', ' ').replace('"', '\'')
                    diagnosticRecorder.recordOmniTurn(
                        "turnGen text=\"$answerText\" textTokens=${answer.textTokens} " +
                            "frames=${answer.speechFrames} finished=${answer.finished} stopped=${answer.stopped} " +
                            "pcmChunks=${answer.pcmChunks} pcmSamples=${answer.pcmSamples} pcmPeak=${answer.pcmPeak} " +
                            "writtenShorts=${event.writtenShorts} supplyGaps=${event.supplyGaps} totalMs=${event.totalMs} " +
                            "generationRealtimeFactor=${event.generationRealtimeFactor ?: "unknown"} maxChunkIntervalMs=${event.maxChunkIntervalMs} " +
                            "rebufferCount=${event.rebufferCount} rebufferTotalMs=${event.rebufferTotalMs} droppedChunks=${event.droppedChunks} " +
                            "playCalledMs=${event.playCalledAtMs ?: "unknown"} firstHeadAdvanceMs=${event.firstSoundAtMs ?: "unknown"} firstWriteCompleteMs=${event.firstWriteCompleteAtMs ?: "unknown"} " +
                            "audioRows=${answer.audioRows} positions=${answer.positionsAfterPrefill}"
                    )
                }
            }
            is OmniVoiceCallSession.Event.CapturedSpeech ->
                diagnosticRecorder.recordOmniTurn(
                    "turnCapture seconds=${event.seconds} peak=${event.peak}"
                )
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
            is OmniVoiceCallSession.Event.TranscriptUpdated -> {
                val callId = voiceSessionController.currentCallId()
                val currentCall = callId != null && activeCallId == callId
                if (currentCall) pendingOmniTranscripts[event.turnId] = event.text
                val updated = if (currentCall) callId?.let { voiceSessionController.updateUserTranscriptForReply(
                    it, event.turnId, event.text, expectedText = "",
                ) == true } else false
                diagnosticRecorder.recordOmniTurn(
                    "asr_transcript_update turn=${event.turnId} outcome=${when { updated -> "paired"; currentCall -> "pending_before_reply"; else -> "stale_call" }} chars=${event.text.length}"
                )
                if (updated) pendingOmniTranscripts.remove(event.turnId)
            }
            is OmniVoiceCallSession.Event.TranscriptStale ->
                diagnosticRecorder.recordOmniTurn("asr_stale turn=${event.turnId} reason=call_or_turn_fence")
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
        val components = OmniRuntime.openCallComponents(
            context, bundle,
            onStage = { diagnosticRecorder.recordOmniPrep(it) },
        )
        // Stage breadcrumbs: build 1944 died somewhere inside this build
        // with only stackBuildStarted on record. If the process ever dies
        // here again, the last stage on record localizes it.
        diagnosticRecorder.recordOmniPrep("stackStage=componentsOpen")
        try {
            diagnosticRecorder.recordOmniPrep("stackStage=audioSectionStarted")
            val audioSection = OmniRuntime.extractSection(context, bundle, "audio")
            diagnosticRecorder.recordOmniPrep("stackStage=audioSectionReady")
            val depthFile = companionFile(spec, DEPTH_FILE)
            val frameFile = companionFile(spec, FRAME_FILE)
            val codecFile = companionFile(spec, CODEC_FILE)
            // The codec graph's state input names come from its own 'decode'
            // signature in the file — no unit supplies a default list and
            // none is invented here.
            val codecStateNames = TfliteSignatureInputs.inputsFor(codecFile, "decode")
                .filter { it != "codes" && it != "start" }
            require(codecStateNames.isNotEmpty()) { "Codec decoder has no state inputs in its 'decode' signature." }
            // The speech graphs' tensor geometry comes from the verified
            // sections map, never from CompiledModel tensor-type queries
            // (the load-path rule the decoder init established in build
            // 1966): the audio plan sizes the encoder's windowing, and the
            // codec plan must cover the signature-derived state names
            // exactly — a drifted map fails here, in managed code.
            val codecStateSizes = OmniCodecStatePlan.build(
                codecStateNames,
                OmniRuntime.codecStateElements(context),
            )
            val audioPlan = OmniRuntime.audioPlan(context)
            val sink = OmniPcmQueueSink()
            val weightCacheDir = OmniRuntime.weightCacheDir(context)
            val built = withContext(components.dispatcher) {
                diagnosticRecorder.recordOmniPrep("stackStage=audioEncoderCreateStarted")
                val encoder = OmniAudioEncoder(
                    audioSection, components.environment,
                    OmniRuntime.cpuOptions(audioSection, "audio", weightCacheDir),
                    audioPlan,
                )
                diagnosticRecorder.recordOmniPrep("stackStage=audioEncoderReady")
                val speechStack = OmniSpeechOutputFactory.createStack(
                    runner = components.runner,
                    depthFile = depthFile,
                    frameFile = frameFile,
                    codecFile = codecFile,
                    depthOptions = depthOptions(depthFile),
                    frameOptions = OmniRuntime.cpuOptions(frameFile, "frame", weightCacheDir),
                    codecOptions = OmniRuntime.cpuOptions(codecFile, "codec", weightCacheDir),
                    environment = components.environment,
                    codecStateNames = codecStateNames,
                    codecStateSizes = codecStateSizes,
                    codecWindow = codecOverlapAddWindow(),
                    sink = sink,
                    onStage = { stage -> diagnosticRecorder.recordOmniPrep(stage) },
                )
                encoder to speechStack
            }
            val (encoder, speechStack) = built
            diagnosticRecorder.recordOmniPrep("stackStage=speechStackReady")
            val engine = OmniCallEngineImpl(components, encoder, speechStack, sink, dispatchToolCalls)
            // U8: record the concrete driver/accelerator actually selected for this call.
            diagnosticRecorder.recordOmniRuntimePath(
                "CompiledModel depth=${depthAcceleratorLabel()} lm=CPU audio-encoder=CPU codec=CPU"
            )
            diagnosticRecorder.recordOmniPrep("stackReady model=${spec.id}")
            // The full stack built: the weight caches this load wrote are
            // trusted for the next load (see OmniRuntime.noteStackLoadStarted).
            OmniRuntime.noteStackLoadReady(OmniRuntime.weightCacheDir(context))
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
    private fun depthOptions(depthFile: File): CompiledModel.Options =
        if (depthAcceleratorLabel() == "GPU-fp16") CompiledModel.Options(Accelerator.GPU)
        else OmniRuntime.cpuOptions(depthFile, "depth", OmniRuntime.weightCacheDir(context))

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

/** The speech stack's bounded lossless PCM handoff. A full queue applies producer backpressure. */
internal class OmniPcmQueueSink : com.battlesbudz.jarvis.v2.ai.OmniPcmSink {
    @Volatile var activeQueue: Channel<FloatArray>? = null
    /** PCM offered this turn (chunks, samples, peak amplitude), counted at handoff. */
    @Volatile var offeredChunks: Int = 0
        private set
    @Volatile var offeredSamples: Int = 0
        private set
    @Volatile var offeredPeak: Float = 0f
        private set
    private val droppedChunkCount = AtomicInteger(0)
    val droppedChunks: Int get() = droppedChunkCount.get()
    @Volatile var maxChunkIntervalMs: Long = 0L
        private set
    @Volatile var firstChunkArrivalAtMs: Long? = null
        private set
    @Volatile private var previousChunkAtMs: Long? = null

    fun resetOffered() {
        offeredChunks = 0
        offeredSamples = 0
        offeredPeak = 0f
        droppedChunkCount.set(0)
        maxChunkIntervalMs = 0L
        firstChunkArrivalAtMs = null
        previousChunkAtMs = null
    }
    fun recordDroppedChunk() { droppedChunkCount.incrementAndGet() }
    override suspend fun offer(pcm24k: FloatArray): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (firstChunkArrivalAtMs == null) firstChunkArrivalAtMs = now
        previousChunkAtMs?.let { maxChunkIntervalMs = maxOf(maxChunkIntervalMs, now - it) }
        previousChunkAtMs = now
        offeredChunks++
        offeredSamples += pcm24k.size
        for (sample in pcm24k) {
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > offeredPeak) offeredPeak = magnitude
        }
        val queue = activeQueue ?: run { recordDroppedChunk(); return false }
        return try {
            queue.send(pcm24k)
            true
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // The only normal close is stop/barge-in teardown; evidence makes
            // this explicit rather than hiding an unexpected rejected chunk.
            recordDroppedChunk()
            false
        }
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
    override fun discardQueuedAudio() {
        // Closing wakes a producer suspended in the codec callback;
        // only stop/barge-in calls this before the turn queue's normal close.
        sink.activeQueue?.close()
    }

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
            require(ids.last() == OmniSpeechTokens.MIXED_START) {
                "Omni audio prompt must open the assistant turn with <|mixed_start|>"
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
        val positionsAfterPrefill = observedPositions
        val prefillMs = SystemClock.elapsedRealtime() - prefillStart

        // U7 tool-call watch. The host loop decodes the round's text at the
        // end of each text block and whenever the text phase ends, so a
        // complete closing marker is caught at a step boundary. Every PCM
        // chunk remains in the lossless queue; only stop/barge-in teardown
        // discards queued audio.
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
        // it sets is in place before the following audio block.
        fun watchToolCallBoundary() {
            val roundText = components.tokenizer.decode(roundTokens.toIntArray())
            if ("</tool_call>" in roundText) toolCallComplete = true
            tokensSinceWatch = 0
        }
        var genMsTotal = 0L
        val queue = Channel<FloatArray>(capacity = 8)
        sink.activeQueue = queue
        sink.resetOffered()
        var speechFrames = 0
        var generationFinished = false
        var generationStopped = false
        val turnGenerationFinished = AtomicBoolean(false)
        try {
            coroutineScope {
                val drain = launch {
                    var seenGaps = output.supplyGapsThisTurn
                    while (true) {
                        val item = queue.receiveCatching()
                        if (item.isClosed) break
                        val chunk = item.getOrNull() ?: continue
                        // Keep accepting/discarding on teardown so a producer
                        // backpressured in offer() is released immediately.
                        if (shouldStop()) {
                            sink.recordDroppedChunk()
                            continue
                        }
                        output.playChunk(chunk)
                        val gapsNow = output.supplyGapsThisTurn
                        if (gapsNow > seenGaps && !turnGenerationFinished.get() && !shouldStop()) {
                            seenGaps = gapsNow
                            output.pauseForRebuffer()
                            val refill = ArrayList<FloatArray>(OmniPlaybackBufferPolicy.REBUFFER_CHUNKS)
                            repeat(OmniPlaybackBufferPolicy.REBUFFER_CHUNKS) {
                                if (!shouldStop()) queue.receiveCatching().getOrNull()?.let(refill::add)
                            }
                            if (!shouldStop()) output.resumeAfterRebuffer()
                            for (buffered in refill) {
                                if (shouldStop()) {
                                    sink.recordDroppedChunk()
                                    continue
                                }
                                output.playChunk(buffered)
                            }
                        }
                    }
                }
                var hiddenState = hidden
                var toolRounds = 0
                while (true) {
                    toolCallComplete = false
                    roundTokens.clear()
                    tokensSinceWatch = 0
                    val roundStart = SystemClock.elapsedRealtime()
                    withContext(components.dispatcher) {
                        val maxSteps = (components.runner.cacheLength - components.runner.pos - 8)
                            .coerceIn(1, MAX_GENERATE_STEPS)
                        val result = speechStack.generator.generate(
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
                        speechFrames += result.frames.size
                        generationFinished = result.finished
                        generationStopped = result.stopped
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
                            append("<|im_start|>assistant\n<think></think><|mixed_start|>")
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
                turnGenerationFinished.set(true)
                queue.close()
                drain.join()
            }
        } finally {
            sink.activeQueue = null
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
            audioRows = audioRows.size,
            positionsAfterPrefill = positionsAfterPrefill,
            textTokens = textIds.size,
            speechFrames = speechFrames,
            finished = generationFinished,
            stopped = generationStopped,
            pcmChunks = sink.offeredChunks,
            pcmSamples = sink.offeredSamples,
            pcmPeak = sink.offeredPeak,
            maxChunkIntervalMs = sink.maxChunkIntervalMs,
            firstChunkArrivalAtMs = sink.firstChunkArrivalAtMs,
            droppedChunks = sink.droppedChunks,
        )
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
        val system = OmniChatPrompt.VOICE_SYSTEM
        // Model card, voice-chat mode: the answer opens with
        // <think></think><|mixed_start|> — the mixed_start token is part of
        // the prefill (the sample's ChatPrompt.ask appends it the same
        // way), not a token left for the sampler to produce.
        val userTurn = "<|im_start|>user\n<|vision_start|><|audio|><|vision_end|><|im_end|>\n" +
            "<|im_start|>assistant\n<think></think><|mixed_start|>"
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
            .append(OmniChatPrompt.VOICE_SYSTEM).append("<|im_end|>\n")
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
 * past zero after playback starts — actual audible start, not
 * generation or write-start time — in the same clock domain as the
 * capture endpoint. Playback starts after two 23,040-frame codec chunks
 * have arrived or at [finishTurn], whichever comes first. Barge-in via
 * [stopAndFlush] remains immediate and never waits for the pre-roll. The
 * track lives for the call; [close] releases it.
 */
internal class OmniAudioTrackOutput(
    private val log: (String) -> Unit = {},
) : OmniCallSpeechOutput {
    private val trackFactory = SpeechAudioTrackFactory(log)
    private val lock = Any()
    private var track: AudioTrack? = null
    @Volatile private var firstSound: Long? = null
    @Volatile private var stopped: Boolean = false
    private val playbackPolicy = OmniPlaybackBufferPolicy()
    private val headObserverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var firstHeadJob: Job? = null
    @Volatile private var playCalledAt: Long? = null
    @Volatile private var firstWriteCompleteAt: Long? = null
    @Volatile private var firstHeadBaseline = 0L
    @Volatile private var rebufferCount = 0
    @Volatile private var rebufferStartedAt: Long? = null
    @Volatile private var rebufferTotalMs = 0L
    @Volatile private var underrunBaseline: Int = 0
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
    private var turnStartWrittenFrames = 0L

    private data class PendingPcm(val startFrame: Long, val pcm: ByteArray) {
        val frames: Long get() = pcm.size / 2L
    }

    override val firstSoundAtMonotonicMs: Long?
        get() {
            firstSound?.let { return it }
            val audioTrack = synchronized(lock) { track } ?: return null
            val advanced = runCatching { audioTrack.playbackHeadPosition.toLong() > firstHeadBaseline }.getOrDefault(false)
            if (advanced) {
                val now = SystemClock.elapsedRealtime()
                firstSound = now
                log("first_head_advance playCalledMs=${playCalledAt ?: "unknown"} firstHeadAdvanceMs=$now firstWriteCompleteMs=${firstWriteCompleteAt ?: "pending"}")
            }
            return firstSound
        }
    override val playCalledAtMs: Long? get() = playCalledAt
    override val firstWriteCompleteAtMs: Long? get() = firstWriteCompleteAt
    override val rebufferCountThisTurn: Int get() = rebufferCount
    override val rebufferTotalMsThisTurn: Long get() = rebufferTotalMs
    override val writtenShortsThisTurn: Long
        get() = synchronized(lock) { writtenFrames - turnStartWrittenFrames }
    override val supplyGapsThisTurn: Int
        get() {
            val audioTrack = synchronized(lock) { track } ?: return 0
            val current = runCatching { audioTrack.underrunCount }.getOrDefault(0)
            return (current - underrunBaseline).coerceAtLeast(0)
        }

    override fun beginTurn() {
        synchronized(lock) {
            turnStartWrittenFrames = writtenFrames
            firstHeadBaseline = turnStartWrittenFrames
            stopped = false
            firstSound = null
            playCalledAt = null
            firstWriteCompleteAt = null
            firstHeadJob?.cancel()
            rebufferCount = 0
            rebufferTotalMs = 0L
            rebufferStartedAt = null
            writeFailureLogged = false
            val audioTrack = ensureTrackLocked()
            // Every Omni turn re-enters the arrival gate even if the prior
            // track state remained PLAYING after its final write.
            runCatching { audioTrack.pause() }
            underrunBaseline = runCatching { audioTrack.underrunCount }.getOrDefault(0)
            playbackPolicy.reset()
        }
    }

    override suspend fun playChunk(pcm24kMono: FloatArray) {
        if (pcm24kMono.isEmpty()) return
        val audioTrack = synchronized(lock) { if (stopped) null else track } ?: return
        val startNow = playbackPolicy.arrived(pcm24kMono.size)
        if (startNow) synchronized(lock) {
            if (stopped || track !== audioTrack) false
            else {
                playCalledAt = SystemClock.elapsedRealtime()
                audioTrack.play()
                startHeadObservation(audioTrack)
                true
            }
        }
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
        }
        if (startNow || firstWriteCompleteAt == null) firstWriteCompleteAt = SystemClock.elapsedRealtime()
    }

    override suspend fun finishTurn() {
        val shouldStart = synchronized(lock) { !stopped && track != null }
        if (shouldStart && playbackPolicy.finished()) {
            synchronized(lock) {
                track?.takeUnless { stopped }?.let {
                    playCalledAt = SystemClock.elapsedRealtime()
                    it.play()
                    startHeadObservation(it)
                }
            }
        }
        // Polling has run concurrently with writes since play(); joining here
        // only makes the measurement available before the turn record emits.
        firstHeadJob?.join()
    }

    override fun pauseForRebuffer() {
        synchronized(lock) {
            if (stopped || rebufferStartedAt != null) return
            runCatching { track?.pause() }
            playbackPolicy.underrun()
            rebufferStartedAt = SystemClock.elapsedRealtime()
            rebufferCount++
        }
    }

    override fun resumeAfterRebuffer() {
        synchronized(lock) {
            val startedAt = rebufferStartedAt ?: return
            if (stopped) { rebufferStartedAt = null; return }
            runCatching { track?.play() }
            rebufferTotalMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
            rebufferStartedAt = null
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
            playbackPolicy.stop()
            firstHeadJob?.cancel()
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
            playbackPolicy.stop()
            firstHeadJob?.cancel()
            pendingPcm.clear()
            writtenFrames = 0
            releasedFrames = 0
            track.also { track = null }
        }
        headObserverScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
            if (head.toLong() > firstHeadBaseline) {
                val now = SystemClock.elapsedRealtime()
                if (firstSound == null) {
                    firstSound = now
                    log("first_head_advance playCalledMs=${playCalledAt ?: "unknown"} firstHeadAdvanceMs=$now firstWriteCompleteMs=${firstWriteCompleteAt ?: "pending"}")
                }
                return
            }
            delay(5)
        }
        // The track never reported audible progress; first sound stays
        // unknown rather than reporting a write time as audible start.
    }

    private fun startHeadObservation(audioTrack: AudioTrack) {
        firstHeadJob?.cancel()
        firstHeadJob = headObserverScope.launch {
            observeFirstSound(audioTrack)
        }
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
 * → `SignatureDef.signature_key` (field 2, matched) → `SignatureDef.inputs`
 * (field 0) → `TensorMap.name` (field 0). Field ids are schema_v3b's, and
 * every string/vector field is followed through its uoffset, as the format
 * requires.
 *
 * Reads are seek-based against the real file, never a head window: the
 * converter that produced the pinned speech graphs places at least one
 * TensorMap vtable at the very end of the file (the codec graph's `codes`
 * TensorMap vtable sits at byte 152,364,902 of 152,370,912), so any
 * fixed-size prefix buffer reads past structures it must follow. Build
 * 1966's reader also read the root offset from byte 4 — the "TFL3" file
 * identifier, 860,636,756 as an integer — which is the exact
 * "tflite offset 860636756 out of range" the device reported.
 */
internal object TfliteSignatureInputs {
    fun inputsFor(file: File, signatureKey: String): List<String> {
        require(file.isFile) { "Model file is missing: ${file.path}" }
        RandomAccessFile(file, "r").use { input ->
            val reader = Reader(input, file.length())
            val rootPos = reader.u32(0).toInt()
            val (defsPos, defsLen) = reader.vectorAt(rootPos, 7)
            require(defsLen in 0 until 10_000) { "Model file ${file.name} has an implausible signature count" }
            for (i in 0 until defsLen) {
                val defPos = reader.tableAt(defsPos, i)
                if (reader.stringAt(defPos, 2) != signatureKey) continue
                val (inputsPos, inputsLen) = reader.vectorAt(defPos, 0)
                require(inputsLen in 0 until 10_000) { "Model file ${file.name} has an implausible input count" }
                return (0 until inputsLen).map { reader.stringAt(reader.tableAt(inputsPos, it), 0) }
            }
            error("Model file ${file.name} has no '$signatureKey' signature")
        }
    }

    /** Little-endian primitive reads at absolute file positions, bounds-checked against the file length. */
    private class Reader(private val input: RandomAccessFile, private val fileLength: Long) {
        fun u32(pos: Int): Long {
            requireRange(pos, 4)
            input.seek(pos.toLong())
            val bytes = ByteArray(4)
            input.readFully(bytes)
            return (bytes[0].toLong() and 0xff) or
                ((bytes[1].toLong() and 0xff) shl 8) or
                ((bytes[2].toLong() and 0xff) shl 16) or
                ((bytes[3].toLong() and 0xff) shl 24)
        }

        fun u16(pos: Int): Int {
            requireRange(pos, 2)
            input.seek(pos.toLong())
            return input.read() or (input.read() shl 8)
        }

        fun i32(pos: Int): Int = u32(pos).toInt()

        private fun requireRange(pos: Int, len: Int) {
            require(pos >= 0 && pos.toLong() + len <= fileLength) { "tflite offset $pos out of range" }
        }

        /** Absolute position of a table field's value slot, or 0 when the field is absent. */
        private fun tableField(tablePos: Int, fieldId: Int): Int {
            val vtable = tablePos - i32(tablePos)
            require(vtable >= 0) { "tflite vtable before table at $tablePos" }
            val vtableLen = u16(vtable)
            val fieldPos = vtable + 4 + fieldId * 2
            if (fieldPos + 2 > vtable + vtableLen) return 0
            return u16(fieldPos)
        }

        /** Follows a field's uoffset to the position of the data it points at. */
        private fun deref(tablePos: Int, fieldId: Int): Int {
            val off = tableField(tablePos, fieldId)
            require(off != 0) { "tflite table at $tablePos has no field $fieldId" }
            val slotPos = tablePos + off
            return slotPos + u32(slotPos).toInt()
        }

        fun stringAt(tablePos: Int, fieldId: Int): String {
            val strPos = deref(tablePos, fieldId)
            val len = u32(strPos).toInt()
            requireRange(strPos + 4, len)
            val bytes = ByteArray(len)
            input.seek(strPos.toLong() + 4)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /** (element position, length) of a vector field. */
        fun vectorAt(tablePos: Int, fieldId: Int): Pair<Int, Int> {
            val vecPos = deref(tablePos, fieldId)
            val len = u32(vecPos).toInt()
            require(len >= 0 && vecPos.toLong() + 4 + len.toLong() * 4 <= fileLength) {
                "tflite offset $vecPos out of range"
            }
            return vecPos + 4 to len
        }

        fun tableAt(vecPos: Int, index: Int): Int {
            val elemPos = vecPos + index * 4
            return elemPos + u32(elemPos).toInt()
        }
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
