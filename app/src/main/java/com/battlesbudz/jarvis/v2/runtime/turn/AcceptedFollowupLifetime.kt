package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** The exact report writer must finish before its output/model session can be released. */
internal class AcceptedReportPlayback(
    private val stop: () -> Unit,
    private val release: () -> Unit,
    private val detach: () -> Unit
) {
    var job: Job? = null
    private var closed = false

    suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        try {
            runCatching(stop)
            job?.cancelAndJoin()
        } finally {
            try { release() } finally { detach() }
        }
    }
}

/** Accepted pump children have a shorter lifetime than the process-owned phone task. */
internal class AcceptedFollowupLifetime {
    var capture: Deferred<CapturedVoiceTurn>? = null
    var deliveryReady: Job? = null
    var workerIdle: Job? = null
    var typedAvailable: Job? = null
    var report: AcceptedReportPlayback? = null

    suspend fun releaseReport() { report?.close(); report = null }

    suspend fun close() = withContext(NonCancellable) {
        try {
            capture?.cancelAndJoin()
            typedAvailable?.cancelAndJoin()
            deliveryReady?.cancelAndJoin()
            workerIdle?.cancelAndJoin()
        } finally { releaseReport() }
    }
}
