package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceInvocation
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import com.battlesbudz.jarvis.v2.voice.CallInputQueue
import com.battlesbudz.jarvis.v2.voice.CallTurnOwner
import com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn
import com.battlesbudz.jarvis.v2.voice.ContinuousActionSession
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/** Process-held state of the one user-armed call; turn resources have a shorter owner. */
internal class VoiceCallState {
    @Volatile var armed = false
        set(value) {
            field = value
            if (!value) VoiceSessionUi.liveTranscript.value = ""
            VoiceSessionUi.armed.value = value
        }
    @Volatile var turnJob: Job? = null
    @Volatile var capture: AudioTurnCapture? = null
    @Volatile var output: PiperVoiceOutput? = null
    @Volatile var acceptedSession: ContinuousActionSession<AcceptedVoiceInvocation>? = null
    @Volatile var latestStatus = "Preparing microphone…"
    val playback = kotlinx.coroutines.flow.MutableStateFlow(VoicePlaybackFrame())
    var audioRecoveryAttempts = 0
    val returnToWakeCuePending = AtomicBoolean(false)
    val resumeCommandCue = AtomicBoolean(false)
    val inputQueue = CallInputQueue()
    val typedTurnOwner = CallTurnOwner()
    val pendingVoiceCorrection = AtomicReference<CapturedVoiceTurn?>(null)
    /** A popped typed final remains owned even when the queue refills during a lease race. */
    val pendingTypedHandoff = AtomicReference<CallFinalInput?>(null)
    val preparingTypedInput = AtomicReference<CallFinalInput?>(null)
}
