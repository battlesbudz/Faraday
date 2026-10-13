package com.battlesbudz.jarvis.v2.voice

/** Session lifetime is explicit: silence continues an armed user-started call. */
object CallLifetimePolicy {
    fun initialSilenceTimeoutMs(): Long? = null
    fun endsSession(finalTranscript: String, recognitionSucceeded: Boolean): Boolean =
        recognitionSucceeded && VoiceCallPolicy.isGoodbye(finalTranscript)
    fun waitingStatus(): String = "Voice Call is listening — speak now."
}
