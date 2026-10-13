package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Playback completion never cancels this optional exact native child. Accepted input does. */
internal object PostAnswerCaptionContinuation {
    class NativeReleaseFailure(cause: Throwable) : IllegalStateException("Caption native release/reset failed", cause)
    enum class End { COMPLETED, NEXT_INPUT, OWNER_REPLACED, TIMEOUT }
    data class Result<T>(val end: End, val value: T? = null)

    suspend fun <T> run(
        isCurrent: () -> Boolean,
        hasNextInput: () -> Boolean,
        awaitNextInput: suspend () -> Unit,
        generate: suspend () -> T,
        checkedReset: suspend () -> Unit,
        observe: (String) -> Unit = {},
        timeoutMs: Long = 12_000L,
    ): Result<T> = supervisorScope {
        if (!isCurrent()) return@supervisorScope Result(End.OWNER_REPLACED)
        if (hasNextInput()) return@supervisorScope Result(End.NEXT_INPUT)
        val next = async(start = CoroutineStart.UNDISPATCHED) { awaitNextInput() }
        val caption = async(start = CoroutineStart.LAZY) { withTimeout(timeoutMs) { generate() } }
        val terminalFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        caption.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) terminalFailure.set(cause)
        }
        var started = false
        try {
            if (!isCurrent()) return@supervisorScope Result(End.OWNER_REPLACED)
            if (hasNextInput()) return@supervisorScope Result(End.NEXT_INPUT)
            started = true
            caption.start()
            val result = try {
                select<Result<T>> {
                    next.onAwait { Result(End.NEXT_INPUT) }
                    caption.onAwait { Result(End.COMPLETED, it) }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                Result(End.TIMEOUT)
            }
            when {
                !isCurrent() -> Result(End.OWNER_REPLACED)
                hasNextInput() -> Result(End.NEXT_INPUT)
                else -> result
            }
        } finally {
            withContext(NonCancellable) {
                if (!caption.isCompleted) observe("caption_cancel_requested")
                caption.cancelAndJoin()
                observe("caption_child_joined")
                next.cancelAndJoin()
                if (started) {
                    // Failed native drain/reset escapes; callers must retain quarantine.
                    try { checkedReset() } catch (error: Throwable) { throw NativeReleaseFailure(error) }
                    terminalFailure.get()?.let { throw NativeReleaseFailure(it) }
                    observe("caption_native_reset_complete")
                }
            }
        }
    }
}
