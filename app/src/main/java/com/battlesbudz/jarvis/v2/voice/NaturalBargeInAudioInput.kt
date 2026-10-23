package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow

/** VAD-qualified bounded probes, alongside the keyword fallback, on one capture stream. */
class NaturalBargeInAudioInput(
    private val input: AudioInput,
    private val createKeyword: () -> InterruptionKeywordDetector,
    private val createVad: () -> SpeechDetector,
    private val createTranscriber: () -> StreamingTranscriber,
    private val playing: () -> Boolean,
    private val reference: () -> String,
    private val hasPlaybackBudget: () -> Boolean,
    private val onConfirmed: (Boolean, String) -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val canContinuePlayback: () -> Boolean = hasPlaybackBudget,
    private val minimumProbeAudioMs: Int = 250,
    private val onNaturalTextConfirmed: (String) -> Unit = {},
    /** Raw handoff bookkeeping only; these callbacks grant no input/publication authority. */
    private val onCandidateRetained: (Long?, Int) -> Unit = { _, _ -> },
    private val onCandidateCleared: () -> Unit = {}
) : AudioInput {
    init { require(input.sampleRateHz == 16_000 && input.channelCount == 1) }
    override val sampleRateHz get() = input.sampleRateHz
    override val channelCount get() = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        supervisorScope {
            val started = nowMs()
            val keyword = createKeyword()
            try { primeInterruptionKeywords(keyword, input.priorAudioForKeywords, log) }
            catch (error: Throwable) { keyword.close(); throw error }
            val keywordLoadMs = (nowMs() - started).coerceAtLeast(0)
            var keywordBytes = 0L
            var maxKeywordWorkMs = 0L
            var maxBacklogMs = 0L
            var vad: SpeechDetector? = null
            val worker = BoundedInterruptionRecognizer(this, createTranscriber, nowMs, dispatcher = dispatcher, log = log, hasBudget = hasPlaybackBudget, canContinue = canContinuePlayback)
            val workBudget = InterruptionProbeBudget()
            val stopWorkBudget = InterruptionProbeBudget(capacity = 2)
            var budgetDeferrals = 0
            val stopAudio = RollingAudioBuffer(maxDurationMs = 2000)
            data class StopCandidate(val pcm: ByteArray, val at: Long, val reference: String, val id: Long,
                                     val keyword: String, var submitted: Boolean = false)
            var pendingStop: StopCandidate? = null
            var stopProbes = 0
            var stopHits = 0L
            val onset = RollingAudioBuffer(maxDurationMs = 400)
            // One speech episode's audio, retained across machinery resets that
            // are not rejections of the speech itself (candidate window limit,
            // capture backlog), so a restarted candidate still begins at the
            // episode's first word. Rolling at the candidate retention bound.
            val episode = RollingAudioBuffer(maxDurationMs = InterruptionTiming.CANDIDATE_RETAIN_MS)
            var episodeLive = false
            val candidate = RollingAudioBuffer(maxDurationMs = InterruptionTiming.CANDIDATE_RETAIN_MS)
            var active = false
            var submitted = false
            var submittedBytes = 0L
            var candidateProbes = 0
            var retryAt = 0L
            var lastGateDecision = ""
            var workerDeferrals = 0
            var delivered = false
            var disabled = false
            var revision = 0L
            var probes = 0
            var lastSpeechAt = 0L
            var candidateAt = 0L
            var cooldownUntil = 0L
            var hypothesis: BoundedInterruptionRecognizer.Result? = null
            var gate = BargeInGate()
            var keywordReady = false
            var lastKeywordReport = 0L
            var backlogSuspended = false
            var recoveredAt: Long? = null
            var backlogRecoveries = 0
            var rejectedWindows = 0
            var windowRetentionLogged = false
            var staleResults = 0
            var pressureFrames = 0
            var finalReason = "no_confirmed_request"
            val diagnosticEvidence = BargeInDiagnosticEvidence()
            // Playback calibration is local; never contaminate ordinary listening's room profile.
            val acousticGate = CaptureSpeechGate()
            val acousticEvidence = CandidateSpeechEvidence()
            val confirmation = CandidateConfirmation()
            var submittedSpeech = acousticEvidence.snapshot()
            var hypothesisSupported = false
            var evaluatedAudioAt: Long? = null
            var lastAcousticLogAt = Long.MIN_VALUE / 2
            fun reset(keepEpisode: Boolean = false) {
                onCandidateCleared()
                active = false; submitted = false; submittedBytes = 0; candidateProbes = 0; hypothesis = null
                candidate.clear(); gate = BargeInGate(); retryAt = 0; lastGateDecision = ""; revision++
                acousticEvidence.reset(); confirmation.reset(); hypothesisSupported = false; evaluatedAudioAt = null
                windowRetentionLogged = false
                if (!keepEpisode) { episode.clear(); episodeLive = false }
            }
            fun disable(reason: String) {
                if (!disabled) {
                    disabled = true; reset()
                    finalReason = reason
                    log("barge_natural_unavailable reason=$reason fallback=keyword")
                }
            }
            try {
                vad = try { createVad() } catch (error: Exception) {
                    disable("vad_load_${error.javaClass.simpleName}"); null
                }
                log("barge_capture_ready mode=bounded_candidates keywordReady=false naturalSpeechReady=false")
                input.chunks().collect { pcm ->
                    if (delivered) { emit(pcm); return@collect }
                    val now = nowMs()
                    val at = input.lastChunkCaptureTimeMs ?: now
                    stopAudio.append(pcm)
                    val keywordAt = nowMs()
                    val hit = keyword.accept(pcm)
                    if (now - lastKeywordReport >= 1000) {
                        lastKeywordReport = now
                        log("barge_keyword_levels ${keyword.diagnosticSummary}")
                    }
                    keywordBytes += pcm.size
                    maxKeywordWorkMs = maxOf(maxKeywordWorkMs, (nowMs() - keywordAt).coerceAtLeast(0))
                    maxBacklogMs = maxOf(maxBacklogMs, input.bufferedAudioMs)
                    if (!keywordReady && keyword.ready) {
                        keywordReady = true
                        log("barge_keyword_ready keywords=Hey_Jarvis,stop readyMs=${now - started} mode=bounded_candidates " +
                            "loadMs=$keywordLoadMs inputMs=${keywordBytes / 32} maxWorkMs=$maxKeywordWorkMs maxBacklogMs=$maxBacklogMs")
                    }
                    // A raw stop hit is never enough to change action state. It always needs final,
                    // non-echo ASR confirmation, including while no reply audio is playing.
                    if (hit != null && pendingStop == null) {
                        reset() // Results for an earlier natural candidate cannot verify this hit.
                        stopHits++
                        pendingStop = StopCandidate(stopAudio.snapshot(), at, reference(), -stopHits, hit)
                        onCandidateRetained(input.lastChunkSequence, stopAudio.sizeBytes().toInt())
                        log("barge_keyword_candidate keyword=$hit playback=true verification=required evidence=${keyword.lastHitEvidence}")
                    }
                    val polled = worker.poll()
                    val stop = pendingStop
                    if (stop != null) {
                        if (polled?.revision == stop.id) {
                            val fresh = now - stop.at <= InterruptionTiming.RESULT_AGE_MS
                            val accepted = fresh && RecognizedInterruptionWords.confirmsKeyword(stop.keyword, polled.text, stop.reference + " " + reference())
                            pendingStop = null
                            onCandidateCleared()
                            if (accepted) {
                                finalReason = "keyword_${stop.keyword}"
                                delivered = true
                                // This is a final-only decode, not a provisional hypothesis.
                                // Preserve end-call intent instead of reducing every control to
                                // stop-reply and reopening ordinary listening.
                                onConfirmed(false, if (stop.keyword == "stop") {
                                    if (VoiceCallPolicy.isGoodbye(polled.text)) "stop listening" else "stop"
                                } else stop.keyword)
                                worker.close()
                                log("barge_keyword_confirmed keyword=${stop.keyword} verification=asr_non_echo workMs=${polled.workMs}")
                            } else log("barge_stop_rejected reason=${if (fresh) "unconfirmed_or_echo" else "stale"} chars=${polled.text.length} playback_uninterrupted=true")
                            return@collect
                        }
                        if (now - stop.at > InterruptionTiming.RESULT_AGE_MS || worker.unavailable ||
                            (stop.submitted && !worker.busy && worker.retryableFailure)) {
                            log("barge_stop_rejected reason=verification_unavailable playback_uninterrupted=true")
                            pendingStop = null
                            onCandidateCleared()
                            cooldownUntil = now + 500
                            return@collect
                        }
                        if (!stop.submitted && !worker.busy) {
                            if (!stopWorkBudget.available(now) || now - stop.at > InterruptionTiming.START_AGE_MS || !hasPlaybackBudget()) {
                                log("barge_stop_rejected reason=verification_budget playback_uninterrupted=true fallback=Hey_Jarvis")
                                pendingStop = null
                                onCandidateCleared()
                            } else if (worker.submit(stop.id, stop.pcm, stop.at)) {
                                stopProbes++
                                stopWorkBudget.record(now)
                                stop.submitted = true
                            }
                        }
                        return@collect
                    }
                    polled?.let { result ->
                        if (result.revision == revision && now - result.audioAtMs <= InterruptionTiming.RESULT_AGE_MS) {
                            hypothesis = result
                            log("barge_natural_ready scope=candidate revision=$revision resultAgeMs=${now - result.audioAtMs}")
                        } else {
                            staleResults++
                            log("barge_probe_discarded reason=stale revision=${result.revision}")
                        }
                    }
                    if (worker.unavailable) disable("recognizer_budget_or_failure")
                    if (disabled) return@collect
                    if (input.bufferedAudioMs > 600 || now - at > 800) {
                        if (!backlogSuspended) {
                            // A capture backlog is a machinery fault, not a rejection
                            // of the speech: keep the episode so the candidate that
                            // restarts after recovery still begins at its first word.
                            backlogSuspended = true; reset(keepEpisode = episodeLive); onset.clear()
                            log("barge_natural_suspended reason=capture_backlog retryable=true fallback=keyword")
                        }
                        recoveredAt = null
                    }
                    if (backlogSuspended) {
                        if (input.bufferedAudioMs <= 200 && now - at <= 300 && !worker.busy) {
                            if (recoveredAt == null) recoveredAt = now
                            if (now - requireNotNull(recoveredAt) >= InterruptionTiming.RECOVERY_QUIET_MS) {
                                backlogSuspended = false; backlogRecoveries++
                                log("barge_natural_recovered reason=capture_caught_up fresh_candidate_required=true")
                            }
                        } else recoveredAt = null
                        if (backlogSuspended) {
                            // The chunk is real sequential audio arriving late, not
                            // silence: retain it in the episode even though the
                            // candidate machinery is paused.
                            if (episodeLive) episode.append(pcm)
                            return@collect
                        }
                    }
                    if (submitted && !worker.busy && worker.retryableFailure) {
                        submitted = false; hypothesis = null; retryAt = now + 500; workerDeferrals++
                        log("barge_candidate_deferred reason=${worker.retryReason} retryAfterMs=500")
                    }
                    val signal = Pcm16Signal.measure(pcm)
                    val raw = try { vad?.accept(pcm) ?: SpeechDecision(false, 0f) } catch (error: Exception) {
                        disable("vad_${error.javaClass.simpleName}"); SpeechDecision(false, 0f)
                    }
                    val acoustic = acousticGate.accept(raw, signal.rms, at)
                    val speech = acoustic.isSpeech
                    if (now - lastAcousticLogAt >= 1000 || (raw.isSpeech && !speech)) {
                        lastAcousticLogAt = now
                        log("barge_acoustic atMs=$at rms=${signal.rms.toInt()} peak=${signal.peak} " +
                            "rawVad=${raw.probability} rawSpeech=${raw.isSpeech} admitted=$speech floorRms=${acousticGate.noiseFloorRms.toInt()}")
                    }
                    if (disabled) return@collect
                    // Episode lifecycle: an episode opens at the first admitted
                    // speech (seeded with the onset pre-roll so it reaches the
                    // first detected word), mirrors every following chunk while
                    // live, and closes after EPISODE_GAP_MS without admitted
                    // speech while no candidate is active.
                    if (speech && !episodeLive) { episode.append(onset.snapshot()); episodeLive = true }
                    onset.append(pcm)
                    if (episodeLive) episode.append(pcm)
                    if (speech) lastSpeechAt = at
                    if (episodeLive && !active && at - lastSpeechAt > InterruptionTiming.EPISODE_GAP_MS) {
                        episode.clear(); episodeLive = false
                    }
                    if (!active && speech && now >= cooldownUntil && !worker.busy) {
                        active = true; candidateAt = at
                        candidate.append(if (episodeLive && episode.sizeBytes() > 0) episode.snapshot() else onset.snapshot())
                        onCandidateRetained(input.lastChunkSequence, candidate.sizeBytes().toInt())
                    } else if (active) candidate.append(pcm)
                    if (!active) return@collect
                    acousticEvidence.observe(pcm.size, acoustic)
                    if (at - candidateAt + 400 >= InterruptionTiming.CANDIDATE_RETAIN_MS) {
                        reset(); cooldownUntil = now + 1000; rejectedWindows++
                        log("barge_candidate_rejected reason=retention_limit playback_uninterrupted=true")
                        return@collect
                    }
                    val heard = hypothesis
                    val fresh = heard != null && now - heard.audioAtMs <= InterruptionTiming.CONFIRM_AGE_MS &&
                        now - lastSpeechAt <= InterruptionTiming.CONFIRM_AGE_MS
                    val decisionReference = if (heard != null) reference() else ""
                    val lexicalAction = if (fresh) gate.update(true, playing(), now, requireNotNull(heard).text, decisionReference) else BargeInGate.Action.WAIT
                    if (fresh && evaluatedAudioAt != heard.audioAtMs) {
                        evaluatedAudioAt = heard.audioAtMs
                        hypothesisSupported = confirmation.observe(gate.selectedRequest, heard.audioAtMs, submittedBytes, submittedSpeech)
                        LiveCallAudioEvidence.event("candidate revision=$revision text=${heard.text.take(260)} support=${confirmation.reason}", now)
                        log("barge_confirmation revision=$revision reason=${confirmation.reason} audioAtMs=${heard.audioAtMs} " +
                            "admittedMs=${submittedSpeech.admittedMs} strongMs=${submittedSpeech.strongMs} probes=$candidateProbes")
                    }
                    val action = if (hypothesisSupported) lexicalAction else BargeInGate.Action.WAIT
                    if (heard != null) {
                        val decision = if (!fresh) "stale_or_no_recent_speech" else if (!hypothesisSupported && gate.selectedRequest.isNotBlank()) confirmation.reason else gate.reason
                        diagnosticEvidence.record("${heard.revision}/${heard.audioAtMs}", revision,
                            now - heard.audioAtMs, decision, heard.text, decisionReference, gate, fresh)
                        val key = "$decision:${heard.text.length}"
                        if (key != lastGateDecision) {
                            lastGateDecision = key
                            log("barge_candidate_decision revision=$revision reason=$decision chars=${heard.text.length} " +
                                "audioMs=${candidate.sizeBytes() / 32} resultAgeMs=${now - heard.audioAtMs} probes=$candidateProbes")
                        }
                    }
                    if (action == BargeInGate.Action.CONFIRM) {
                        delivered = true
                        finalReason = "natural_confirmed"
                        val echo = reference()
                        onNaturalTextConfirmed(gate.requestText)
                        onConfirmed(true, echo)
                        worker.close() // Final-turn ASR must never overlap the probe lease.
                        log("barge_speech_confirmed method=bounded_candidate preRollMs=${candidate.sizeBytes() / 32} " +
                            "speechOnsetToStopRequestMs=${now - candidateAt} minimumProbeAudioMs=$minimumProbeAudioMs " +
                            "speakerIdentity=disabled wordStabilityMs=300 recognizedWords=\"${gate.requestText.take(120)}\" acousticOnly=false")
                        emit(candidate.snapshot())
                        candidate.clear(); onset.clear(); episode.clear(); episodeLive = false
                    } else if ((heard != null && now - heard.audioAtMs > InterruptionTiming.CONFIRM_AGE_MS) ||
                        (heard == null && !worker.busy && at - lastSpeechAt > 800)) {
                        // A stale hypothesis over speech that is still arriving keeps
                        // its episode: the words were real, only the confirmation
                        // machinery ran out, and a restarted candidate must still
                        // begin at the first word. A candidate that died silent (no
                        // hypothesis, speech ended) ends the episode with it.
                        reset(keepEpisode = heard != null && episodeLive &&
                            now - lastSpeechAt <= InterruptionTiming.EPISODE_GAP_MS)
                        cooldownUntil = now + 500
                    }
                    if (delivered || !active) return@collect
                    if (at - candidateAt >= InterruptionTiming.CANDIDATE_INPUT_MS) {
                        // The input window is shorter than the confirmation protocol's
                        // own worst case: one probe may take LOAD_MS + DECODE_MS, word
                        // stability adds 300 ms, and weak speech needs a second
                        // agreeing probe over fresher audio before it may confirm, so
                        // a legitimate confirmation can need ~6400 ms end to end
                        // against this 3400 ms window (the retention bound
                        // CANDIDATE_RETAIN_MS is sized for that full envelope).
                        // Destroying the candidate here discards the head of the
                        // utterance: the restarted candidate only reaches back the
                        // 400 ms onset pre-roll after a 1000 ms cooldown. So past the
                        // window the candidate is rejected only when confirmation can
                        // no longer progress; while a probe is in flight, a retry is
                        // pending, or a fresh hypothesis with live request words is
                        // still being judged, the candidate and its audio are
                        // retained and probes may continue below.
                        val confirmationInProgress = worker.busy || retryAt > now ||
                            (fresh && (hypothesisSupported || gate.selectedRequest.isNotBlank()))
                        if (!confirmationInProgress) {
                            log("barge_candidate_rejected reason=window_limit lastDecision=${gate.reason} probes=$candidateProbes playback_uninterrupted=true")
                            reset(keepEpisode = episodeLive && now - lastSpeechAt <= InterruptionTiming.EPISODE_GAP_MS)
                            cooldownUntil = now + 1000; rejectedWindows++
                            return@collect
                        }
                        if (!windowRetentionLogged) {
                            windowRetentionLogged = true
                            log("barge_candidate_retained reason=confirmation_in_progress audioMs=${candidate.sizeBytes() / 32} probes=$candidateProbes")
                        }
                    }
                    if (!hasPlaybackBudget()) pressureFrames++
                    if (candidateProbes < 3 && now >= retryAt &&
                        (!submitted || (hypothesis != null && (!hypothesisSupported || gate.reason != "words_settling") && candidate.sizeBytes() >= submittedBytes + minimumProbeAudioMs * 32)) &&
                        at - candidateAt >= 200 && candidate.sizeBytes() >= minimumProbeAudioMs * 32 && hasPlaybackBudget()) {
                        if (!workBudget.available(now)) {
                            budgetDeferrals++
                            if (budgetDeferrals == 1 || budgetDeferrals % 50 == 0)
                                log("barge_probe_deferred reason=rolling_work_budget retryable=true")
                            return@collect
                        }
                        // The recognizer accepts at most PROBE_WINDOW_MS per probe, so
                        // a candidate longer than the window is probed through its
                        // newest audio; every earlier part was covered by the probes
                        // submitted while the candidate grew. The candidate itself
                        // keeps the whole episode for the handoff on confirmation.
                        val candidateAudio = candidate.snapshot()
                        val probeWindowBytes = (InterruptionTiming.PROBE_WINDOW_MS * 32).toInt()
                        val probeAudio = if (candidateAudio.size > probeWindowBytes)
                            candidateAudio.copyOfRange(candidateAudio.size - probeWindowBytes, candidateAudio.size)
                        else candidateAudio
                        val probeStream = LiveCallAudioEvidence.newStream("probe")
                        if (worker.submit(revision, probeAudio, at)) {
                            LiveCallAudioEvidence.record(probeStream, probeAudio, sampleRateHz, at)
                            LiveCallAudioEvidence.event("probe=$probeStream revision=$revision reference=${reference().take(1600)} playing=${playing()}", at)
                            workBudget.record(now)
                            submitted = true; submittedBytes = candidate.sizeBytes(); candidateProbes++; probes++
                            submittedSpeech = acousticEvidence.snapshot()
                            hypothesis = null
                            // A different fresh decode must pass lexical/echo checks again.
                            gate = BargeInGate(); evaluatedAudioAt = null; hypothesisSupported = false
                            log("barge_probe_started revision=$revision preRollMs=${candidate.sizeBytes() / 32} attempt=$probes")
                        }
                    }
                }
            } finally {
                onCandidateCleared()
                worker.close()
                try { vad?.close() } finally {
                    keyword.close(); onset.clear(); candidate.clear(); episode.clear(); stopAudio.clear()
                    log("barge_keyword_summary ready=$keywordReady inputMs=${keywordBytes / 32} " +
                        "loadMs=$keywordLoadMs maxWorkMs=$maxKeywordWorkMs maxBacklogMs=$maxBacklogMs ${keyword.diagnosticSummary}")
                    log("barge_natural_summary probes=$probes budgetDeferrals=$budgetDeferrals stopProbes=$stopProbes stopHits=$stopHits reason=$finalReason backlogRecoveries=$backlogRecoveries " +
                        "backlogSuspended=$backlogSuspended pressureFrames=$pressureFrames " +
                        "windowRejects=$rejectedWindows staleResults=$staleResults workerDeferrals=$workerDeferrals")
                    diagnosticEvidence.entries().forEach(log)
                }
            }
        }
    }
}
