package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The attempt must finish its cleanup before throwing. Explicit cancellation never restarts. */
suspend fun <T> recoverReplyListener(log: (String) -> Unit = {}, attempt: suspend () -> T): T {
    var failures = 0
    while (true) {
        try { return attempt() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failures++
            val retryMs = (failures.toLong() * 500).coerceAtMost(5000)
            log("barge_listener_failed reason=${error.message} recovery=restart")
            log("barge_listener_restarting attempt=$failures delayMs=$retryMs")
            delay(retryMs)
        }
    }
}
