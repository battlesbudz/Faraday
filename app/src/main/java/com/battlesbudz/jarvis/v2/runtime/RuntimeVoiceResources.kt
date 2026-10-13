package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import com.battlesbudz.jarvis.v2.voice.AndroidAudioInput
import com.battlesbudz.jarvis.v2.voice.CommunicationAudioSession
import com.battlesbudz.jarvis.v2.voice.RoutedAudioInput
import com.battlesbudz.jarvis.v2.voice.SpeechCaptureProfile
import com.battlesbudz.jarvis.v2.voice.VoiceAudioSession
import com.battlesbudz.jarvis.v2.voice.VoiceCallResources
import com.battlesbudz.jarvis.v2.voice.VoiceModelSession
import kotlinx.coroutines.CoroutineScope

/** Owns Android microphone routing and resident capture/model resources for one runtime. */
internal class RuntimeVoiceResources(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recordDiagnostic: (String) -> Unit,
    private val onLevel: (Float) -> Unit
) {
    val smartTurn = com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnCallOwner { model ->
        com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnShadow(backendFactory = {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            com.battlesbudz.jarvis.v2.voice.smartturn.NativeSmartTurnBackend(model)
        }, enabled = true)
    }
    init { scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { smartTurn.close() } }

    @Volatile var appliedSpeechCaptureProfile = SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED
    val resources by lazy {
        VoiceCallResources(
            captureIdentity = { SpeechCaptureProfile.selected(context).id },
            createAudio = { communication ->
                val profile = SpeechCaptureProfile.selected(context)
                appliedSpeechCaptureProfile = profile
                val duplexRoute = communication && android.os.Build.VERSION.SDK_INT >= 31
                val useCommunication = duplexRoute && profile.communicationInput
                val manager = context.getSystemService(android.media.AudioManager::class.java)
                recordDiagnostic("Microphone: capture_profile=${profile.id} requestedSource=${if (useCommunication) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"} requestedNoiseSuppression=${profile.noiseSuppression} appliesTo=recorder_lifetime")
                val input = AndroidAudioInput(scope, echoCancellation = true, noiseSuppression = profile.noiseSuppression,
                    communicationInput = useCommunication, audioManager = manager,
                    onLevel = onLevel,
                    log = { recordDiagnostic("Microphone: $it") })
                val source = if (duplexRoute) RoutedAudioInput(input, acquire = {
                    val route = CommunicationAudioSession.openSpeaker(manager) {
                        recordDiagnostic("Voice call route: $it")
                    }
                    try {
                        route.awaitReady()
                        recordDiagnostic("Voice call route: policy=communication_speaker_v1 source=${if (useCommunication) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"} usage=VOICE_COMMUNICATION")
                        route
                    } catch (error: Throwable) { route.close(); throw error }
                }) else input
                VoiceAudioSession(
                    source, scope,
                    log = { recordDiagnostic("Voice capture ownership: $it") })
            },
            createModels = {
                VoiceModelSession {
                    recordDiagnostic("Voice model ownership: $it")
                }
            })
    }
}
