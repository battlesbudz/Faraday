package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.atomic.AtomicBoolean

/** Existing playback-tail echo policy decides when retained PCM is actually NEW input. */
internal class CaptionInputAdmission(
    private val needsFinalEchoCheck: () -> Boolean,
    private val onAccepted: () -> Unit,
    private val onPending: () -> Unit = {},
    private val onRejected: () -> Unit = {},
) : RetainedPcmObserver {
    private val accepted = AtomicBoolean(false)
    private fun acceptOnce() { if (accepted.compareAndSet(false, true)) onAccepted() }

    override fun onPcm(retainedPcm16: ByteArray) {
        if (retainedPcm16.isNotEmpty()) {
            if (needsFinalEchoCheck()) onPending() else acceptOnce()
        }
    }

    /** Native audio admission remains independent of optional ASR text; known echo still rejects. */
    fun onFinalCandidate(hasSpeech: Boolean, finalTranscript: String, finalSucceeded: Boolean, rejectedEcho: Boolean,
        nativeAudioAccepted: Boolean = false) {
        if (hasSpeech && !rejectedEcho && (nativeAudioAccepted || finalSucceeded && finalTranscript.isNotBlank())) acceptOnce()
        else onRejected()
    }

    override fun onCandidateDiscarded() { onRejected() } // Revocation remains irreversible.
    override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) { onRejected() }
}
