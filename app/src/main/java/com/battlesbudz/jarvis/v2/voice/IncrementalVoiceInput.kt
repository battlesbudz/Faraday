package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One append-only native input track. No answer tokens or tool calls during listening. */
interface VoicePrefillSession : AutoCloseable {
    fun append(text: String)
    suspend fun decode(onToken: (String) -> Unit): GenerationResult
}

class IncrementalVoiceInput(
    scope: CoroutineScope,
    private val prefix: String,
    private val create: () -> VoicePrefillSession,
    private val canPrefill: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val updates = Channel<String>(Channel.CONFLATED)
    private var session: VoicePrefillSession? = null
    private var committed = ""
    private var previous = ""
    private var revised = false
    private var failure: Throwable? = null
    private var sealed = false
    private var consumed = false
    private var closed = false
    private var chunks = 0
    private var prefillMs = 0L
    private var contextPrefillCompletedAtNanos: Long? = null
    private var firstWordPrefillCompletedAtNanos: Long? = null
    private var lastWordPrefillCompletedAtNanos: Long? = null
    private var sealRequestedAtNanos: Long? = null
    private val worker = scope.launch(Dispatchers.Default) {
        for (text in updates) {
            log("input_partial chars=${text.length}")
            if (revised || failure != null || text.isBlank() || text.length > 12_000) continue
            if (!text.startsWith(committed)) {
                revised = true
                log("input_revision committedChars=${committed.length} policy=rebuild_once_at_final")
                continue
            }
            val common = previous.commonPrefixWith(text)
            previous = text
            // ASR can revise its newest word. Commit only complete words shared by
            // consecutive hypotheses; neither speech resumption nor appended words reset KV state.
            val stable = common.substring(0, (common.lastIndexOf(' ') + 1).coerceAtLeast(0))
            if ((stable.length <= committed.length && session != null) || !canPrefill()) continue
            try {
                val began = nowNanos()
                val native = session ?: create().also {
                    session = it
                    it.append(prefix)
                    contextPrefillCompletedAtNanos = nowNanos()
                }
                ensureActive()
                val appendedWords = stable.length > committed.length
                if (appendedWords) {
                    native.append(stable.substring(committed.length))
                    committed = stable
                    chunks++
                    val completedAtNanos = nowNanos()
                    if (firstWordPrefillCompletedAtNanos == null) firstWordPrefillCompletedAtNanos = completedAtNanos
                    lastWordPrefillCompletedAtNanos = completedAtNanos
                }
                prefillMs += (nowNanos() - began).coerceAtLeast(0) / 1_000_000
                val event = if (appendedWords) "input_prefilled" else "input_context_prefilled"
                log("$event chunks=$chunks contextChars=${prefix.length} committedChars=${committed.length} prefillMs=$prefillMs mode=text decodeStarted=false")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = error
                log("input_prefill_failed type=${error.javaClass.simpleName} recovery=final_text")
            }
        }
    }

    fun submit(text: String) {
        if (!sealed && !closed) updates.trySend(text)
    }

    /** Finish queued prefill; never cancel and regenerate just because the user stops talking. */
    suspend fun seal() {
        if (sealed) return
        sealed = true
        sealRequestedAtNanos = nowNanos()
        updates.close()
        worker.join()
    }

    suspend fun answer(finalPrompt: String, onToken: (String) -> Unit): GenerationResult {
        seal()
        check(!closed && !consumed)
        consumed = true
        val retained = prefix + committed
        val reuseReason = when {
            failure != null -> "prefill_failed"
            revised -> "hypothesis_revised"
            session == null -> "no_prefill"
            !finalPrompt.startsWith(prefix) -> "prompt_prefix_changed"
            !finalPrompt.startsWith(retained) -> "final_transcript_revised"
            else -> "retained"
        }
        val reuse = reuseReason == "retained"
        if (!reuse) {
            session?.close(); session = null
            committed = ""
        }
        val native = session ?: create().also { session = it }
        // Persist only completion-minus-seal intervals. A negative offset proves
        // completion before this observed seal request, not before speech end or
        // the accepted endpoint. Queued work can complete after seal was requested.
        // Absolute target-clock stamps stay private; paired fixtures already wrap
        // the real append call for ephemeral common-clock begin/end observations.
        log("input_finalized reuse=$reuse reuseReason=$reuseReason chunks=$chunks retainedChars=${if (reuse) retained.length else 0} " +
            "finalPromptChars=${finalPrompt.length} listeningPrefillMs=$prefillMs audioSubmitted=false " +
            "prefillTimingSchema=seal_relative_v1 " +
            "contextPrefillCompletedOffsetMs=${completionOffsetMs(contextPrefillCompletedAtNanos)} " +
            "firstWordPrefillCompletedOffsetMs=${completionOffsetMs(firstWordPrefillCompletedAtNanos)} " +
            "lastWordPrefillCompletedOffsetMs=${completionOffsetMs(lastWordPrefillCompletedAtNanos)}")
        val remaining = if (reuse) finalPrompt.substring(retained.length) else finalPrompt
        val finalStarted = nowNanos()
        if (remaining.isNotEmpty()) native.append(remaining)
        log("input_final_prefill remainingChars=${remaining.length} finalPrefillMs=${(nowNanos() - finalStarted).coerceAtLeast(0) / 1_000_000}")
        return native.decode(onToken)
    }

    private fun completionOffsetMs(completedAtNanos: Long?): String {
        val sealAtNanos = sealRequestedAtNanos ?: return "unavailable"
        if (completedAtNanos == null) return "unavailable"
        return try {
            // Subtract in nanoseconds before conversion: sub-millisecond work must
            // preserve its sign, and overflow cannot become false overlap proof.
            (Math.subtractExact(completedAtNanos, sealAtNanos) / 1_000_000.0).toString()
        } catch (_: ArithmeticException) { "unavailable" }
    }

    /** Recovery is permitted only before user-visible output; never swallow cancellation. */
    suspend fun answerWithTextFallback(
        finalPrompt: String,
        onToken: (String) -> Unit,
        retry: suspend (Exception) -> GenerationResult
    ): GenerationResult {
        var outputStarted = false
        try {
            return answer(finalPrompt) { token ->
                if (token.isNotBlank()) outputStarted = true
                onToken(token)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            close()
            if (outputStarted) throw error
            return retry(error)
        } finally { close() }
    }

    suspend fun close() = withContext(NonCancellable) {
        if (!closed) {
            closed = true
            updates.close()
            worker.cancelAndJoin() // Blocking native calls finish before the session can be freed.
            session?.close(); session = null
        }
    }
}
