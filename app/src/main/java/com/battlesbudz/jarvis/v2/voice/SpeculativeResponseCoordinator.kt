package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Exact, ephemeral request identity. Never persist the prompt/PCM hashes as diagnostics. */
@ConsistentCopyVisibility
internal data class SpeculativeInputIdentity private constructor(
    val callId: String,
    val turnId: String,
    val captureGeneration: Long,
    val promptSha256: String,
    val pcmSamples: Int,
    val pcmSha256: String,
) {
    companion object {
        fun audio(callId: String, turnId: String, captureGeneration: Long,
                  exactPrompt: String, completePcm16: ByteArray): SpeculativeInputIdentity {
            require(callId.isNotBlank() && turnId.isNotBlank() && captureGeneration > 0)
            require(exactPrompt.isNotBlank() && exactPrompt.length <= 48_000)
            require(completePcm16.isNotEmpty() && completePcm16.size % 2 == 0 && completePcm16.size <= 960_000)
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return SpeculativeInputIdentity(callId, turnId, captureGeneration,
                hash(exactPrompt.toByteArray(Charsets.UTF_8)), completePcm16.size / 2, hash(completePcm16))
        }
    }
}

/**
 * A turn-owned, effect-free speculative lane. [generate] and [rollback] borrow the
 * SAME admitted native owner; rollback must join native callbacks and destroy its
 * speculative history before returning. No other inference may borrow it until
 * [awaitIdle] returns. Coroutine completion alone is never proof of native drain.
 *
 * The only publication port is supplied to [promote] AFTER endpoint, routing and
 * full input/prompt validation. There is deliberately no TTS, action, memory or
 * history port. Capture supplies an explicit frozen identity plus a joined,
 * raw-coverage-certified tail; every undecidable/mismatching input falls back.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SpeculativeResponseCoordinator<I : Any>(
    private val scope: CoroutineScope,
    private val generate: suspend (I, (String) -> Unit) -> GenerationResult,
    private val rollback: suspend () -> Unit,
    private val nativeSafeToRelease: () -> Boolean,
    private val ownerIsCurrent: () -> Boolean,
    private val budget: Budget = Budget(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val observe: (String) -> Unit = {},
) {
    data class Budget(
        val maxAttempts: Int = 2,
        val maxGenerationMs: Long = 1_500,
        val maxHeldChars: Int = 4_096,
        val maxHeldUtf8Bytes: Int = 16_384,
        /** Token IDs are unavailable. This is a documented four-chars/token estimate. */
        val maxEstimatedTokens: Int = 1_024,
        val maxQueuedCallbacks: Int = 64,
    ) {
        init {
            require(maxAttempts in 1..2 && maxGenerationMs in 1..5_000)
            require(maxHeldChars in 1..8_192 && maxHeldUtf8Bytes in 1..32_768)
            require(maxEstimatedTokens in 1..2_048 && maxQueuedCallbacks in 1..128)
        }
    }
    enum class Invalidation { RESUMED_SPEECH, INPUT_CHANGED, CALL_REPLACED, TYPED_INPUT, STOP, OWNER_LOST }
    class Quarantined(cause: Throwable?) : IllegalStateException("Speculative native owner did not drain; retain its model lease", cause)
    private class BudgetExceeded : IllegalStateException("Speculative response budget exceeded")
    private data class Completion(val result: GenerationResult?, val failure: Throwable?)
    private class Candidate<I>(val identity: SpeculativeInputIdentity, val input: I, capacity: Int) {
        val tokens = Channel<String>(capacity)
        val completed = CompletableDeferred<Completion>()
        lateinit var job: Job
        var invalidated = false
        var promoted = false
        var acceptingCallbacks = true
        var generationFailure: Throwable? = null
        val streamDigest = MessageDigest.getInstance("SHA-256")
        var queuedChars = 0
        var queuedBytes = 0
        var generatedChars = 0
        var firstTokenObserved = false
    }
    private val gate = Any()
    private var active: Candidate<I>? = null
    private var attempts = 0
    private var closed = false
    private var finalClaimed = false
    private var quarantine: Throwable? = null
    private fun event(message: String) { runCatching { observe(message) } }

    /** Nonblocking admission; no candidate backlog, replacement inference, or native call here. */
    fun propose(identity: SpeculativeInputIdentity, input: I): Boolean {
        val candidate = synchronized(gate) {
            if (closed || finalClaimed || quarantine != null || active != null ||
                attempts >= budget.maxAttempts || scope.coroutineContext[Job]?.isActive == false || !ownerIsCurrent()) return false
            Candidate(identity, input, budget.maxQueuedCallbacks).also {
                active = it
                attempts++
                val candidate = it
                it.job = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) { run(candidate) }
                event("speculative_launched")
                it.job.invokeOnCompletion { error ->
                    // Atomic start guarantees the ownership cleanup even when
                    // cancellation arrives before the dispatcher starts the body.
                    // This fallback also closes a rejected/failed launch mailbox.
                    if (candidate.completed.complete(Completion(null, error ?: CancellationException("Speculation did not run"))))
                        candidate.tokens.cancel()
                    synchronized(gate) {
                        if (candidate.invalidated && active === candidate && quarantine == null) active = null
                    }
                }
            }
        }
        return true
    }

    /** Revokes publication synchronously. Native cancel/drain remains on the owning worker. */
    fun invalidate(reason: Invalidation) {
        val candidate = synchronized(gate) {
            active?.also {
                it.invalidated = true
                it.tokens.cancel()
                it.queuedChars = 0
                it.queuedBytes = 0
                if (it.job.isCompleted && quarantine == null) active = null
            }
        }
        candidate?.job?.cancel(CancellationException("speculation_${reason.name.lowercase()}"))
        event("speculative_invalidated reason=${reason.name.lowercase()}")
    }

    /**
     * This is the final ordinary-reply boundary, not an ASR partial callback.
     * Any mismatch discards work, joins rollback, and returns null for the normal
     * confirmed path. Once a token is emitted, failure propagates (no replay).
     */
    suspend fun promote(finalIdentity: SpeculativeInputIdentity, endpointConfirmed: Boolean,
                        ordinaryReplyAccepted: Boolean, onToken: (String) -> Unit): GenerationResult? {
        val candidate = synchronized(gate) {
            check(!finalClaimed) { "Speculative final input may be consumed only once" }
            finalClaimed = true
            active?.takeIf { !closed && !it.invalidated && it.generationFailure == null && endpointConfirmed && ordinaryReplyAccepted &&
                ownerIsCurrent() && it.identity == finalIdentity &&
                (!it.completed.isCompleted || it.completed.getCompleted().failure == null) }?.also { it.promoted = true }
        }
        if (candidate == null) {
            invalidate(Invalidation.INPUT_CHANGED)
            awaitIdle()
            return null
        }
        event("speculative_promoted")
        var emitted = false
        try {
            for (token in candidate.tokens) {
                synchronized(gate) {
                    if (active !== candidate || candidate.invalidated || closed || !ownerIsCurrent()) {
                        throw CancellationException("Speculative publication owner changed")
                    }
                    candidate.generationFailure?.let { throw it }
                    candidate.queuedChars -= token.length
                    candidate.queuedBytes -= token.toByteArray(Charsets.UTF_8).size
                    // Linearize publication with invalidation. The caller's normal
                    // memory/call publication fence remains responsible downstream.
                    if (token.isNotEmpty()) { emitted = true; onToken(token) }
                }
            }
            val completion = candidate.completed.await()
            checkHealthy()
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                if (candidate.invalidated || closed || !ownerIsCurrent()) throw CancellationException("Speculative owner changed")
            }
            completion.failure?.let { if (emitted) throw it else return null }
            return completion.result
        } catch (error: Throwable) {
            // Snapshot before our own cancellation can manufacture a worker failure.
            val currentDraftFailure = synchronized(gate) {
                candidate.generationFailure === error && !candidate.invalidated && !closed && ownerIsCurrent()
            }
            invalidate(Invalidation.OWNER_LOST)
            withContext(NonCancellable) { candidate.job.join(); checkHealthy() }
            currentCoroutineContext().ensureActive()
            if (emitted || (error is CancellationException && !currentDraftFailure)) throw error
            return null
        } finally {
            withContext(NonCancellable) { candidate.job.join() }
        }
    }

    /** Joins the exact worker including non-cancellable checked native rollback. */
    suspend fun awaitIdle() {
        val job = synchronized(gate) { active?.job }
        job?.join()
        checkHealthy()
    }

    /** False requires quarantine and retention of the enclosing engine/model lease. */
    suspend fun closeAndDrain(): Boolean = withContext(NonCancellable) {
        synchronized(gate) { closed = true }
        invalidate(Invalidation.STOP)
        val job = synchronized(gate) { active?.job }
        job?.join()
        synchronized(gate) { quarantine == null } && nativeSafeToRelease()
    }

    private fun checkHealthy() {
        synchronized(gate) { quarantine?.let { throw Quarantined(it) } }
        if (!nativeSafeToRelease()) throw Quarantined(null)
    }

    private suspend fun run(candidate: Candidate<I>) {
        var result: GenerationResult? = null
        var failure: Throwable? = null
        val deadline = scope.launch(dispatcher) {
            delay(budget.maxGenerationMs)
            synchronized(gate) {
                // After confirmed promotion this becomes the ordinary streaming
                // answer. Never cut a long confirmed answer at the draft deadline.
                if (!candidate.promoted && !candidate.completed.isCompleted)
                    candidate.job.cancel(CancellationException("speculative_generation_deadline"))
            }
        }
        try {
            run {
                currentCoroutineContext().ensureActive()
                synchronized(gate) { check(active === candidate && !candidate.invalidated && ownerIsCurrent()) }
                result = generate(candidate.input) { token ->
                    synchronized(gate) {
                        if (!candidate.acceptingCallbacks) return@synchronized
                        if (active !== candidate || candidate.invalidated || closed || !ownerIsCurrent())
                            throw CancellationException("Speculative input invalidated")
                        val bytes = token.toByteArray(Charsets.UTF_8).size
                        val newChars = candidate.generatedChars.toLong() + token.length
                        if (newChars > 32_000 || (!candidate.promoted && (newChars > budget.maxHeldChars ||
                            (newChars + 3) / 4 > budget.maxEstimatedTokens)) ||
                            token.length > budget.maxHeldChars - candidate.queuedChars ||
                            bytes > budget.maxHeldUtf8Bytes - candidate.queuedBytes ||
                            !candidate.tokens.trySend(token).isSuccess) throw BudgetExceeded()
                        if (token.isNotEmpty() && !candidate.firstTokenObserved) {
                            candidate.firstTokenObserved = true
                            event("speculative_first_held_token")
                        }
                        candidate.streamDigest.update(token.toByteArray(Charsets.UTF_8))
                        candidate.generatedChars = newChars.toInt()
                        candidate.queuedChars += token.length
                        candidate.queuedBytes += bytes
                    }
                }
                synchronized(gate) { candidate.acceptingCallbacks = false }
                check(result.toolCalls.isEmpty()) { "Speculative generation cannot propose tools" }
                // Backends must stream their complete visible result, so a terminal
                // result cannot smuggle an unbounded/non-streamed response past budgets.
                check(result.text.length == candidate.generatedChars && MessageDigest.isEqual(
                    candidate.streamDigest.digest(), MessageDigest.getInstance("SHA-256")
                        .digest(result.text.toByteArray(Charsets.UTF_8)))) { "Speculative result did not match its stream" }
            }
        } catch (error: Throwable) {
            failure = error
            synchronized(gate) { candidate.generationFailure = error }
        }
        finally {
            deadline.cancel()
            synchronized(gate) { candidate.acceptingCallbacks = false }
            event("speculative_drain_started")
            withContext(NonCancellable) {
                try {
                    rollback()
                    check(nativeSafeToRelease()) { "Native speculative rollback is not safely drained" }
                } catch (error: Throwable) {
                    failure?.takeUnless { it === error }?.let(error::addSuppressed)
                    failure = error
                    synchronized(gate) { quarantine = error; candidate.invalidated = true }
                    candidate.tokens.cancel()
                }
                event("speculative_drain_finished")
                candidate.completed.complete(Completion(result.takeIf { failure == null }, failure))
                candidate.tokens.close()
                synchronized(gate) {
                    if (candidate.invalidated && active === candidate && quarantine == null) active = null
                }
                event("speculative_finished success=${failure == null} quarantined=${quarantine != null}")
            }
        }
    }
}
