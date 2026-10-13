package com.battlesbudz.jarvis.v2.runtime.turn



/**
 * One turn's model operation lease. A successful accepted-action promotion transfers the
 * existing lease to the process worker, so finalization cannot release it a second time.
 * Access is confined to the turn coroutine and its synchronous promotion callbacks.
 */
internal class VoiceTurnModelLease(private val acquire: () -> Boolean, private val release: () -> Unit) {
    @Volatile var owned = false
        private set

    fun acquireWhenIdle(noActiveConversation: Boolean): Boolean {
        check(!owned) { "voice_turn_model_lease_already_owned" }
        if (!noActiveConversation || !acquire()) return false
        owned = true
        return true
    }

    fun transfer(acceptOwnership: () -> Unit): Boolean {
        if (!owned) return false
        acceptOwnership()
        owned = false
        return true
    }

    /** All encoder/speculative borrowers must drain, even when the engine itself
     * reports idle. Failed cleanup retains admission instead of rearming a turn. */
    fun finishNativeDrain(nativeBorrowersDrained: Boolean, engineSafeToRelease: Boolean,
                          quarantine: () -> Unit) {
        if (nativeBorrowersDrained && (!owned || engineSafeToRelease)) close()
        else quarantine()
    }

    fun close() {
        if (owned) {
            owned = false
            release()
        }
    }
}
