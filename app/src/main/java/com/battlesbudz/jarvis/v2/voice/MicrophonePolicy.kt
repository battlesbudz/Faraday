package com.battlesbudz.jarvis.v2.voice

/** Counts are used because Android anonymizes other applications' recording identities. */
object MicrophonePolicy {
    fun externalCommunication(mode: Int, ownsCommunication: Boolean): Boolean =
        mode == 2 || (mode == 3 && !ownsCommunication)

    fun shouldYield(recordingCount: Int, ownsRecorder: Boolean, silenced: Boolean, communication: Boolean): Boolean =
        silenced || communication || recordingCount > if (ownsRecorder) 1 else 0
}
class MicrophoneBusyException : IllegalStateException("Another app is using the microphone; Jarvis yielded.")

class MicrophoneYieldCancellation : kotlinx.coroutines.CancellationException("Microphone yielded to another app")
