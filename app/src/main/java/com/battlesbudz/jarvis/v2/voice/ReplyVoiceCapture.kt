package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import java.io.File
import kotlinx.coroutines.*

/** Local speech capture alongside generation/playback, with the ordinary mic-priority contract. */
class ReplyVoiceCapture(private val context: Context, private val log: (String) -> Unit) {
    suspend fun listen(output: PiperVoiceOutput, asrDirectory: File?,
                       onConfirmed: () -> Unit, asrEngine: AsrEngine = AsrEngine.WHISPER, onPartialTranscript: (String) -> Unit = {}, trace: VoiceTurnTrace? = null,
                       inputFactory: (suspend () -> AudioInput)? = null, modelSession: VoiceModelSession? = null,
                       /** Action mode listens while native work is silent; its ASR budget is independent of Piper. */
                       asrOnly: Boolean = false,
                       /** No recognizer download, construction or probing in an ASR-free trial. */
                       recognitionEnabled: Boolean = true,
                       /** Interruption-confirmation probe. Independent of final-capture
                        * recognition, so direct-audio turns can confirm natural speech
                        * over playback without transcribing the follow-up request. */
                       probeEnabled: Boolean = recognitionEnabled,
                       /** The action pump can swap its report ledger/output without restarting ASR. */
                       outputProvider: () -> PiperVoiceOutput = { output },
                       onReady: () -> Unit = {},
                       onCandidateRetained: (Long?, Int) -> Unit = { _, _ -> },
                       onCandidateCleared: () -> Unit = {},
                       /** Original finalized ASR and its acoustic end, before echo resolution or control routing. */
                       onMetrics: (AsrCaptureMetrics, String, Long?) -> Unit = { _, _, _ -> }): CapturedVoiceTurn = recoverReplyListener(log) {
        supervisorScope {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val profile = SpeechCaptureProfile.selected(context)
            val input = inputFactory?.invoke() ?: AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java),
                echoCancellation = true, communicationInput = profile.communicationInput,
                noiseSuppression = profile.noiseSuppression, log = log)
            val confirmed = CompletableDeferred<Unit>()
            var naturalReference: String? = null
            var confirmedNaturalText = ""
            var stopOnly = false
            var endConversation = false
            fun currentOutput(): PiperVoiceOutput = outputProvider()
            fun confirm() {
                trace?.mark(VoiceTurnTrace.Stage.INTERRUPTION_CONFIRMED)
                trace?.mark(VoiceTurnTrace.Stage.PLAYBACK_STOP_REQUESTED)
                currentOutput().stopSpeaking()
                confirmed.complete(Unit)
                onConfirmed()
            }
            val gated: AudioInput = if (!probeEnabled) KeywordBargeInAudioInput(input,
                createDetector = { MicroInterruptionKeywords(context.assets) },
                onConfirmed = { keyword ->
                    stopOnly = keyword == "stop"
                    confirm()
                },
                // A keyword hit only interrupts speech in this mode; it never executes an
                // action. Suppress a hit when Piper's recent output contains the same keyword.
                allowKeyword = { keyword ->
                    val ownWords = PlaybackEchoText.words(currentOutput().recentSpokenText())
                    if (keyword == "stop") "stop" !in ownWords
                    else !PlaybackEchoText.words(keyword.replace('_', ' ')).all { it in ownWords }
                }, log = log).also { log("barge_asr_disabled scope=all_natural_and_keyword_verification_probes fallback=keyword_vad_only") }
            else NaturalBargeInAudioInput(input,
                    createKeyword = { MicroInterruptionKeywords(context.assets) },
                    createVad = { SileroSpeechDetector.create(context.assets) },
                    createTranscriber = {
                        asrEngine.createProbe(requireNotNull(asrDirectory), log, modelSession)
                    },
                    playing = { currentOutput().isPlayingAudio }, reference = { currentOutput().recentSpokenText() },
                    hasPlaybackBudget = { asrOnly || currentOutput().hasInterruptionBudget() },
                    canContinuePlayback = { asrOnly || currentOutput().canContinueInterruption() },
                    onNaturalTextConfirmed = { confirmedNaturalText = it },
                    onCandidateRetained = onCandidateRetained,
                    onCandidateCleared = onCandidateCleared,
                    onConfirmed = { natural, evidence ->
                        if (natural) naturalReference = evidence
                        else {
                            stopOnly = evidence == "stop"
                            endConversation = evidence == "stop listening"
                        }
                        confirm()
                    }, log = log)
            lateinit var capture: AudioTurnCapture
            capture = AudioTurnCapture(gated, this,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                createTranscriber = if (recognitionEnabled) { { LazyStreamingTranscriber { asrEngine.create(requireNotNull(asrDirectory), log = log, modelSession = modelSession) } } } else null, log = log,
                trailingSilenceMs = if (recognitionEnabled) null else 650L,
                maxAudioDurationMs = if (recognitionEnabled) 25000 else GemmaAudioInputPolicy.MAX_CAPTURE_MS,
                rejectAtAudioLimit = !recognitionEnabled,
                captionOnly = !recognitionEnabled,
                allowAudioOnlyTurns = true,
                guardFollowupSpeech = true,
                initialConfirmedSpeech = { confirmedNaturalText },
                onMetrics = { metrics, originalText -> onMetrics(metrics, originalText, capture.lastSpeechAtMs) },
                onPartialTranscript = { text -> onPartialTranscript(text) })
            try {
                capture.start(initialSilenceTimeoutMs = null)
                onReady()
                log("barge_capture_ready keywordReadiness=reported_separately naturalSpeechReady=false")
                // Observe capture failures while waiting for speech, too.
                val completion = async { capture.awaitTurnCompletion() }
                kotlinx.coroutines.selects.select<Unit> {
                    confirmed.onAwait { }
                    completion.onAwait { error("Interruption capture ended without confirmed speech") }
                }
                if (stopOnly || endConversation) {
                    // Stop is a control, not a request for an audio-model answer. The retained
                    // microphone hands subsequent speech to the ordinary follow-up listener.
                    log("barge_stop_complete destination=${if (endConversation) "end_conversation" else "followup_listening"} audio_fallback=false")
                    completion.cancel()
                    return@supervisorScope CapturedVoiceTurn(if (endConversation) "stop listening" else "", byteArrayOf())
                }
                withTimeout(130_000) { completion.await() }
                val wav = capture.stop()
                // With no final recognizer (direct-audio turns) the probe's confirmed
                // text is the only recognition there is; it still faces the echo
                // recheck below before it can authorize anything.
                val finalText = capture.finalTranscript.ifBlank {
                    if (recognitionEnabled) "" else confirmedNaturalText
                }
                if (capture.recognitionIssue != null) {
                    return@supervisorScope CapturedVoiceTurn(finalText, wav, capture.audioIsComplete, capture.recognitionIssue, speechEndedAtMs = capture.lastSpeechAtMs)
                }
                val echo = naturalReference
                if (echo != null) {
                    // Recheck the final recognition: provisional words never authorize actions.
                    val checked = NaturalCorrectionText.resolve(finalText, echo)
                    if (checked == null) {
                        log("barge_correction_discarded reason=final_request_not_confirmed")
                        return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                    }
                    if (NaturalCorrectionText.isFloorOnly(checked) &&
                        VoiceActionControl.parse(checked, hasUnfinished = true) == VoiceActionControl.None) {
                        log("barge_floor_handoff text=$checked destination=followup_listening modelAnswer=false")
                        return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                    }
                    return@supervisorScope CapturedVoiceTurn(checked, wav, capture.audioIsComplete, capture.recognitionIssue, speechEndedAtMs = capture.lastSpeechAtMs)
                }
                return@supervisorScope CapturedVoiceTurn(finalText, wav, capture.audioIsComplete, capture.recognitionIssue, speechEndedAtMs = capture.lastSpeechAtMs)
            } catch (busy: MicrophoneBusyException) {
                log("barge_listener_yielded external_microphone=true")
                throw busy
            } catch (timeout: TimeoutCancellationException) {
                log("barge_correction_timeout")
                return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
            } catch (cancelled: CancellationException) {
                // UI microphone pause closes the current audio channel. It is a capture boundary,
                // not an action-task cancellation: return a final local issue so the persistent
                // action pump can wait for Resume and attach a fresh ASR capture.
                if (VoiceSessionUi.paused.value) {
                    log("barge_capture_paused destination=action_pump_resume")
                    return@supervisorScope CapturedVoiceTurn("", byteArrayOf(), recognitionIssue = "microphone_paused")
                }
                throw cancelled
            }
            catch (error: Exception) {
                if (confirmed.isCompleted) {
                    // Never replay an incomplete correction after a capture failure.
                    return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                }
                throw error
            } finally { capture.stop() }
        }
    }
}
