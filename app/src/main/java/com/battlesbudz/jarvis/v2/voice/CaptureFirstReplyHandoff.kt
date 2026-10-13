package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Normal terminal delivered-frame receipt from output, never inferred from Job.isCompleted. */
internal data class NormalReplyPlayback(val turnId: String, val completedAtMs: Long) {
    companion object {
        fun from(turnId: String, delivery: SpeechDelivery?, completedAtMs: Long): NormalReplyPlayback? =
            if (delivery?.turnId == turnId && delivery.state == SpeechDeliveryState.COMPLETED &&
                delivery.playedFrames > 0 && delivery.spans.isNotEmpty() && delivery.spans.all {
                    it.sealed && it.endFrame > it.startFrame && it.endFrame <= delivery.playedFrames
                }) NormalReplyPlayback(turnId, completedAtMs) else null
    }
}

internal object CaptureFirstReplyHandoff {
    sealed interface Result<out T> {
        data class Finished<T>(val answer: T, val followup: CapturedVoiceTurn? = null) : Result<T>
        data class Interrupted(val input: CapturedVoiceTurn) : Result<Nothing>
    }
    class Config(
        val normalPlayback: Deferred<NormalReplyPlayback>,
        val beginCapture: (NormalReplyPlayback) -> AudioInput,
        val capture: suspend (AudioInput, previousReplyCompleted: () -> Boolean) -> CapturedVoiceTurn,
        val awaitTypedInput: suspend () -> Unit,
        val hasTypedInput: () -> Boolean,
        val observe: (String) -> Unit,
    )
    private enum class Event { INTERRUPTED, PLAYBACK, FINISHED }

    suspend fun <T> run(
        normalPlayback: Deferred<NormalReplyPlayback>,
        reply: suspend () -> T,
        listen: suspend (() -> Unit) -> CapturedVoiceTurn,
        stopReply: () -> Unit,
        beginCapture: (NormalReplyPlayback) -> AudioInput,
        capture: suspend (AudioInput, previousReplyCompleted: () -> Boolean) -> CapturedVoiceTurn,
        awaitTypedInput: suspend () -> Unit,
        hasTypedInput: () -> Boolean,
        observe: (String) -> Unit = {},
    ): Result<T> = coroutineScope {
        val confirmed = CompletableDeferred<Unit>()
        val replyJob = async { reply() }
        val listener = async { listen { confirmed.complete(Unit) } }
        var nextCapture: Deferred<CapturedVoiceTurn>? = null
        var typed: Deferred<Unit>? = null
        try {
            val event = select<Event> {
                confirmed.onAwait { Event.INTERRUPTED }
                normalPlayback.onAwait { Event.PLAYBACK }
                replyJob.onAwait { Event.FINISHED }
            }
            if (event == Event.INTERRUPTED || confirmed.isCompleted) {
                replyJob.cancel(VoiceControlCancellation(VoiceControl.STOP_REPLY))
                stopReply()
                replyJob.join()
                return@coroutineScope Result.Interrupted(listener.await())
            }
            if (event == Event.FINISHED && !normalPlayback.isCompleted) {
                return@coroutineScope Result.Finished(replyJob.await())
            }
            // Raw retention becomes ready BEFORE either the old listener/probe or caption joins.
            val delivery = normalPlayback.await()
            val input = beginCapture(delivery)
            observe("capture_first_recording_ready")
            listener.cancel()
            nextCapture = async {
                listener.join() // Same resident ASR slot; never construct a second recognizer.
                observe("capture_first_previous_listener_joined")
                // Successful completion includes the exact reply's native/caption cleanup.
                // This is observation eligibility only; capture never waits on it.
                capture(input) { replyJob.isCompleted && !replyJob.isCancelled }
            }
            typed = async(start = CoroutineStart.UNDISPATCHED) { awaitTypedInput() }
            val spoken = if (hasTypedInput()) null else select<CapturedVoiceTurn?> {
                typed.onAwait { null }
                nextCapture.onAwait { it }
            }
            if (spoken == null) {
                observe("capture_first_typed_handoff")
                nextCapture.cancelAndJoin()
            }
            // Caption cleanup and confirmed-history reset remain under the exact old turn.
            // No sealed next input may reach the next runner/model lease before this join.
            val answer = replyJob.await()
            observe("capture_first_sealed_input_released")
            Result.Finished(answer, spoken)
        } finally {
            withContext(NonCancellable) {
                nextCapture?.cancelAndJoin()
                typed?.cancelAndJoin()
                listener.cancelAndJoin()
                replyJob.cancelAndJoin()
            }
        }
    }
}

internal suspend fun <T> runCaptureFirstReply(
    reply: suspend () -> T,
    listen: suspend (() -> Unit) -> CapturedVoiceTurn,
    stopReply: () -> Unit,
    handoff: CaptureFirstReplyHandoff.Config?,
): ReplyOutcome<T> {
    if (handoff == null) return runInterruptibleReply(reply, listen, stopReply)
    return when (val result = CaptureFirstReplyHandoff.run(handoff.normalPlayback, reply, listen, stopReply,
        handoff.beginCapture, handoff.capture, handoff.awaitTypedInput, handoff.hasTypedInput, handoff.observe)) {
        is CaptureFirstReplyHandoff.Result.Finished -> ReplyOutcome.Finished(result.answer, result.followup)
        is CaptureFirstReplyHandoff.Result.Interrupted -> ReplyOutcome.Interrupted(result.input)
    }
}
