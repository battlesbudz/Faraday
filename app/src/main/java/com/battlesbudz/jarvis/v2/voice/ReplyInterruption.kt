package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Carries the correction itself across turn cleanup, so the user need not repeat it. */
data class CapturedVoiceTurn(val transcript: String, val wav: ByteArray,
    val audioIsComplete: Boolean = true, val recognitionIssue: String? = null,
    /** One final capture receives one ID and wall-clock boundary; retries preserve both. */
    val utteranceId: String = java.util.UUID.randomUUID().toString(),
    val capturedAtMs: Long = System.currentTimeMillis(),
    val origin: TranscriptOrigin = TranscriptOrigin.SPOKEN,
    /** Monotonic end of the spoken floor; typed inputs intentionally leave it unknown. */
    val speechEndedAtMs: Long? = null,
    /** Only capture-finalized evidence; ordinary barge/provisional carriers leave these absent. */
    val finalAsrStatus: String? = null, val captionFinalizationReason: String? = null,
    val finalAsrEngineId: String? = null, val firstSpeechCaptureAtMs: Long? = null,
    val handoff: VoiceCaptureHandoff? = null)
sealed interface ReplyOutcome<out T> {
    data class Finished<T>(val value: T, val followup: CapturedVoiceTurn? = null) : ReplyOutcome<T>
    data class Interrupted(val correction: CapturedVoiceTurn) : ReplyOutcome<Nothing> {
        /** Verified farewell controls can intentionally carry no PCM. */
        val endsCallSegment: Boolean get() = CallLifetimePolicy.endsSession(
            correction.transcript, correction.recognitionIssue == null)
    }
}

suspend fun <T> runInterruptibleReply(
    reply: suspend () -> T,
    listen: suspend (onConfirmed: () -> Unit) -> CapturedVoiceTurn,
    stopReply: () -> Unit
): ReplyOutcome<T> = coroutineScope {
    val confirmed = CompletableDeferred<Unit>()
    val replyJob = async { reply() }
    val listener = async { listen { confirmed.complete(Unit) } }
    try {
        val interrupted = select<Boolean> {
            confirmed.onAwait { true }
            replyJob.onAwait { false }
        }
        // An already confirmed correction wins a simultaneous completion. Otherwise
        // release the keyword reader immediately: the retained microphone replays
        // post-playback audio into ordinary follow-up ASR, without a keyword-only wait.
        if (interrupted || confirmed.isCompleted) {
            replyJob.cancel(VoiceControlCancellation(VoiceControl.STOP_REPLY))
            stopReply()
            replyJob.join()
            ReplyOutcome.Interrupted(listener.await())
        } else ReplyOutcome.Finished(replyJob.await())
    } finally {
        withContext(NonCancellable) { listener.cancelAndJoin(); replyJob.cancelAndJoin() }
    }
}
