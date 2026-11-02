package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route B (Option B) owner for one native-audio turn: primes the turn's
 * text prefix on a [VoicePrefillSession] during capture, then at
 * finalization chunk-feeds the sealed audio rows into that same session
 * and decodes there — hiding the text prefill under the user's speech.
 * Only the text prefill is hidden; the audio rows exist only at the seal,
 * so their prefill still happens at finalization (hiding that too is
 * Option A's provisional-rows bridge work).
 *
 * Arming is decided by RouteBPrimingPolicy before this owner is created:
 * only turn shapes whose Session-assembled prompt provably equals the
 * Conversation-assembled one are primed. Every failure here degrades to
 * the sealed Conversation route before any user-visible output — the
 * session is closed first, because the checked native lifecycle admits
 * one child at a time and the sealed route needs the Conversation. A
 * failure after output has started is not recoverable (mirroring
 * [IncrementalVoiceInput.answerWithTextFallback]) and propagates.
 */
internal class PrimedAudioInput(
    private val scope: CoroutineScope,
    private val prefix: String,
    private val create: () -> VoicePrefillSession,
    private val canPrime: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime
) {
    private var session: VoicePrefillSession? = null
    private var priming: Job? = null
    private var primed = false
    private var failure: Throwable? = null
    private var consumed = false
    private var closed = false
    private var prefillMs = 0L

    /** True when the Session route produced the turn's answer. */
    var usedPriming: Boolean = false
        private set

    /** Why the sealed Conversation route produced the answer instead, when it did. */
    var fallbackReason: String? = null
        private set

    /**
     * Starts priming on the owner's worker: allocate the session and append
     * the prefix while capture is still running. Idempotent; a priming
     * failure is recorded, never thrown — finalization will fall back.
     */
    fun prime() {
        if (priming != null || closed) return
        priming = scope.launch(Dispatchers.Default) {
            if (!canPrime()) {
                log("route_b_priming_skipped reason=scheduler_or_state")
                return@launch
            }
            try {
                val began = nowNanos()
                val native = create()
                session = native
                native.append(prefix)
                prefillMs = (nowNanos() - began).coerceAtLeast(0) / 1_000_000
                primed = true
                log("route_b_primed prefixChars=${prefix.length} prefillMs=$prefillMs")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                log("route_b_priming_failed type=${error.javaClass.simpleName} recovery=sealed_conversation")
            }
        }
    }

    /**
     * Finalizes the turn: if the primed session provably holds this exact
     * turn (the final prompt still starts with the primed prefix), append
     * the remainder, chunk-feed the sealed rows and decode on the session.
     * Otherwise — and on any pre-output failure — close the session and run
     * [sealedFallback], the ordinary sealed Conversation submission.
     */
    suspend fun answerWithSealedFallback(
        finalPrompt: String,
        sealed: Content.SealedAudioEmbeddings,
        onToken: (String) -> Unit,
        sealedFallback: suspend () -> GenerationResult
    ): GenerationResult {
        check(!consumed && !closed) { "Primed audio input already consumed or closed" }
        consumed = true
        try {
            priming?.join()
            val reuseReason = when {
                failure != null -> "priming_failed"
                session == null || !primed -> "not_primed"
                !finalPrompt.startsWith(prefix) -> "prompt_prefix_changed"
                else -> "retained"
            }
            if (reuseReason != "retained") {
                fallbackReason = reuseReason
                log("route_b_finalized reuse=false reason=$reuseReason policy=sealed_conversation")
                closeSession()
                return sealedFallback()
            }
            val native = checkNotNull(session)
            log("route_b_finalized reuse=true audioTokens=${sealed.audioTokenCount} " +
                "listeningPrefillMs=$prefillMs")
            var outputStarted = false
            try {
                val remainder = finalPrompt.substring(prefix.length)
                if (remainder.isNotEmpty()) native.append(remainder)
                native.appendAudio(sealed.projectedRows(), sealed.audioTokenCount, sealed.embeddingWidth)
                val result = native.decode { token ->
                    if (token.isNotBlank()) outputStarted = true
                    onToken(token)
                }
                usedPriming = true
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (outputStarted) throw error
                fallbackReason = "session_generation_failed"
                log("route_b_session_failed type=${error.javaClass.simpleName} recovery=sealed_conversation")
                closeSession()
                return sealedFallback()
            }
        } finally {
            close()
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /** Recovery is permitted only before user-visible output; never swallow cancellation. */
    suspend fun close() = withContext(NonCancellable) {
        if (!closed) {
            closed = true
            priming?.cancelAndJoin()
            closeSession()
        }
    }
}
