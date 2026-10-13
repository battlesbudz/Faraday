package com.battlesbudz.jarvis.v2.voice

/** Admit at useful headroom; do not revoke a running decode merely because playback began. */
object DuplexPlaybackBudget {
    fun allows(queuedMs: Long?, continuing: Boolean, unavailable: Boolean): Boolean =
        !unavailable && (queuedMs == null || queuedMs >= if (continuing) 80 else 450)
}
