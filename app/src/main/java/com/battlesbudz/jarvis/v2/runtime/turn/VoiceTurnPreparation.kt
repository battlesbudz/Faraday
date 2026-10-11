package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.Context
import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.conversation.ConversationSessionState
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.runtime.VoiceCapturePlan
import com.battlesbudz.jarvis.v2.runtime.VoiceTurnCaptureFactory
import com.battlesbudz.jarvis.v2.runtime.VoiceTurnOutputFactory
import com.battlesbudz.jarvis.v2.voice.TtsModelStore
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Prepares native/call resources and a started capture; lifetime cleanup owns every acquired handle. */
internal class VoiceTurnPreparation(
    private val context: Context,
    private val call: VoiceCallAccess,
    private val nativeState: ConversationSessionState,
    private val conversation: VoiceConversationAccess,
    private val modelStore: ModelStore,
    private val ttsModels: TtsModelStore,
    private val resources: RuntimeVoiceResources,
    private val conversationHistory: ConversationHistory,
    private val dialogue: VoiceDialogueContext,
    private val promptBuilder: ConversationPromptBuilder,
    private val memory: VoiceMemoryAccess,
    private val diagnosticRecorder: DiagnosticRecorder
) {
    companion object {
        private val nativePauseGenerations = java.util.concurrent.atomic.AtomicLong()
        /** Bind ownership before checkpoint/diagnostic/microphone setup can fail. */
        internal fun beginOwnedCall(
            controller: com.battlesbudz.jarvis.v2.voice.VoiceSessionController,
            conversationId: String,
            lifetime: VoiceTurnLifetime,
        ): com.battlesbudz.jarvis.v2.voice.VoiceCallRecord = synchronized(controller) {
            // A replacement that appeared during wake must never be adopted.
            if (controller.currentCallId() != null) {
                throw kotlinx.coroutines.CancellationException("voice_call_replaced_during_wake")
            }
            try {
                controller.beginCall(conversationId)
            } finally {
                // beginCall sets the identity before saving its checkpoint.
                lifetime.expectedResourceCall = controller.currentCallId()
            }
        }
    }

    suspend fun prepare(request: VoiceTurnRequest, observation: VoiceTurnObservation, lifetime: VoiceTurnLifetime): PreparedVoiceTurn {
        var directAudioTurn = request.directAudioTurn
        while (com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
            call.status("Paused — microphone off. Tap Resume microphone to listen again.")
            kotlinx.coroutines.delay(250)
        }
        com.battlesbudz.jarvis.v2.voice.MicrophoneInterruptionMonitor.awaitAvailable()
        check(lifetime.modelLease.acquireWhenIdle(ConversationWork.activeJobs.get() == 0)) {
            "Another model operation is still finishing. Please try again in a moment."
        }
        call.state.playback.value = com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame()
        call.status(if (request.captionAsrEnabled) "Preparing speech recognition…" else "Preparing audio understanding…")
        val replyAsrEnabled = request.captionAsrEnabled
        val asrDirectory = if (replyAsrEnabled) request.asrEngine.prepare(context, call::status) else null
        observation.benchmark.configuration("asr_work_scope", if (replyAsrEnabled) "caption_and_verified_interruption" else "disabled_entire_call_turn_keyword_vad_only")
        observation.benchmark.configuration("natural_interruption_asr", replyAsrEnabled.toString())
        diagnosticRecorder.record("Voice ASR selected engine=${request.asrEngine.id} model=${request.asrEngine.modelVersion} turn=${request.asrTurnId}")
        check(modelStore.verifyIntegrity(modelStore.selectedModel())) { "The selected model failed integrity verification." }
        val selectedSpec = modelStore.selectedModel()
        val comparisonLoadStarted = System.nanoTime()
        observation.benchmark.mark("llm_setup_started")
        request.comparison?.put("llm_reused", nativeState.engine != null && nativeState.engine?.modelId == selectedSpec.id && nativeState.engine?.audioEnabled == selectedSpec.supportsAudio)
        if (nativeState.engine == null || nativeState.engine?.modelId != selectedSpec.id ||
            nativeState.engine?.audioEnabled != selectedSpec.supportsAudio) {
            nativeState.engine?.close()
            nativeState.engine = null
            val created = LiteRtLmEngine(
                modelStore.selectedModel().id, modelStore.fileFor(modelStore.selectedModel()).path,
                context.cacheDir.path, useGpu = selectedSpec.recommendedGpu,
                tools = if (selectedSpec.supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                audioEnabled = selectedSpec.supportsAudio
            )
            try { created.initialize() } catch (error: Throwable) { created.close(); throw error }
            nativeState.engine = created
        }
        val engine = requireNotNull(nativeState.engine)
        if (directAudioTurn) check(engine.audioEnabled) { "Select an audio-capable Gemma model for Gemma audio understanding." }
        observation.engine = engine
        observation.benchmark.mark("llm_setup_finished")
        observation.benchmark.metric("llm_setup_ms", (System.nanoTime() - comparisonLoadStarted) / 1_000_000)
        engine.onBenchmarkSubmission = observation.benchmark::submission
        engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.ANSWER
        request.comparison?.put("llm_setup_ms", (System.nanoTime() - comparisonLoadStarted) / 1_000_000)
        request.comparison?.put("llm", engine.modelId)
        request.comparison?.put("build", BuildConfig.VERSION_NAME)
        request.comparison?.put("source_commit", BuildConfig.SOURCE_COMMIT)
        request.comparison?.put("capture_asr", if (request.comparison?.request?.path?.usesAudio == true) "none_Gemma_audio" else request.asrEngine.id)
        request.comparison?.put("barge_asr", request.asrEngine.id)
        request.comparison?.put("thermal_before", context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
        request.comparison?.put("media_volume", context.getSystemService(android.media.AudioManager::class.java).getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        request.comparison?.put("audio_mode", context.getSystemService(android.media.AudioManager::class.java).mode)
        request.comparison?.put("context_policy", "fresh_prompt_each_trial_models_retained_by_normal_call_ownership")
        if (request.comparison?.request?.path?.usesAudio == true) check(engine.modelId.startsWith("Gemma", ignoreCase = true) && engine.audioEnabled) {
            "Choose an audio-capable Gemma model before testing Gemma ASR."
        }
        conversation.reset()
        if (directAudioTurn) engine.setToolsEnabled(false)
        nativeState.characters = 0
        val ttsDirectory = ttsModels.ensureReady(request.ttsEngine, call::status)
        val followupBoundary = resources.resources.consumeFollowupBoundary()
        var input = resources.resources.borrowMicrophone("command", followupBoundary,
            communication = call.controller.currentCallId() != null)
        lifetime.microphone = input
        if (call.controller.currentCallId() == null) {
            val wakeDirectory = com.battlesbudz.jarvis.v2.voice.WakeWordModelStore(context).ensureReady(call::status)
            com.battlesbudz.jarvis.v2.voice.PassiveWakeListener(wakeDirectory,
                log = { diagnosticRecorder.record("Voice wake: $it") },
                onReady = {
                    call.status("Waiting for Hey Jarvis — microphone active")
                    if (call.state.returnToWakeCuePending.getAndSet(false)) lifetime.scope.launch {
                        com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                            com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.WAKE_LISTENING,
                            log = { diagnosticRecorder.recordImportant(it) })
                    }
                }).use { wake ->
                input.start()
                call.status("Preparing wake detector — microphone warming up…")
                wake.awaitWake(input)
            }
            beginOwnedCall(call.controller, conversationHistory.current.value.id, lifetime)
                .also { call.events.startDiagnostics("Voice Call ${it.id}") }
            input.stop()
            input = resources.resources.borrowMicrophone("command", communication = true)
            lifetime.microphone = input
            diagnosticRecorder.recordImportant("Wake word detected: Hey Jarvis. ASR and call audio start now.")
            lifetime.wokeThisTurn = true
            call.status("Hey Jarvis detected — getting ready to listen…")
        }
        val expectedCallId = call.controller.currentCallId()
            ?: throw kotlinx.coroutines.CancellationException("voice_call_ended_during_preparation")
        lifetime.expectedResourceCall = expectedCallId
        lifetime.capturedInputBoundary = call.state.inputQueue.captionBoundary(expectedCallId)
        lifetime.capturedInputRevision = call.controller.currentInputRevision(expectedCallId)
        observation.benchmark.configuration("capture_profile", resources.appliedSpeechCaptureProfile.id)
        observation.benchmark.configuration("microphone_source_requested", if (resources.appliedSpeechCaptureProfile.communicationInput && android.os.Build.VERSION.SDK_INT >= 31) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION")
        observation.benchmark.configuration("noise_suppression_requested", resources.appliedSpeechCaptureProfile.noiseSuppression.toString())
        observation.benchmark.configuration("echo_cancellation_requested", "true")
        observation.benchmark.configuration("effect_status_provenance", "requested_settings_actual_effects_in_microphone_diagnostics")
        val resourceKey = "$expectedCallId:${request.asrEngine.id}:${request.ttsEngine.id}"
        val models = resources.resources.modelsFor(resourceKey)
        dialogue.enterCall(expectedCallId)
        val provenance = call.controller.contextProvenance() +
            " sharedConversationId=${conversationHistory.current.value.id} sharedThreadEntries=${conversationHistory.current.value.messages.size}"
        var submissionIndex = 0
        engine.onPromptSubmitted = { submitted, audioSize ->
            request.comparison?.log("prompt audioBytes=$audioSize text=$submitted")
            diagnosticRecorder.recordInferencePrompt(
                "turn=${request.asrTurnId} submission=${++submissionIndex} model=${engine.modelId} " +
                    "mode=${if (audioSize > 0) "audio_text" else "text"} audioBytes=$audioSize " +
                    "audioCorrectionCount=not_observable promptChars=${submitted.length}\n" +
                    provenance + "\n${engine.inputContextDescription()}\nsummaryChars=${dialogue.summaryCharacters()}\n" +
                    "--- Exact submitted text begins ---\n$submitted\n--- Exact submitted text ends ---")
        }
        if (request.comparison != null) dialogue.resetForComparison()
        val voiceHistory = if (request.comparison != null) emptyList() else
            (conversationHistory.context(excludingCall = expectedCallId) +
                call.controller.conversationContext().map { ChatEntry(it.role, it.text) }).takeLast(24)
        // Audio-only trials retain keyword/VAD interruption without warming a recognizer.
        diagnosticRecorder.recordSummary("Voice TTS turn=${request.asrTurnId} engine=${request.ttsEngine.id} " +
            "speechPolicy=piper-natural-v1")
        val output = VoiceTurnOutputFactory(
            context, diagnosticRecorder, onPlayback = { call.state.playback.value = it }
        ).create(request.asrTurnId, ttsDirectory, request.ttsEngine, models, resources.resources, request.comparison,
            onDelivery = { delivery ->
                lifetime.finalSpeechDelivery.set(delivery)
                call.controller.updateDelivery(expectedCallId, delivery)
                com.battlesbudz.jarvis.v2.voice.NormalReplyPlayback.from(
                    request.asrTurnId, delivery, System.nanoTime() / 1_000_000
                )?.let { lifetime.normalReplyPlayback.complete(it) }
            }, onMetrics = observation.telemetry::recordTts)
        lifetime.output = output
        call.state.output = output
        output.setInterrupted(com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.interrupted.value)
        // Preload Piper while listening; this channel stays empty until final validation.
        lifetime.speechJob = lifetime.scope.launch(Dispatchers.Default) {
            try {
                output.speak(lifetime.speechChunks.receiveAsFlow().filter {
                    val binding = lifetime.answerMemoryBinding.get()
                    binding == null ||
                        (memory.delivery.binding.get() === binding && memory.fence.isValid(binding.ticket) && binding.context.isCurrent())
                }) {
                    observation.telemetry.recordFirstPlayback(expectedCallId, call.controller)
                    call.status("Jarvis is speaking…")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Throwable) {
                request.comparison?.put("tts_error", error.message ?: error.javaClass.simpleName)
                diagnosticRecorder.record("Voice TTS failure: ${error.message}")
                call.status("Voice playback failed: ${error.message}")
            }
        }
        val incremental = com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput(
            lifetime.scope, promptBuilder.voiceInputPrefix(voiceHistory, (selectedSpec.contextTokens ?: 4096) < 2048), engine::createVoicePrefillSession,
            canPrefill = {
                val thermal = if (android.os.Build.VERSION.SDK_INT >= 29)
                    context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
                val allowed = !directAudioTurn && selectedSpec.incrementalGemmaInput && models.workScheduler.admitPrefill(input.bufferedAudioMs, thermal)
                diagnosticRecorder.record("Voice input scheduler: allowed=$allowed reason=${models.workScheduler.reason} " +
                    "thermal=$thermal cutoff=5 backlogMs=${input.bufferedAudioMs}")
                allowed
            }, log = {
                request.comparison?.log("incremental $it")
                diagnosticRecorder.recordSummary("Voice incremental turn=${request.asrTurnId} $it")
                if (it.startsWith("input_final"))
                    diagnosticRecorder.recordTurnEvidence(request.asrTurnId, it.substringBefore(' '), it)
            })
        lifetime.preparation = incremental
        diagnosticRecorder.recordSummary("Voice input: speaker_identity=disabled interruption_policy=recognized_non_echo_words")
        val correction = request.queuedTypedInput?.let { typed ->
            com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                typed.text, byteArrayOf(), utteranceId = typed.id, capturedAtMs = typed.capturedAtMs,
                origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
            )
        } ?: call.state.pendingVoiceCorrection.getAndSet(null)
        correction?.handoff?.let { sealed ->
            check(sealed.claim(expectedCallId, conversationHistory.current.value.id, correction)) {
                "capture_first_handoff_stale_or_already_claimed"
            }
        }
        if (correction?.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) directAudioTurn = false
        val earlyPreparationJob = lifetime.scope.coroutineContext[kotlinx.coroutines.Job]
        val earlyGemma = if (directAudioTurn && correction == null && selectedSpec.incrementalGemmaInput)
            com.battlesbudz.jarvis.v2.voice.EarlyGemmaPreparationGate {
                earlyPreparationJob?.isActive == true && lifetime.modelLease.owned &&
                    call.controller.currentCallId() == expectedCallId && call.state.armed
            }.also { gate -> earlyPreparationJob?.invokeOnCompletion { gate.cancel() } }
        else null
        if (directAudioTurn && selectedSpec == com.battlesbudz.jarvis.v2.ai.ModelCatalog.gemma4E2b &&
            request.comparison == null && correction == null) {
            val preparationContext = kotlin.coroutines.coroutineContext
            val exactModelLease = lifetime.modelLease
            val exactTurnJob = lifetime.scope.coroutineContext[kotlinx.coroutines.Job]
            observation.benchmark.mark("native_audio_artifact_prepare_started")
            val artifact = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore.acquire(
                    modelStore.fileFor(selectedSpec), java.io.File(context.cacheDir, selectedSpec.id),
                    readAsset = context.assets::open,
                    exactSourceVerified = { modelStore.matchesVerifiedArtifact(selectedSpec,
                        com.battlesbudz.jarvis.v2.ai.audio.WeightlessEncoderRecipe.SOURCE_SHA256) },
                    modelLeaseHeld = { exactModelLease.owned },
                    cancelled = { preparationContext[kotlinx.coroutines.Job]?.isActive != true })
                    .also {
                        lifetime.nativeAudioArtifact = it
                        observation.benchmark.mark("native_audio_artifact_prepare_finished")
                    }
            }
            try {
                lifetime.nativeAudioCapture = com.battlesbudz.jarvis.v2.voice.GemmaStreamingAudioCapture(
                    artifact, request.asrTurnId,
                    turnIsCurrent = { exactTurnJob?.isActive == true &&
                        call.controller.currentCallId() == expectedCallId && call.state.armed },
                    beforeEncoderCreate = { earlyGemma?.awaitReady() },
                    onEncoderPreparationWait = { waitMs ->
                        observation.benchmark.metric("native_encoder_early_prefill_wait_ms", waitMs)
                    }, cancelEncoderPreparation = { earlyGemma?.cancel() },
                    onEncoderWork = { work ->
                        observation.benchmark.configuration("native_encoder_work_scope", "append_and_seal_api_wall_all_candidates_not_llm_prefill_or_individual_graph_steps")
                        observation.benchmark.metric("native_encoder_busy", if (work.busy) 1 else 0)
                        observation.benchmark.metric("native_encoder_append_calls", work.appendCalls)
                        observation.benchmark.metric("native_encoder_append_cumulative_ms", work.appendWorkNs / 1_000_000)
                        observation.benchmark.metric("native_encoder_append_max_ms", work.maxAppendWorkNs / 1_000_000)
                        observation.benchmark.metric("native_encoder_seal_work_ms", work.sealWorkNs / 1_000_000)
                        observation.benchmark.metric("native_encoder_queued_pcm_ms", work.queuedBytes / 32)
                        observation.benchmark.metric("native_encoder_peak_queued_pcm_ms", work.peakQueuedBytes / 32)
                        if (!work.busy) diagnosticRecorder.recordSummary("Voice encoder work turn=${request.asrTurnId} " +
                            "phase=${work.phase} appendCalls=${work.appendCalls} cumulativeMs=${work.appendWorkNs / 1_000_000} " +
                            "lastMs=${work.lastWorkNs / 1_000_000} maxMs=${work.maxAppendWorkNs / 1_000_000} " +
                            "sealMs=${work.sealWorkNs / 1_000_000} queuedPcmMs=${work.queuedBytes / 32}")
                    })
            } catch (error: Throwable) { artifact.close(); lifetime.nativeAudioArtifact = null; throw error }
            observation.benchmark.configuration("native_audio_encoder", "gemma4_e2b_stateful_cpu_v1")
            observation.benchmark.configuration("native_audio_encoder_sha256",
                com.battlesbudz.jarvis.v2.ai.audio.WeightlessEncoderRecipe.OUTPUT_SHA256)
        }
        val nativePauseGeneration = nativePauseGenerations.incrementAndGet().also { check(it > 0) }
        val nativeCapture = lifetime.nativeAudioCapture
        val nativePreview = if (nativeCapture != null) conversation.previewNativeAudioPrompt() else null
        if (nativeCapture != null && nativePreview != null) {
            val exactTurnJob = lifetime.scope.coroutineContext[kotlinx.coroutines.Job]
            lifetime.nativeSpeculation = com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation(
                lifetime.scope, expectedCallId, request.asrTurnId, nativePauseGeneration,
                com.battlesbudz.jarvis.v2.voice.NativeSpeculativeAudioDriver(engine, nativeCapture), nativePreview,
                admissible = {
                    val thermal = context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0
                    directAudioTurn && correction == null && request.comparison == null &&
                        (earlyGemma == null || earlyGemma.ready) && input.bufferedAudioMs == 0L && thermal < 5
                }, ownerIsCurrent = {
                    exactTurnJob?.isActive == true && lifetime.modelLease.owned &&
                        call.controller.currentCallId() == expectedCallId && call.state.armed
                }, observe = { event ->
                    observation.benchmark.mark(event.substringBefore(' '))
                    diagnosticRecorder.recordSummary("Voice speculation turn=${request.asrTurnId} $event")
                })
            observation.benchmark.configuration("native_speculation", "frozen_pause_exact_prompt_v1")
            observation.benchmark.configuration("native_speculation_budget", "attempts=1,draft_ms=1500,held_chars=4096,held_utf8_bytes=16384,estimated_tokens=1024,callbacks=64,tts_pcm_bytes=0")
        } else observation.benchmark.configuration("native_speculation", "ineligible_no_native_capture_or_current_context")
        val exactShadowTurnJob = lifetime.scope.coroutineContext[kotlinx.coroutines.Job]
        val shadowTelemetry = com.battlesbudz.jarvis.v2.diagnostics.SmartTurnBenchmarkTelemetry(observation.benchmark) { category, evidence ->
            diagnosticRecorder.recordTurnEvidence(request.asrTurnId, category, evidence)
        }
        val shadowObserver = try { resources.smartTurn.beginCapture(
            expectedCallId, request.asrTurnId, nativePauseGeneration,
            enabled = directAudioTurn && request.comparison == null &&
                com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnSettings.enabled(context),
            model = com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnSettings.store(context).availableFile(),
            telemetry = shadowTelemetry,
            ownerIsCurrent = { exactShadowTurnJob?.isActive == true && call.state.armed &&
                call.controller.currentCallId() == expectedCallId },
            observeCapture = correction == null,
            endpointEnabled = directAudioTurn,
            admissionBlocker = {
                when {
                    lifetime.nativeSpeculation?.consumedEncoder == true -> "gemma_speculation"
                    (context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0) >= 3 -> "thermal_severe"
                    else -> null
                }
            }) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            resources.smartTurn.closeCall(expectedCallId)
            runCatching { observation.benchmark.configuration("smart_turn_mode", "unavailable_optional_setup") }
            null
        }
        // Preparation may fail before capture owns the observer. Completion revokes
        // that exact observer without closing a newer call or waiting for native work.
        exactShadowTurnJob?.invokeOnCompletion { shadowObserver?.close() }
        val capturePlan = VoiceCapturePlan(
            request.asrTurnId, request.asrEngine, asrDirectory, directAudioTurn, request.captionAsrEnabled, followupBoundary != null)
        val activeCapture = VoiceTurnCaptureFactory(
            context, diagnosticRecorder
        ).create(lifetime.scope, input, models, capturePlan, request.comparison, call::status,
            retainedPcmObserver = lifetime.nativeAudioCapture,
            shadowObserver = shadowObserver,
            needsFollowupTranscript = resources.resources::needsFollowupTranscript,
            nativePauseObserver = lifetime.nativeSpeculation,
            nativePauseTurnId = request.asrTurnId,
            nativePauseGeneration = nativePauseGeneration,
            onMetrics = { metrics, text -> observation.telemetry.recordAsr(metrics, text, lifetime.capture) },
            onPartialTranscript = { text ->
                if (text.isNotBlank()) request.comparison?.mark("asr_first_partial")
                request.comparison?.log("partial atMs=${System.nanoTime() / 1_000_000} text=$text")
                if (!directAudioTurn) incremental.submit(text)
                call.events.post {
                    if (call.state.capture === lifetime.capture) call.events.transcript("You", text, false)
                }
            })
        lifetime.capture = activeCapture
        call.state.capture = activeCapture
        request.comparison?.put("input_is_interruption_correction", correction != null)
        request.comparison?.mark("capture_start")
        if (correction == null) activeCapture.start(com.battlesbudz.jarvis.v2.voice.CallLifetimePolicy.initialSilenceTimeoutMs())
        if (correction == null) {
            observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.MICROPHONE_READY)
            observation.benchmark.mark("microphone_ready")
        }
        if (earlyGemma != null) {
            val started = System.nanoTime()
            observation.benchmark.mark("early_gemma_session_prepare_started")
            observation.benchmark.configuration("early_gemma_preface_policy", com.battlesbudz.jarvis.v2.ai.GemmaVoicePreface.POLICY)
            observation.benchmark.configuration("early_gemma_preface_scope", "immutable_app_instructions_only_system_role_no_history_memory_or_audio")
            try {
                check(engine.prepareVoiceConversation {
                    earlyPreparationJob?.isActive == true && lifetime.modelLease.owned &&
                        call.controller.currentCallId() == expectedCallId && call.state.armed
                }) { "early_gemma_preparation_owner_changed" }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                observation.benchmark.metric("early_gemma_session_with_fixed_prefill_ms", elapsed)
                observation.benchmark.mark("early_gemma_fixed_prefill_completed")
                diagnosticRecorder.recordSummary("Voice early Gemma turn=${request.asrTurnId} " +
                    "sessionAndFixedPrefillMs=$elapsed completed=true historyPrefilled=false audioPrefilled=false " +
                    "policy=${com.battlesbudz.jarvis.v2.ai.GemmaVoicePreface.POLICY}")
                earlyGemma.complete()
            } catch (error: Throwable) {
                earlyGemma.cancel()
                observation.benchmark.configuration("early_gemma_preparation_failure", error.javaClass.simpleName)
                throw error
            }
        }
        val callAudioManager = context.getSystemService(android.media.AudioManager::class.java)
        val callStream = com.battlesbudz.jarvis.v2.voice.CallAudioRouting.stream
        diagnosticRecorder.recordImportant("Voice call route: turn=${request.asrTurnId} mode=${callAudioManager.mode} " +
            "usage=${com.battlesbudz.jarvis.v2.voice.CallAudioRouting.usage} stream=$callStream " +
            "volume=${callAudioManager.getStreamVolume(callStream)}/${callAudioManager.getStreamMaxVolume(callStream)}")
        request.comparison?.put("audio_mode", callAudioManager.mode)
        request.comparison?.put("playback_stream", callStream)
        request.comparison?.put("playback_volume", callAudioManager.getStreamVolume(callStream))
        if (request.comparison != null) check(!callAudioManager.isStreamMute(callStream) && callAudioManager.getStreamVolume(callStream) > 0) {
            "Unmute call audio before comparing audible response timing."
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (!call.controller.setStateIfCurrent(expectedCallId, VoiceSessionState.ACTIVELY_LISTENING)) {
            throw kotlinx.coroutines.CancellationException("voice_call_ended_during_capture_start")
        }
        call.status("Voice Call is listening — speak now.")
        if (correction == null && (lifetime.wokeThisTurn || call.state.resumeCommandCue.getAndSet(false))) {
            com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.COMMAND_READY,
                log = { diagnosticRecorder.recordImportant(it) })
            diagnosticRecorder.recordImportant("Wake acknowledged; command microphone ready.")
        }
        return PreparedVoiceTurn(engine, selectedSpec, asrDirectory, ttsDirectory, input, expectedCallId, models, voiceHistory, output, incremental, activeCapture, correction, directAudioTurn, replyAsrEnabled)
    }
}
