package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.voice.AudioInput
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import com.battlesbudz.jarvis.v2.voice.CallPromotionLease
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.SpeechDelivery
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * Resource handles acquired by one exact launch job. A handle is registered immediately
 * after acquisition so a later stage failure still releases it. This owner never stores
 * a model, call transcript, memory archive or process accepted-action queue.
 */
internal class VoiceTurnLifetime(
    val scope: CoroutineScope,
    val modelLease: VoiceTurnModelLease,
    val hadActiveCall: Boolean
) {
    val promotionLease = CallPromotionLease()
    var preparation: IncrementalVoiceInput? = null
    var capture: AudioTurnCapture? = null
    var nativeSpeculation: com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation? = null
    var nativeAudioCapture: com.battlesbudz.jarvis.v2.voice.GemmaStreamingAudioCapture? = null
    @Volatile var nativeAudioArtifact: com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore.Lease? = null
    var microphone: AudioInput? = null
    var expectedResourceCall: String? = null
    /** Frozen before microphone capture starts, so a later input cannot be adopted as this turn's revision. */
    var capturedInputRevision: Long? = null
    var capturedInputBoundary: com.battlesbudz.jarvis.v2.voice.CallInputQueue.CaptionBoundary? = null
    var preserveCaptureOnCancellation = false
    var captionNativeReleaseFailed = false
    var output: PiperVoiceOutput? = null
    val finalSpeechDelivery = AtomicReference<SpeechDelivery?>(null)
    val normalReplyPlayback = kotlinx.coroutines.CompletableDeferred<com.battlesbudz.jarvis.v2.voice.NormalReplyPlayback>()
    val speechChunks = Channel<String>(Channel.UNLIMITED)
    var speechJob: Job? = null
    /** Kept after global detachment so queued publication remains bound and fails closed. */
    val answerMemoryBinding = AtomicReference<VoiceMemoryBinding?>(null)
    val answerExpiryJob = AtomicReference<Job?>(null)
    val activePumpTypedInput = AtomicReference<CallFinalInput?>(null)
    var microphoneYielded = false
    var wokeThisTurn = false
    var finalMessage = "Voice Call turn failed."
}
