package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Who arbitrates this capture's endpoint. LEGACY is the historical stack: the
 * Smart Turn verdict arbitrated against AdaptiveTurnEnd's transcript-cue targets,
 * which remain an active decider whenever no verdict binds. REFERENCE is the
 * Pipecat pattern used by the conversation captures (primary and follow-up,
 * built by VoiceTurnCaptureFactory): the analyzer's COMPLETE is the only
 * verdict-based endpoint, an undecided turn stays open until speech resumes or
 * the 5s turn-stop timeout backstop fires, and with no analyzer the plain
 * speech-timeout window decides. Reply/barge-in captures use REFERENCE too:
 * the speech retained after an interruption is a user turn, and its stop is
 * judged the same way. LEGACY remains only for captures that never opt in,
 * the non-conversation diagnostic and dictation endpoints.
 */
enum class TurnStopPolicy { LEGACY, REFERENCE }

/** Captures one speech turn, with bounded idle pre-roll and no raw audio on disk. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val createDetector: () -> SpeechDetector,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val createTranscriber: (() -> StreamingTranscriber)? = null,
    private val onPartialTranscript: (String) -> Unit = {},
    private val onMetrics: (AsrCaptureMetrics, String) -> Unit = { _, _ -> },
    private val trailingSilenceMs: Long? = null,
    private val onRecognitionRecovery: (Boolean) -> Unit = {},
    private val allowAudioOnlyTurns: Boolean = false,
    private val guardFollowupSpeech: Boolean = false,
    private val initialConfirmedSpeech: () -> String = { "" },
    private val onSpeechResumed: () -> Unit = {},
    private val turnEnd: TurnEndDetector = AdaptiveTurnEnd(),
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxAudioDurationMs: Int = 25_000,
    private val rejectAtAudioLimit: Boolean = false,
    /** Display-only recognition cannot veto acoustically confirmed native audio. */
    private val captionOnly: Boolean = false,
    private val onAcousticDecision: (ByteArray, SpeechDecision, SpeechDecision, Double) -> Unit = { _, _, _, _ -> },
    private val retainedPcmObserver: RetainedPcmObserver? = null,
    private val shadowObserver: CaptureShadowObserver? = null,
    /** Pure verdict over already-final evidence; rejection reuses this capture's existing reset path. */
    private val rejectFinalCandidate: (String, Long?) -> Boolean = { _, _ -> false },
    private val nowNs: () -> Long = System::nanoTime,
    /** Ordinary native audio only; caller checks playback-tail risk using the actual onset. */
    private val canRetireIdleCaption: (Long?) -> Boolean = { false },
    private val nativePauseObserver: NativePauseObserver? = null,
    private val nativePauseTurnId: String = "",
    private val nativePauseGeneration: Long = 0,
    private val canUseNativePause: (Long?) -> Boolean = { false },
    private val canUseSmartTurn: (Long?) -> Boolean = { false },
    private val turnStopPolicy: TurnStopPolicy = TurnStopPolicy.LEGACY
) {
    // Observer failures cannot escape into the PCM owner; endpoint advice is separately fenced.
    private inline fun shadow(action: (CaptureShadowObserver) -> Unit) {
        shadowObserver?.let { runCatching { action(it) } }
    }
    private val endpointObserver = (shadowObserver as? CaptureEndpointObserver)?.takeIf { it.controlsEndpoint }
    private val nativePause = nativePauseObserver?.let { NativePauseCapture(it, nativePauseTurnId, nativePauseGeneration) }
    /** Only published by stop(), after capture's collector and VAD producer have joined. */
    @Volatile var nativePauseCertificate: NativePauseCertificate? = null
        private set
    private val pcm = RollingAudioBuffer(maxDurationMs = maxAudioDurationMs.toLong())
    private var capturedPcmBytes = 0L
    val audioIsComplete: Boolean get() = capturedPcmBytes <= maxAudioDurationMs.toLong() * 32
    @Volatile var recognitionIssue: String? = null
        private set
    private val preRoll = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 1200)
    private val recoveryAudio = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 25_000)
    private val lifecycle = Mutex()
    private var collectionJob: Job? = null
    private var detector: SpeechDetector? = null
    private var transcriber: StreamingTranscriber? = null
    @Volatile var finalTranscript: String = ""
        private set
    /** Observational only: a skipped caption is never reported as final ASR. */
    @Volatile var finalAsrStatus: String = "pending"
        private set
    @Volatile var captionFinalizationReason: String = "not_attempted"
        private set
    private var lastPartial = ""
    private var partialUpdates = 0
    private var firstSpeechAt: Long? = null
    private var firstPartialAfterSpeechMs: Long? = null
    private var captureReadyMs = 0L
    private val turnCompleted = CompletableDeferred<Boolean>()
    private var stopped = false
    private fun newTranscriber(): StreamingTranscriber? = createTranscriber?.let { SegmentedTranscriber(it, log) }
    private val quietEvidence = QuietSpeechEvidence()
    @Volatile var firstSpeechCaptureAtMs: Long? = null
        private set
    @Volatile var lastSpeechAtMs: Long? = null
        private set
    /** Final accepted endpoint proposal, before recognizer finalization. Never last speech. */
    @Volatile var endpointDecisionAtNs: Long? = null
        private set
    /** Actual retained onset buffer, including the confirming frame, in PCM samples. */
    @Volatile var retainedPreRollSampleCount: Int? = null
        private set
    @Volatile private var endRequested = false
    fun finishNow() { shadow { it.onPriority("explicit_stop") }; nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED); endRequested = true }
    fun yieldMicrophone() { shadow { it.close() }; nativePause?.invalidate(NativePauseInvalidation.CANCELLED); turnCompleted.completeExceptionally(MicrophoneBusyException()) }
    @Volatile var hasSpeech: Boolean = false
        private set

    suspend fun start(initialSilenceTimeoutMs: Long? = VoiceCallPolicy.CALL_INACTIVITY_MS) = lifecycle.withLock {
        check(!stopped && collectionJob == null) { "Audio capture is already started or stopped." }
        check(input.sampleRateHz == 16_000 && input.channelCount == 1) { "VAD requires 16 kHz mono audio." }
        // Start the hardware first; AndroidAudioInput buffers PCM even without a collector.
        // Model construction must not erase speech spoken during microphone preparation.
        val captureRequestedAt = nowMs()
        input.start()
        val activeDetector = createDetector()
        detector = activeDetector
        val loadStartedAt = nowMs()
        transcriber = newTranscriber()
        var modelLoadMs = nowMs() - loadStartedAt
        val startedAt = nowMs()
        val speechQueue = CaptureSpeechQueue(input, activeDetector, nowMs, log, dispatcher = captureDispatcher, onDecision = onAcousticDecision,
            onRawDecision = { nativePause?.observeRaw(it) })
        speechQueue.targetSilenceMs = trailingSilenceMs ?: transcriber?.noTextSilenceMs ?: 3000L
        var audioBytes = 0L
        var asrInputStartSample = 0L
        var lastSpeechSample = 0L
        val completedCues = NativeCaptionEndpointCueTracker()
        val completedCueEnd = AdaptiveTurnEnd()
        val nativeSpeechEvidence = CandidateSpeechEvidence()
        var lastNativeEligibilityReason = ""
        var nativeEligibleFrames = 0
        var nativeUncoveredEndpointFrames = 0
        var nativeProposalCount = 0
        var decodeMs = 0L
        var maxDecodeChunkMs = 0L
        var emptyCandidates = 0
        var deferredPartialChunks = 0
        var finalDecodeMs = 0L
        val recognitionBudget = CaptureRecognitionBudget()
        var maxRecognitionBacklogMs = 0L
        val acousticMetrics = CaptureAcousticAccumulator()
        val recognitionWork = AsrRecognitionWorkLedger()
        var lastSpeechAt = startedAt
        var lastLevelLogAt = startedAt
        var pendingEndpoint = false
        var finalNativeEndpointGuard: AdaptiveTurnEnd.Decision? = null
        var retiredCaptionAtPendingEndpoint = false
        var skippedSmartCaptionAtPendingEndpoint = false
        var quietEvidenceUsed = false
        var unconfirmedOnsetAt: Long? = null
        val followupEvidence = FollowupSpeechEvidence()
        var initialEvidenceAvailable = true
        val pendingAudio = RollingAudioBuffer(maxDurationMs = 1200)
        log("capture_started vad=silero threshold=0.5 speechConfirmationMs=96 " +
            "speechGate=confirmed_acoustic_v4 noiseWindowMs=3000 noiseCalibrationMs=200 weakNoiseRatio=1.8 strongNoiseRatio=1.1 partialPolicy=work_paced_v1 " +
            "endpointing=${if (trailingSilenceMs == null) "adaptive" else "fixed"} " +
            "trailingSilenceMs=$trailingSilenceMs initialSilenceTimeoutMs=$initialSilenceTimeoutMs audioWindowMs=$maxAudioDurationMs maxTurnMs=${if (rejectAtAudioLimit) maxAudioDurationMs else 120000}")
        captureReadyMs = nowMs() - captureRequestedAt
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var acceptedTurn: Boolean? = null
                val wake = Channel<Unit>(Channel.CONFLATED)
                endpointObserver?.setEndpointReadyListener { wake.trySend(Unit) }
                var lastFrame: CaptureSpeechQueue.Frame? = null
                val frames = if (endpointObserver == null) speechQueue.frames() else channelFlow<CaptureSpeechQueue.Frame?> {
                    val wakeJob = launch { wake.receiveAsFlow().collect { send(null) } }
                    try { speechQueue.frames().collect { send(it) } } finally { wakeJob.cancel() }
                }.buffer(Channel.RENDEZVOUS)
                try { frames.transformWhile { emit(it); acceptedTurn == null && !turnCompleted.isCompleted }.collect { event ->
                    if (turnCompleted.isCompleted) return@collect
                    val isModelWake = event == null
                    val frame = event?.also { lastFrame = it } ?: lastFrame ?: return@collect
                    val chunk = frame.pcm
                    val signal = Pcm16Signal.measure(chunk)
                    val decision = frame.decision
                    val now = nowMs()
                    val audioAt = frame.capturedAtMs
                    var awaitingConfirmation = unconfirmedOnsetAt?.let { audioAt - it < 96 } == true
                    if (!isModelWake) {
                        speechQueue.consumed(frame)
                        audioBytes += chunk.size
                        recoveryAudio.append(chunk)
                        acousticMetrics.record(signal, decision, frame.noiseFloorRms)
                        nativeSpeechEvidence.observe(chunk.size, decision)
                        // Give a possible onset one confirmation window before sealing.
                        // It cannot refresh lastSpeechAt or reopen ASR on its own.
                        if (!decision.isSpeech && decision.probability >= 0.5f) {
                            if (unconfirmedOnsetAt == null) unconfirmedOnsetAt = audioAt
                        } else unconfirmedOnsetAt = null
                        awaitingConfirmation = unconfirmedOnsetAt?.let { audioAt - it < 96 } == true
                        var resumedAudio: ByteArray? = null
                        if (pendingEndpoint) pendingAudio.append(chunk)
                        if (pendingEndpoint && (decision.isSpeech || ((nativePause != null || endpointObserver != null) &&
                            (frame.rawDecision.probability >= .15f || frame.rawDecision.rawCoverage == null)))) {
                            if (!skippedSmartCaptionAtPendingEndpoint) (transcriber as? SegmentedTranscriber)?.resumeAfterEndpoint()
                            skippedSmartCaptionAtPendingEndpoint = false
                            pendingEndpoint = false
                            finalNativeEndpointGuard = null
                            retiredCaptionAtPendingEndpoint = false
                            resumedAudio = pendingAudio.snapshot(); pendingAudio.clear()
                            onSpeechResumed()
                            log("turn_endpoint_invalidated reason=resumed_speech")
                        }
                        var retainedAudio: ByteArray? = null
                        var retainedWindowRolled = false
                        synchronized(pcm) {
                            if (hasSpeech) {
                                pcm.append(chunk)
                                if (retainedPcmObserver != null || nativePause != null || shadowObserver != null) retainedAudio = chunk.copyOf()
                                val wasComplete = audioIsComplete
                                capturedPcmBytes += chunk.size
                                if (wasComplete && !audioIsComplete) {
                                    retainedWindowRolled = true
                                    onSpeechResumed()
                                    log("audio_window_rolled full_request_text_required=true speculation_invalidated=true")
                                }
                            } else {
                                preRoll.append(chunk)
                            }
                            if (decision.isSpeech) {
                                if (hasSpeech && audioAt - lastSpeechAt >= 180) onSpeechResumed()
                                if (!hasSpeech) {
                                    firstSpeechAt = now
                                    firstSpeechCaptureAtMs = audioAt
                                    val acceptedPreRoll = preRoll.snapshot()
                                    pcm.append(acceptedPreRoll)
                                    retainedPreRollSampleCount = (pcm.sizeBytes() / 2).toInt()
                                    if (retainedPcmObserver != null || nativePause != null || shadowObserver != null) retainedAudio = pcm.snapshot()
                                    capturedPcmBytes = pcm.sizeBytes()
                                    preRoll.clear()
                                    log("speech_started vad=silero elapsedMs=${now - startedAt}")
                                }
                                hasSpeech = true
                                lastSpeechAt = audioAt
                                lastSpeechAtMs = audioAt
                                lastSpeechSample = audioBytes / 2
                            }
                        }
                        // Observe the retained request, not raw/VAD frames. The copied
                        // bytes leave the PCM lock before any external queue admission.
                        if (retainedWindowRolled) {
                            shadow { it.onInvalidated("window_rolled") }
                            nativePause?.invalidate(NativePauseInvalidation.WINDOW_ROLLED)
                            retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.WINDOW_ROLLED)
                        }
                        if (audioIsComplete) retainedAudio?.let {
                            shadow { observer -> observer.onPcm(it, audioBytes / 2) }
                            nativePause?.retain(it)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onPcm(it)
                        }
                        // Offer the bounded model worker BEFORE optional caption decoding. It
                        // shares this retained PCM, but never blocks capture or owns Gemma.
                        fun endpointFrame(corroborated: Boolean = false) = CaptureShadowFrame(
                            audioBytes / 2, audioAt * 1_000_000L, hasSpeech,
                            decision.isSpeech || corroborated || frame.rawDecision.probability >= .15f ||
                                frame.rawDecision.rawCoverage == null,
                            (audioAt - lastSpeechAt).coerceAtLeast(0), maxOf(speechQueue.bufferedAudioMs, input.bufferedAudioMs))
                        shadow { it.onFrame(endpointFrame()) }
                        // ASR receives every frame from microphone startup. VAD controls
                        // submission and endpointing, not whether initial words reach the recognizer.
                        val decodeStartedAt = nowMs()
                        if (!pendingEndpoint) transcriber?.observeSpeech(decision.isSpeech)
                        val backlogMs = speechQueue.bufferedAudioMs
                        maxRecognitionBacklogMs = maxOf(maxRecognitionBacklogMs, backlogMs)
                        val allowPartial = recognitionBudget.allows(decodeStartedAt, backlogMs) &&
                            !(hasSpeech && decision.probability < 0.15f)
                        if (!pendingEndpoint && !allowPartial) deferredPartialChunks++
                        val segmentsBefore = (transcriber as? SegmentedTranscriber)?.segments
                        val partial = if (pendingEndpoint) null else transcriber?.accept(resumedAudio ?: chunk, allowPartial)?.let {
                            // An unchanged cached hypothesis is not fresh evidence of speech
                            // in a queued or silent frame. Finalization still consumes all PCM.
                            val committed = (transcriber as? SegmentedTranscriber)?.segments != segmentsBefore
                            if (!allowPartial && !committed) null else if (TranscriptContent.isSoundOnly(it)) "" else TranscriptContent.speech(it)
                        }
                        currentCoroutineContext().ensureActive()
                        if (turnCompleted.isCompleted) return@collect
                        val chunkDecodeMs = nowMs() - decodeStartedAt
                        if (!pendingEndpoint) recognitionBudget.completed(decodeStartedAt, nowMs())
                        decodeMs += chunkDecodeMs
                        maxDecodeChunkMs = maxOf(maxDecodeChunkMs, chunkDecodeMs)
                        // Stable words corroborate weak whisper VAD; blank/noisy audio cannot
                        // qualify on amplitude alone. Strong VAD retains its existing fast path.
                        val corroborated = quietEvidence.accept(if (allowPartial) partial else null, decision.probability, audioAt, hasSpeech)
                        followupEvidence.observe(decision.speechSamples?.times(2) ?: chunk.size, decision.probability, partial?.takeIf { it.isNotBlank() } ?: initialConfirmedSpeech().takeIf { initialEvidenceAvailable && it.isNotBlank() }, corroborated, decision.isSpeech)
                        if (corroborated) {
                            quietEvidenceUsed = true
                            nativePause?.invalidate(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO)
                        }
                        if (hasSpeech && corroborated) {
                            if (audioAt - lastSpeechAt >= 180) onSpeechResumed()
                            lastSpeechAt = audioAt
                            lastSpeechAtMs = audioAt
                            lastSpeechSample = audioBytes / 2
                        }
                        if (!hasSpeech && corroborated) {
                            var acceptedPreRoll: ByteArray? = null
                            synchronized(pcm) {
                                firstSpeechAt = now
                                    firstSpeechCaptureAtMs = audioAt
                                val onsetPcm = preRoll.snapshot()
                                pcm.append(onsetPcm)
                                retainedPreRollSampleCount = (pcm.sizeBytes() / 2).toInt()
                                if (retainedPcmObserver != null || nativePause != null || shadowObserver != null) acceptedPreRoll = pcm.snapshot()
                                capturedPcmBytes = pcm.sizeBytes()
                                preRoll.clear()
                                hasSpeech = true
                                lastSpeechAt = audioAt
                                lastSpeechAtMs = audioAt
                                lastSpeechSample = audioBytes / 2
                            }
                            acceptedPreRoll?.let {
                                shadow { observer -> observer.onPcm(it, audioBytes / 2) }
                                nativePause?.retain(it)
                                if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onPcm(it)
                            }
                            log("speech_started source=asr_and_vad probability=${decision.probability} preRollMs=1200")
                        }
                        val smartFrame = endpointFrame(corroborated)
                        shadow { it.onFrame(smartFrame) }
                        val cue = if (captionOnly && nativePause != null) transcriber?.completedEndpointCue else null
                        if (hasSpeech && partial != null) publishPartial(partial)
                        if (captionOnly && nativePause != null && completedCues.update(cue,
                                lastSpeechSample - asrInputStartSample, now, completedCueEnd)) {
                            log("native_endpoint_cue source=completed_asr coveredSamples=${cue?.coveredAudioSamples} " +
                                "latestSpeechSample=${lastSpeechSample - asrInputStartSample} speechClockUnchanged=true")
                        }
                    }
                    val currentSmartFrame = CaptureShadowFrame(audioBytes / 2, audioAt * 1_000_000L,
                        hasSpeech, decision.isSpeech || frame.rawDecision.probability >= .15f ||
                            frame.rawDecision.rawCoverage == null || lastSpeechAt == audioAt,
                        (audioAt - lastSpeechAt).coerceAtLeast(0), maxOf(speechQueue.bufferedAudioMs, input.bufferedAudioMs))
                    shadow { it.onFrame(currentSmartFrame) }
                    val smartDecision = runCatching { endpointObserver?.endpointDecision(currentSmartFrame) }
                        .getOrNull() ?: CaptureEndpointDecision(CaptureEndpointState.FALLBACK)
                    val smartState = smartDecision.state
                    val rawCoverage = frame.rawDecision.rawCoverage
                    val exactCollectorCoverage = rawCoverage != null && rawCoverage.receivedPcmBytes == audioBytes &&
                        rawCoverage.classifiedThroughSample * 2 == audioBytes
                    val unsegmented = (transcriber as? SegmentedTranscriber)?.segments?.let { it == 0 } != false
                    val nativeEligibility = NativePauseEndpointPolicy.eligibility(hasSpeech,
                        nativeSpeechEvidence.snapshot().strongMs, quietEvidenceUsed, audioAt - lastSpeechAt,
                        frame.rawDecision.rawCoverage, awaitingConfirmation, speechQueue.bufferedAudioMs, audioIsComplete)
                        .let { if (!captionOnly || nativePause == null || !unsegmented || !canUseNativePause(firstSpeechCaptureAtMs))
                            NativePauseEndpointPolicy.Eligibility(false, "route_or_playback_guard") else it }
                    val legacyEndpoint = trailingSilenceMs?.let { AdaptiveTurnEnd.Decision(it, "fixed") }
                        ?: turnEnd.decision(now).let {
                            if (it.cue == "no_transcript") it.copy(silenceMs = transcriber?.noTextSilenceMs ?: it.silenceMs) else it
                        }
                    val baseEndpoint = finalNativeEndpointGuard ?: if (trailingSilenceMs != null) legacyEndpoint
                        else if (captionOnly && nativePause != null && unsegmented) completedCues.decision(
                            legacyEndpoint, lastSpeechSample - asrInputStartSample,
                            completedCueEnd.decision(now).takeIf {
                                (exactCollectorCoverage && nativeEligibility.allowed) || it.silenceMs >= legacyEndpoint.silenceMs
                            }) else legacyEndpoint
                    val acousticEndpoint = if (trailingSilenceMs == null) NativePauseEndpointPolicy.decision(
                        baseEndpoint, nativeEligibility, frame.rawDecision.rawCoverage, audioBytes, legacyEndpoint) else baseEndpoint
                    // Smart Turn observes retained PCM, independently of ASR segment seams.
                    // Caption retirement and native speculation keep their separate guards.
                    val smartRouteAllowed = (!captionOnly || unsegmented) && audioIsComplete &&
                        canUseSmartTurn(firstSpeechCaptureAtMs)
                    // On the Whisper/ASR route the verdict is the endpoint authority and
                    // only input sanity gates it. The native-audio route keeps the full
                    // native eligibility conjunction for its Smart Turn decisions.
                    // REFERENCE captures use input sanity on every route: the native
                    // conjunction above survives only as the native-pause speculation
                    // gate (nativeEligibility) and the legacy arbitration below.
                    val smartEligibility = if (turnStopPolicy == TurnStopPolicy.REFERENCE)
                        NativePauseEndpointPolicy.smartTurnSanity(hasSpeech,
                            smartDecision.modelThroughSample, lastSpeechSample)
                    else if (captionOnly) NativePauseEndpointPolicy.eligibility(hasSpeech,
                        nativeSpeechEvidence.snapshot().strongMs, quietEvidenceUsed, audioAt - lastSpeechAt,
                        rawCoverage, awaitingConfirmation, speechQueue.bufferedAudioMs, audioIsComplete)
                    else NativePauseEndpointPolicy.smartTurnSanity(hasSpeech,
                        smartDecision.modelThroughSample, lastSpeechSample)
                    val legacyArbitratedEndpoint = SmartTurnEndpointPolicy.decision(acousticEndpoint, smartDecision,
                        smartEligibility, smartRouteAllowed, rawCoverage, audioBytes, audioAt - lastSpeechAt,
                        lastSpeechSample, bindVerdict = !captionOnly)
                    // REFERENCE arbitration (Pipecat pattern): the analyzer is the stop
                    // strategy. A binding COMPLETE is the only verdict-based endpoint;
                    // any other state leaves the turn open behind the 5s turn-stop
                    // timeout backstop, and with no analyzer attached the plain
                    // speech-timeout window decides. The route/echo gate and the
                    // Adaptive stack no longer end conversation turns here; echo is
                    // still rejected at final-candidate acceptance downstream. That
                    // rejection needs the final transcript, so the native caption
                    // omission at finalization keeps honoring the route gate even
                    // though the bind does not.
                    val endpoint = if (turnStopPolicy != TurnStopPolicy.REFERENCE) legacyArbitratedEndpoint
                        else if (endpointObserver == null) AdaptiveTurnEnd.Decision(
                            trailingSilenceMs ?: SmartTurnEndpointPolicy.SPEECH_TIMEOUT_MS,
                            SmartTurnEndpointPolicy.CUE_SPEECH_TIMEOUT)
                        else SmartTurnEndpointPolicy.referenceDecision(smartDecision, smartEligibility,
                            audioBytes, audioAt - lastSpeechAt, lastSpeechSample)
                            ?: AdaptiveTurnEnd.Decision(SmartTurnEndpointPolicy.TURN_STOP_TIMEOUT_MS,
                                SmartTurnEndpointPolicy.CUE_TURN_STOP_TIMEOUT)
                    val smartComplete = endpoint.cue == "smart_turn_complete"
                    // A Smart-Turn-decided endpoint is final: the queue-drain fire
                    // condition and the final-transcript guard do not re-open it below.
                    // The finalization drain may only hold the seal until queued or
                    // unclassified audio is resolved; it never re-judges the endpoint
                    // or the transcript. LEGACY keeps that finality on the Whisper
                    // route only; REFERENCE binds on every route.
                    val smartVerdictBinding = smartComplete &&
                        (turnStopPolicy == TurnStopPolicy.REFERENCE || !captionOnly)
                    if (nativeEligibility.allowed) nativeEligibleFrames++
                    if (nativeEligibility.allowed && !exactCollectorCoverage) nativeUncoveredEndpointFrames++
                    val nativeReason = if (nativeEligibility.allowed && !exactCollectorCoverage)
                        "raw_tail_unclassified" else nativeEligibility.reason
                    if (nativePause != null && nativeReason != lastNativeEligibilityReason) {
                        lastNativeEligibilityReason = nativeReason
                        log("native_pause_eligibility reason=$nativeReason baseCue=${baseEndpoint.cue} " +
                            "endpointCue=${endpoint.cue} rawCoveredSamples=${frame.rawDecision.rawCoverage?.classifiedThroughSample} " +
                            "collectedSamples=${audioBytes / 2}")
                    }
                    if (nativePause?.canPropose == true && nativeEligibility.allowed && !pendingEndpoint &&
                        SmartTurnEndpointPolicy.permitsSpeculation(smartState) &&
                        // Its independent certificate still requires raw coverage. Do not spend
                        // the encoder for a same-event model endpoint that cannot certify it.
                        (smartState != CaptureEndpointState.COMPLETE || exactCollectorCoverage) &&
                        // REFERENCE spends the encoder only on an analyzer-confirmed
                        // pause; LEGACY also proposes on the adaptive base cues.
                        (smartComplete || (turnStopPolicy != TurnStopPolicy.REFERENCE &&
                            NativePauseEndpointPolicy.permitsProposal(baseEndpoint)))) {
                        shadow { it.onPriority("gemma_speculation") }
                        val snapshot = synchronized(pcm) { pcm.snapshot() }
                        if (nativePause.propose(snapshot, audioBytes / 2, nowNs())) {
                            nativeProposalCount++
                            log("native_pause_frozen samples=${snapshot.size / 2} captureBoundary=${audioBytes / 2} policy=native_frozen_pause_raw_coverage_v1")
                        }
                    }
                    speechQueue.targetSilenceMs = endpoint.silenceMs
                    val endpointSilenceMs = SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint,
                        rawCoverage, audioBytes, lastSpeechSample, audioAt - lastSpeechAt)
                    var reason = when {
                        endRequested -> "explicit_stop"
                        hasSpeech && endpointSilenceMs >= endpoint.silenceMs &&
                            !awaitingConfirmation && (speechQueue.bufferedAudioMs == 0L || smartVerdictBinding) -> "trailing_silence"
                        rejectAtAudioLimit && hasSpeech && capturedPcmBytes >= maxAudioDurationMs.toLong() * 32 -> "audio_input_limit"
                        hasSpeech && capturedPcmBytes >= 120L * 32_000 -> "utterance_capacity"
                        !hasSpeech && initialSilenceTimeoutMs != null && now - lastSpeechAt >= initialSilenceTimeoutMs -> "initial_silence"
                        else -> null
                    }
                    if (reason != null && !turnCompleted.isCompleted) {
                        shadow { it.onPriority("endpoint_finalization") }
                        if (reason != "trailing_silence") nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED)
                        // This proposal can still be invalidated by new speech or
                        // final recognition. Publish it only with the accepted candidate.
                        val proposedEndpointAtNs = nowNs()
                        val finalizeStartedAt = nowMs()
                        val acousticAccepted = !guardFollowupSpeech || !hasSpeech || followupEvidence.accepts()
                        if (guardFollowupSpeech && hasSpeech && !pendingEndpoint) {
                            log("followup_speech_evidence accepted=$acousticAccepted ${followupEvidence.diagnostic()}")
                        }
                        currentCoroutineContext().ensureActive()
                        if (turnCompleted.isCompleted) return@collect
                        if (!acousticAccepted) {
                            log("followup_candidate_rejected microphone=kept_open elapsedMs=${now - startedAt}")
                            onSpeechResumed()
                            hasSpeech = false
                            finalTranscript = ""
                            synchronized(pcm) { pcm.clear(); capturedPcmBytes = 0; preRoll.clear() }
                            shadow { it.onInvalidated("candidate_discarded") }
                            nativePause?.invalidate(NativePauseInvalidation.CANDIDATE_DISCARDED)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onCandidateDiscarded()
                            retainedPreRollSampleCount = null
                            followupEvidence.reset(); initialEvidenceAvailable = false; recoveryAudio.clear()
                            pendingEndpoint = false; finalNativeEndpointGuard = null; retiredCaptionAtPendingEndpoint = false; skippedSmartCaptionAtPendingEndpoint = false
                            pendingAudio.clear(); recognitionIssue = null
                            firstSpeechCaptureAtMs = null; firstSpeechAt = null; firstPartialAfterSpeechMs = null; lastPartial = ""
                            quietEvidence.reset(); quietEvidenceUsed = false; turnEnd.reset()
                            nativeSpeechEvidence.reset(); completedCues.reset(); completedCueEnd.reset(); asrInputStartSample = audioBytes / 2
                            transcriber?.let { previous ->
                                previous.close()
                                recognitionWork.retain(previous.recognitionWorkMetrics)
                            }
                            transcriber = null
                            if (initialSilenceTimeoutMs != null && now - startedAt >= initialSilenceTimeoutMs) {
                                turnCompleted.complete(false)
                                return@collect
                            }
                            transcriber = newTranscriber()
                            lastSpeechAt = startedAt
                            lastSpeechAtMs = null
                            return@collect
                        }
                        if (hasSpeech) {
                            val finishAt = nowMs()
                            if ((retiredCaptionAtPendingEndpoint || skippedSmartCaptionAtPendingEndpoint) && reason != "trailing_silence") {
                                // Explicit stop/capacity are guarded endpoints even if an earlier
                                // silence proposal retired captions before hardware drain.
                                if (!skippedSmartCaptionAtPendingEndpoint) (transcriber as? SegmentedTranscriber)?.resumeAfterEndpoint()
                                skippedSmartCaptionAtPendingEndpoint = false
                                retiredCaptionAtPendingEndpoint = false
                                pendingEndpoint = false
                                // Every pending frame was retained acoustically but skipped by
                                // ASR. Replay that exact tail once before guarded finalization.
                                transcriber?.observeSpeech(decision.isSpeech)
                                val guardedTail = pendingAudio.snapshot()
                                pendingAudio.clear()
                                if (guardedTail.isNotEmpty()) transcriber?.accept(guardedTail, allowPartial = false)
                            }
                            // Whisper text is the authoritative request and must be finalized.
                            // Segmented native captions also retain their finalization/recovery.
                            // The caption omission is the one consumer of a binding
                            // COMPLETE that still honors the Smart Turn route gate.
                            // The gate is closed exactly when the final transcript is
                            // required evidence (the factory closes it when a follow-up
                            // transcript is needed for playback-echo rejection), and a
                            // skipped caption would leave acceptance with no transcript
                            // to reject the echo against, submitting the echo audio as
                            // the request. The verdict itself still binds under
                            // REFERENCE; only the omission is gated.
                            val skipSmartCaption = captionOnly && unsegmented && smartComplete && smartRouteAllowed &&
                                reason == "trailing_silence"
                            captionFinalizationReason = when {
                                skipSmartCaption -> "smart_turn_complete_native_audio"
                                retiredCaptionAtPendingEndpoint -> "clean_native_idle_pending_drain"
                                !captionOnly -> "authoritative_asr"
                                reason != "trailing_silence" -> "endpoint_$reason"
                                !audioIsComplete -> "incomplete_audio"
                                pendingEndpoint -> "pending_endpoint"
                                quietEvidenceUsed -> "quiet_asr_evidence"
                                followupEvidence.strongMs < 240 -> "short_strong_audio"
                                !canRetireIdleCaption(firstSpeechCaptureAtMs) -> "route_or_playback_guard"
                                transcriber?.retireIdleCaption() == true -> "clean_native_idle"
                                else -> "worker_busy_or_unsupported"
                            }
                            val idleCaptionRetired = retiredCaptionAtPendingEndpoint || captionFinalizationReason == "clean_native_idle"
                            retiredCaptionAtPendingEndpoint = idleCaptionRetired
                            // Provisional display words never become final request/echo/control
                            // evidence. Native PCM is already the request on this eligible path.
                            val rawFinal = if (idleCaptionRetired || skipSmartCaption) "" else transcriber?.finish().orEmpty().trim()
                            skippedSmartCaptionAtPendingEndpoint = skipSmartCaption
                            finalAsrStatus = when {
                                skipSmartCaption -> "skipped_smart_turn_caption"
                                idleCaptionRetired -> "skipped_idle_caption"
                                transcriber == null -> "unavailable"
                                else -> "finalized"
                            }
                            if (captionOnly) log("caption_finalization reason=$captionFinalizationReason " +
                                "newFinalDecodeSkipped=${idleCaptionRetired || skipSmartCaption} finalAsrStatus=$finalAsrStatus " +
                                "strongAudioMs=${followupEvidence.strongMs}")
                            finalDecodeMs += nowMs() - finishAt
                            currentCoroutineContext().ensureActive()
                            if (turnCompleted.isCompleted) return@collect
                            // REFERENCE never lets the final transcript defer or
                            // re-lengthen an endpoint: it is an ingredient of the
                            // seal (produced by finish() above), never a re-judgment.
                            if (reason == "trailing_silence" && turnStopPolicy != TurnStopPolicy.REFERENCE &&
                                !smartVerdictBinding && endpoint.silenceMs < legacyEndpoint.silenceMs &&
                                rawFinal.isNotBlank() && !TranscriptContent.isSoundOnly(rawFinal)) {
                                val finalCue = AdaptiveTurnEnd().also { it.update(TranscriptContent.speech(rawFinal), nowMs()) }.decision(nowMs())
                                if (finalCue.cue in setOf("unfinished", "uncertain", "explicit_hesitation") &&
                                    audioAt - lastSpeechAt < finalCue.silenceMs) {
                                    nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED)
                                    turnEnd.update(TranscriptContent.speech(rawFinal), nowMs())
                                    finalNativeEndpointGuard = finalCue
                                    completedCues.reset(); completedCueEnd.reset()
                                    pendingEndpoint = true; pendingAudio.clear()
                                    log("native_endpoint_deferred reason=final_caption_${finalCue.cue} targetSilenceMs=${finalCue.silenceMs}")
                                    return@collect
                                }
                            }
                            recognitionIssue = if (reason == "audio_input_limit") "gemma_audio_request_exceeds_limit"
                                else (transcriber as? SegmentedTranscriber)?.issue
                                    ?: if (reason == "utterance_capacity") "utterance_capacity" else null
                            if (reason == "audio_input_limit" || reason == "utterance_capacity") {
                                nativePause?.invalidate(NativePauseInvalidation.AUDIO_LIMIT)
                                retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.AUDIO_LIMIT)
                            }
                            if (!audioIsComplete && rawFinal.isBlank()) recognitionIssue = "missing_long_transcript"
                            // ASR finalization can take time. Consume new possible speech
                            // or unclassified hardware audio before accepting an old endpoint.
                            // Already classified silence alone cannot invalidate it. Retain the
                            // final words as a segment and continue on the same hardware reader.
                            fun frontierDrainPending(): Boolean =
                                (if (nativePause != null || endpointObserver != null) speechQueue.requiresNativeEndpointDrain(audioBytes) else speechQueue.requiresEndpointDrain(audioAt)) &&
                                (transcriber is SegmentedTranscriber || endpointObserver != null)
                            fun needsFinalizationDrain(): Boolean = reason == "trailing_silence" &&
                                (if (smartVerdictBinding)
                                    // The verdict keeps endpoint authority, but the seal still
                                    // waits out the pipeline frontier: queued or unclassified
                                    // audio can hold a continuation of this turn that finish()
                                    // has not seen, and sealing over it would drop those words
                                    // from the request or split them into a separate turn. Only
                                    // classified silence may be left behind.
                                    frontierDrainPending()
                                else (speechQueue.bufferedAudioMs > 0 || smartComplete || endpoint.cue == "smart_turn_continue") &&
                                    frontierDrainPending())
                            if (needsFinalizationDrain()) {
                                if (!pendingEndpoint) {
                                    pendingAudio.clear()
                                    log("turn_endpoint_deferred reason=audio_arrived_during_finalization")
                                }
                                pendingEndpoint = true
                                return@collect
                            }
                            var resolvedFinal = rawFinal
                            var recoveryAttempted = false
                            if (audioIsComplete && recognitionIssue in setOf("unrecognized_segment", "segment_boundary_uncertain")) {
                                val completePcm = synchronized(pcm) { pcm.snapshot() }
                                log("asr_recovery_started reason=$recognitionIssue source=complete_turn audioMs=${completePcm.size / 32}")
                                val recoveryStarted = nowMs()
                                recoveryAttempted = true
                                val recovered = transcriber?.recover(completePcm).orEmpty().trim()
                                finalDecodeMs += nowMs() - recoveryStarted
                                currentCoroutineContext().ensureActive()
                                if (recovered.isNotBlank() && !TranscriptContent.isSoundOnly(recovered)) {
                                    resolvedFinal = recovered
                                    recognitionIssue = null
                                    log("asr_recovery_finished source=complete_turn accepted=true chars=${recovered.length}")
                                } else log("asr_recovery_finished source=complete_turn accepted=false")
                            }
                            val nonverbal = TranscriptContent.isSoundOnly(resolvedFinal)
                            finalTranscript = if (nonverbal) "" else TranscriptContent.speech(resolvedFinal)
                            if (nonverbal) log(if (captionOnly) "nonverbal_caption ignored=true original_audio_retained=true" else "nonverbal_candidate ignored=true destination=none microphone=kept_open")
                            if (transcriber != null && finalTranscript.isBlank() && !allowAudioOnlyTurns && !nonverbal && recognitionIssue == null) {
                                val candidate = recoveryAudio.snapshot()
                                val recoveryAt = nowMs()
                                log("asr_recovery_started candidateAudioMs=${candidate.size / 32} reason=empty_stream source=full_capture_window nativeVad=bypassed")
                                onRecognitionRecovery(true)
                                try {
                                    recoveryAttempted = true
                                    finalTranscript = TranscriptContent.speech(transcriber?.recover(candidate).orEmpty())
                                    log("asr_recovery_finished chars=${finalTranscript.length} elapsedMs=${nowMs() - recoveryAt}")
                                } finally {
                                    onRecognitionRecovery(false)
                                }
                            }
                            // Recovery is another native decode over an earlier snapshot.
                            // Its completed words cannot seal speech that arrived meanwhile.
                            if (recoveryAttempted && needsFinalizationDrain()) {
                                if (!pendingEndpoint) pendingAudio.clear()
                                pendingEndpoint = true
                                log("turn_endpoint_deferred reason=audio_arrived_during_recovery")
                                return@collect
                            }
                            val finalCandidateRejected = recognitionIssue == null && rejectFinalCandidate(finalTranscript, firstSpeechCaptureAtMs)
                            if (finalCandidateRejected || (!captionOnly && transcriber != null && finalTranscript.isBlank() && (!allowAudioOnlyTurns || nonverbal) && recognitionIssue == null)) {
                                emptyCandidates++
                                hasSpeech = false
                                if (finalCandidateRejected) {
                                    endRequested = false
                                    finalTranscript = ""
                                    finalAsrStatus = "pending"
                                    captionFinalizationReason = "not_attempted"
                                    log("final_candidate_rejected policy=existing_playback_echo microphone=kept_open")
                                }
                                // A new word may be starting in the final, not-yet-confirmed
                                // VAD frame. Replay the tail into the replacement recognizer.
                                val tail = synchronized(pcm) {
                                    val recorded = pcm.snapshot()
                                    val retained = recorded.copyOfRange((recorded.size - 38_400).coerceAtLeast(0), recorded.size)
                                    pcm.clear(); capturedPcmBytes = 0
                                    preRoll.clear()
                                    preRoll.append(retained)
                                    retained
                                }
                                shadow { it.onInvalidated("candidate_discarded") }
                                nativePause?.invalidate(NativePauseInvalidation.CANDIDATE_DISCARDED)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onCandidateDiscarded()
                                retainedPreRollSampleCount = null
                                firstSpeechCaptureAtMs = null; firstSpeechAt = null
                                firstPartialAfterSpeechMs = null
                                lastPartial = ""
                                pendingEndpoint = false
                                finalNativeEndpointGuard = null
                                retiredCaptionAtPendingEndpoint = false
                                skippedSmartCaptionAtPendingEndpoint = false
                                turnEnd.reset()
                                followupEvidence.reset()
                                initialEvidenceAvailable = false
                                quietEvidence.reset()
                                quietEvidenceUsed = false
                                nativeSpeechEvidence.reset(); completedCues.reset(); completedCueEnd.reset(); asrInputStartSample = audioBytes / 2 - tail.size / 2
                                onSpeechResumed()
                                log("empty_speech_candidate ignored=true count=$emptyCandidates microphone=kept_open inactivitySince=last_detected_speech")
                                if (initialSilenceTimeoutMs == null || nowMs() - (if (nonverbal) startedAt else lastSpeechAt) < initialSilenceTimeoutMs) {
                                    decodeMs += nowMs() - finalizeStartedAt
                                    // Finish seals an ASR stream, so replace only that stream/engine.
                                    // The microphone keeps buffering opening words during model reload.
                                    val previous = transcriber
                                    transcriber = null
                                    previous?.close()
                                    if (previous != null) recognitionWork.retain(previous.recognitionWorkMetrics)
                                    val reloadAt = nowMs()
                                    transcriber = newTranscriber()
                                    recoveryAudio.clear()
                                    recoveryAudio.append(tail)
                                    if (tail.isNotEmpty()) transcriber?.accept(tail)
                                    modelLoadMs += nowMs() - reloadAt
                                    return@collect
                                }
                                reason = "initial_silence"
                            }
                            if (hasSpeech && finalTranscript.isBlank() && allowAudioOnlyTurns && recognitionIssue == null) {
                                log("audio_only_turn speechDetected=true destination=gemma")
                            }
                            // The owner seals against finalTranscript. Sending it as a new
                            // partial would cancel a matching draft immediately before seal.
                            log("asr_final chars=${finalTranscript.length} segments=${(transcriber as? SegmentedTranscriber)?.segments} " +
                                "audioComplete=$audioIsComplete issue=$recognitionIssue")
                        }
                        val endpointAuthority = when {
                            endpoint.cue == "smart_turn_complete" || endpoint.cue == "smart_turn_continue" -> "smart_turn"
                            endpoint.cue == SmartTurnEndpointPolicy.CUE_TURN_STOP_TIMEOUT -> "timeout"
                            endpoint.cue == SmartTurnEndpointPolicy.CUE_SPEECH_TIMEOUT -> "speech_timeout"
                            finalNativeEndpointGuard != null -> "guard"
                            else -> "fallback"
                        }
                        onMetrics(AsrCaptureMetrics(modelLoadMs, captureReadyMs, audioBytes / 32,
                            decodeMs, maxDecodeChunkMs, firstPartialAfterSpeechMs, partialUpdates,
                            nowMs() - finalizeStartedAt, reason, emptyCandidates,
                            lastSpeechAtMs?.let { (finalizeStartedAt - it).coerceAtLeast(0) },
                            endpoint.silenceMs, endpoint.cue, acousticMetrics.snapshot(),
                            maxRecognitionBacklogMs, recognitionBudget.largestWorkMs, finalDecodeMs,
                            recognitionWork.withActive(transcriber?.recognitionWorkMetrics, transcriber != null),
                            endpointAuthority = endpointAuthority), finalTranscript)
                        log("capture_endpoint_timing reason=$reason " +
                            "speechEndToFinalMs=${lastSpeechAtMs?.let { (nowMs() - it).coerceAtLeast(0) }} " +
                            "silenceDetectedToFinalMs=${speechQueue.latestSilenceDetectedAtMs?.takeIf { it >= lastSpeechAt }?.let { (nowMs() - it).coerceAtLeast(0) }} " +
                            "finalDecodeMs=$finalDecodeMs deferredPartialChunks=$deferredPartialChunks " +
                            "maxRecognitionWorkMs=${recognitionBudget.largestWorkMs} maxRecognitionBacklogMs=$maxRecognitionBacklogMs " +
                            "recognitionBacklogMs=${speechQueue.bufferedAudioMs}")
                        if (nativePause != null) log("native_pause_capture_summary eligibleFrames=$nativeEligibleFrames " +
                            "uncoveredEndpointFrames=$nativeUncoveredEndpointFrames proposals=$nativeProposalCount " +
                            "finalCue=${endpoint.cue} targetSilenceMs=${endpoint.silenceMs}")
                        if (endpointObserver != null) log("smart_turn_endpoint state=$smartState cue=${endpoint.cue} " +
                            "modelThroughSample=${smartDecision.modelThroughSample} retainedThroughSample=${audioBytes / 2} " +
                            "modelWake=$isModelWake decisionAtNs=$proposedEndpointAtNs speechEndMs=$lastSpeechAt " +
                            "silenceEvidenceMs=$endpointSilenceMs captureSilenceMs=${audioAt - lastSpeechAt} " +
                            "eligibility=${smartEligibility.reason} routeAllowed=$smartRouteAllowed")
                        endpointDecisionAtNs = proposedEndpointAtNs.takeIf { hasSpeech }
                        acceptedTurn = hasSpeech
                        log("turn_endpoint reason=$reason elapsedMs=${now - startedAt} " +
                            "silenceMs=${audioAt - lastSpeechAt} endpointCue=${endpoint.cue} " +
                            "targetSilenceMs=${endpoint.silenceMs} speechDetected=$hasSpeech")
                    } else if (now - lastLevelLogAt >= 1_000L) {
                        lastLevelLogAt = now
                        log("capture_level vad=silero rms=${signal.rms.toInt()} peak=${signal.peak} " +
                            "probability=${decision.probability} speech=${decision.isSpeech} speechDetected=$hasSpeech " +
                            "silenceMs=${audioAt - lastSpeechAt} processingLagMs=${(nowMs() - audioAt).coerceAtLeast(0)} " +
                            "recognitionBacklogMs=${speechQueue.bufferedAudioMs}")
                    }
                }
                } finally { endpointObserver?.setEndpointReadyListener(null); wake.close() }
                // flowOn's producer is cancelled and joined before the owner can
                // borrow the next reader. Prefetched but unacknowledged PCM stays
                // in the call session's history for that reader.
                if (acceptedTurn != null) turnCompleted.complete(acceptedTurn!!)
                else if (!turnCompleted.isCompleted) {
                    nativePause?.invalidate(NativePauseInvalidation.CAPTURE_FAILED)
                    shadow { it.onInvalidated("capture_failed"); it.close() }
                    retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CAPTURE_FAILED)
                    turnCompleted.completeExceptionally(IllegalStateException("Microphone stream ended before the turn completed."))
                }
            } catch (cancelled: CancellationException) {
                shadow { it.onInvalidated("cancelled"); it.close() }
                runCatching { nativePause?.invalidate(NativePauseInvalidation.CANCELLED) }
                runCatching { retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CANCELLED) }
                turnCompleted.cancel()
                throw cancelled
            } catch (error: Throwable) {
                shadow { it.onInvalidated("capture_failed"); it.close() }
                // Deliver model/stream failures to the owner, not the Activity's uncaught handler.
                runCatching { nativePause?.invalidate(NativePauseInvalidation.CAPTURE_FAILED) }
                runCatching { retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CAPTURE_FAILED) }
                turnCompleted.completeExceptionally(error)
            }
        }
        log("capture_ready pcmStartupMs=300 totalReadyMs=$captureReadyMs asrLoadMs=$modelLoadMs")
    }

    suspend fun awaitTurnCompletion(): Boolean = turnCompleted.await()

    suspend fun stop(): ByteArray = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!stopped) {
                stopped = true
                shadow { it.close() }
                val backlogBeforeStop = input.bufferedAudioMs
                try {
                    input.stop()
                } finally {
                    collectionJob?.cancel()
                    collectionJob?.join()
                    collectionJob = null
                    nativePauseCertificate = nativePause?.certificateAfterJoin(
                        synchronized(pcm) { pcm.snapshot() }, endpointDecisionAtNs,
                        maxOf(backlogBeforeStop, input.bufferedAudioMs,
                            if (input.stoppedUnconsumedPcmBytes == 0L) 0L else 1L))
                    turnCompleted.cancel()
                    try { transcriber?.close() } finally { detector?.close() }
                    transcriber = null
                    detector = null
                    recoveryAudio.clear()
                    log("capture_stopped speechDetected=$hasSpeech")
                }
            }
            synchronized(pcm) {
                WavEncoder.pcm16Mono(if (hasSpeech) pcm.snapshot() else preRoll.snapshot(), input.sampleRateHz)
            }
        }
    }

    private fun publishPartial(text: String) {
        val partial = text.trim()
        turnEnd.update(partial, nowMs())
        if (partial.isNotBlank() && partial != lastPartial) {
            lastPartial = partial
            partialUpdates++
            if (firstPartialAfterSpeechMs == null) {
                firstPartialAfterSpeechMs = firstSpeechAt?.let { (nowMs() - it).coerceAtLeast(0) }
            }
            log("asr_partial chars=${partial.length} truncated=${partial.length > 900} text=${partial.take(900)}")
            onPartialTranscript(partial)
        }
    }

    private companion object {
        const val MAX_TURN_BYTES = 25 * 16_000 * 2
    }
}
