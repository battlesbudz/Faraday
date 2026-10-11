package com.battlesbudz.jarvis.v2.ai

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per native submission clocks. Callback counts are not token counts: LiteRT's
 * Android callbacks can contain several tokens, control output, or empty text.
 * No prompt, generated text, tool arguments, or error message is retained here.
 */
internal class NativeInferenceMeasurement(private val nowNanos: () -> Long = System::nanoTime) {
    val beganAtNanos: Long = nowNanos()
    private val callbackCount = AtomicInteger()
    private val firstCallbackAt = AtomicLong(UNSET)
    private val firstRawTextAt = AtomicLong(UNSET)
    private val firstVisibleTextAt = AtomicLong(UNSET)
    private val terminalAt = AtomicLong(UNSET)
    private val callbackIntervals = ArrayList<Long>()
    private var lastCallbackAt: Long? = null
    private var droppedCallbackIntervals = 0
    private var nativeSubmitNanos: Long? = null
    private var submissionStartedAtNanos: Long? = null
    private var conversationPreparationNanos: Long? = null
    private var reusedEarlyConversation: Boolean? = null
    fun conversationReady(startedAtNanos: Long, earlyPrepared: Boolean) {
        conversationPreparationNanos = (nowNanos() - startedAtNanos).coerceAtLeast(0)
        reusedEarlyConversation = earlyPrepared
    }
    fun submissionStarted(atNanos: Long) { submissionStartedAtNanos = atNanos }


    @Synchronized fun callback(hasText: Boolean): Long {
        val at = nowNanos()
        lastCallbackAt?.let { previous ->
            if (callbackIntervals.size < MAX_INTERVAL_SAMPLES) callbackIntervals += (at - previous).coerceAtLeast(0)
            else droppedCallbackIntervals++
        }
        lastCallbackAt = at
        callbackCount.incrementAndGet()
        firstCallbackAt.compareAndSet(UNSET, at)
        if (hasText) firstRawTextAt.compareAndSet(UNSET, at)
        return at
    }

    fun visibleText() { firstVisibleTextAt.compareAndSet(UNSET, nowNanos()) }
    fun terminal() { terminalAt.compareAndSet(UNSET, nowNanos()) }

    /** Only duration within sendMessageAsync/generateContentStream, excluding preparation. */
    fun submitted(submissionBeganAtNanos: Long) {
        submissionStartedAtNanos = submissionStartedAtNanos ?: submissionBeganAtNanos
        nativeSubmitNanos = (nowNanos() - submissionBeganAtNanos).coerceAtLeast(0)
    }

    @Synchronized fun snapshot(outputChars: Int): Snapshot {
        val returnedAt = nowNanos()
        val endedAt = terminalAt.get().takeUnless { it == UNSET } ?: returnedAt
        val rawAt = firstRawTextAt.get().takeUnless { it == UNSET }
        val visibleAt = firstVisibleTextAt.get().takeUnless { it == UNSET }
        val estimated = if (outputChars <= 0) 0 else (outputChars + 3) / 4
        // Visible text is emitted on the coroutine consumer, which may drain a
        // synchronous native completion afterwards. Keep native terminal and
        // consumer-return durations separate instead of inventing an overlap.
        val visibleDecodeNanos = visibleAt?.let { (returnedAt - it).coerceAtLeast(0) }
        val rawDecodeNanos = rawAt?.let { (endedAt - it).coerceAtLeast(0) }
        return Snapshot(
            durationMs = (endedAt - beganAtNanos).coerceAtLeast(0) / 1_000_000,
            returnDurationMs = (returnedAt - beganAtNanos).coerceAtLeast(0) / 1_000_000,
            nativeSubmitMs = nativeSubmitNanos?.div(1_000_000),
            conversationPreparationMs = conversationPreparationNanos?.div(1_000_000),
            preSubmitMs = submissionStartedAtNanos?.let { (it - beganAtNanos).coerceAtLeast(0) / 1_000_000 },
            submitToFirstCallbackMs = submissionStartedAtNanos?.let { submitted ->
                firstCallbackAt.get().takeUnless { it == UNSET }?.let { (it - submitted).coerceAtLeast(0) / 1_000_000 }
            },
            submitToFirstRawTextMs = submissionStartedAtNanos?.let { submitted ->
                rawAt?.let { (it - submitted).coerceAtLeast(0) / 1_000_000 }
            },
            reusedEarlyConversation = reusedEarlyConversation,
            firstCallbackMs = offset(firstCallbackAt.get()),
            firstRawTextMs = rawAt?.let { (it - beganAtNanos).coerceAtLeast(0) / 1_000_000 },
            firstVisibleTextMs = visibleAt?.let { (it - beganAtNanos).coerceAtLeast(0) / 1_000_000 },
            callbackCount = callbackCount.get(),
            outputChars = outputChars,
            estimatedOutputTokens = estimated,
            visibleDecodeMs = visibleDecodeNanos?.div(1_000_000),
            estimatedVisibleTokensPerSecond = visibleDecodeNanos?.takeIf { it > 0 && estimated > 0 }
                ?.let { estimated * 1_000_000_000.0 / it },
            rawDecodeMs = rawDecodeNanos?.div(1_000_000),
            estimatedRawDecodeTokensPerSecond = rawDecodeNanos?.takeIf { it > 0 && estimated > 0 }
                ?.let { estimated * 1_000_000_000.0 / it },
            callbackIntervalsRetained = callbackIntervals.size,
            callbackIntervalsDropped = droppedCallbackIntervals,
            callbackIntervalP50Ms = percentile(0.5),
            callbackIntervalP95Ms = percentile(0.95),
            callbackIntervalMaxMs = callbackIntervals.maxOrNull()?.div(1_000_000.0)
        )
    }

    private fun offset(at: Long): Long? =
        at.takeUnless { it == UNSET }?.let { (it - beganAtNanos).coerceAtLeast(0) / 1_000_000 }

    private fun percentile(fraction: Double): Double? {
        if (callbackIntervals.isEmpty()) return null
        val ordered = callbackIntervals.sorted()
        return ordered[(kotlin.math.ceil(ordered.size * fraction).toInt() - 1).coerceIn(0, ordered.lastIndex)] / 1_000_000.0
    }

    data class Snapshot(
        val durationMs: Long,
        val returnDurationMs: Long,
        val nativeSubmitMs: Long?,
        val conversationPreparationMs: Long?,
        val preSubmitMs: Long?,
        val submitToFirstCallbackMs: Long?,
        val submitToFirstRawTextMs: Long?,
        val reusedEarlyConversation: Boolean?,
        val firstCallbackMs: Long?,
        val firstRawTextMs: Long?,
        val firstVisibleTextMs: Long?,
        val callbackCount: Int,
        val outputChars: Int,
        val estimatedOutputTokens: Int,
        val visibleDecodeMs: Long?,
        val estimatedVisibleTokensPerSecond: Double?,
        val rawDecodeMs: Long?,
        val estimatedRawDecodeTokensPerSecond: Double?,
        val callbackIntervalsRetained: Int,
        val callbackIntervalsDropped: Int,
        val callbackIntervalP50Ms: Double?,
        val callbackIntervalP95Ms: Double?,
        val callbackIntervalMaxMs: Double?
    )

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val MAX_INTERVAL_SAMPLES = 4096
    }
}
