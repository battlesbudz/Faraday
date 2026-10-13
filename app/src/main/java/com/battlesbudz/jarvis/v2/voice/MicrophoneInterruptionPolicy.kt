package com.battlesbudz.jarvis.v2.voice

/** Start the quiet interval only AFTER our recorder is released. Never acquire to probe. */
class MicrophoneInterruptionPolicy(private val quietMs: Long = 3000) {
    private var interrupted = false
    private var quietSince: Long? = null
    fun update(busy: Boolean, nowMs: Long, ownReleased: Boolean = true): Boolean {
        if (busy) {
            interrupted = true
            quietSince = null
        } else if (interrupted) {
            if (!ownReleased) {
                quietSince = null
                return true
            }
            val since = quietSince ?: nowMs.also { quietSince = it }
            if (nowMs - since >= quietMs) { interrupted = false; quietSince = null }
        }
        return interrupted
    }
}
