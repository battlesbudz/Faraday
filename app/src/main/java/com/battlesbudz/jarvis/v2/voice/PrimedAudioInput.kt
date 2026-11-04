package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.RouteBPrefillSpike
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route B (Option B) owner for one native-audio turn: primes the turn's
 * text prefix on a [VoicePrefillSession] during capture, then at
 * finalization chunk-feeds the sealed audio rows into that same session
 * and decodes there — hiding the text prefill under the user's speech.
 *
 * Option A extends this owner: while capture runs, the encoder's
 * provisional snapshots (rows of steps already completed, final by the
 * bridge's contract) are offered through [offerProvisionalRows] and fed
 * into the primed session in spike-shaped 72-row chunks, hiding most of
 * the audio prefill too. The sealed route submits the turn's full text
 * BEFORE any audio, and an append-only session cannot move already-fed
 * rows behind the finalization remainder — so a session that holds
 * provisional rows may only answer when the final prompt is exactly the
 * primed prefix, and only after every fed row verifies exactly against
 * the sealed rows. Any other shape (extended prompt, row mismatch, feed
 * failure, broken snapshot sequence) discards the session at finalization
 * and the sealed route answers, exactly as Option B's fallback behaves.
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

    // Option A provisional feeding. Offers arrive on the encoder worker
    // thread; the lock guards only the sequence bookkeeping, and the
    // channel hand-off is non-blocking, so an offer never stalls capture.
    private val provisionalLock = Any()
    private val provisionalOffers = Channel<FloatArray>(Channel.UNLIMITED)
    private var provisionalIntakeOpen = true
    private var provisionalWidth = -1
    private var provisionalExpectedRow = 0
    private var provisionalPoison: String? = null
    private var feeder: Job? = null
    /** Fed chunk copies, in feed order, retained for the seal verification. */
    private val fedProvisionalChunks = mutableListOf<FloatArray>()
    private var provisionalFeedFailure: Throwable? = null

    /** True when the Session route produced the turn's answer. */
    var usedPriming: Boolean = false
        private set

    /** Why the sealed Conversation route produced the answer instead, when it did. */
    var fallbackReason: String? = null
        private set

    /** Option A: provisional rows accepted from the encoder during capture. */
    var provisionalRowsOffered: Int = 0
        private set

    /** Option A: provisional rows actually prefilled into the session during capture. */
    var provisionalRowsFed: Int = 0
        private set

    /** Option A: maxAbsDiff of the fed rows against the sealed rows, when verification ran. */
    var provisionalSealMaxAbsDiff: Double? = null
        private set

    /**
     * Starts priming on the owner's worker: allocate the session and append
     * the prefix while capture is still running. Idempotent; a priming
     * failure is recorded, never thrown — finalization will fall back.
     */
    fun prime() {
        if (priming != null || closed) return
        priming = scope.launch(Dispatchers.Default) {
            try {
                if (!canPrime()) {
                    log("route_b_priming_skipped reason=scheduler_or_state")
                    return@launch
                }
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
        startFeeder()
    }

    /**
     * Option A intake: one provisional snapshot from the turn's encoder,
     * offered on the encoder worker thread while capture runs. Never blocks
     * and never throws: the snapshot is sequence-checked (first row must
     * continue the accepted chain exactly — a restart means the encoder
     * candidate changed under this turn) and copied into the feeder queue.
     * A rejected snapshot poisons only the provisional stream; the sealed
     * route is unaffected and finalization decides the session's fate.
     */
    fun offerProvisionalRows(snapshot: RetainedPcmEncoderWorker.ProvisionalRows) {
        var rejected: String? = null
        synchronized(provisionalLock) {
            if (closed || consumed || !provisionalIntakeOpen || priming == null) return
            if (provisionalPoison != null) return
            val rows = snapshot.totalRows - snapshot.firstRow
            val poison = when {
                rows <= 0 || snapshot.values.isEmpty() -> "provisional_snapshot_empty"
                snapshot.firstRow != provisionalExpectedRow -> "provisional_rows_out_of_sequence"
                provisionalWidth > 0 && snapshot.values.size != rows * provisionalWidth ->
                    "provisional_width_changed"
                provisionalWidth < 0 && snapshot.values.size % rows != 0 ->
                    "provisional_width_indivisible"
                else -> null
            }
            if (poison != null) {
                provisionalPoison = poison
                rejected = poison
            } else {
                if (provisionalWidth < 0) provisionalWidth = snapshot.values.size / rows
                provisionalExpectedRow = snapshot.totalRows
                provisionalRowsOffered = snapshot.totalRows
            }
        }
        if (rejected != null) {
            log("route_b_provisional_rejected reason=$rejected firstRow=${snapshot.firstRow}")
            return
        }
        provisionalOffers.trySend(snapshot.values.copyOf())
    }

    /**
     * Feeds accepted provisional rows into the primed session as they
     * accumulate, in the spike-proven 72-row chunk shape. The last
     * chunk-sized block is always held back: the finalization tail must
     * carry the projected end marker on a real chunk, so a chunk is fed
     * only while more than one chunk's worth of rows is buffered.
     */
    private fun startFeeder() {
        if (feeder != null) return
        feeder = scope.launch(Dispatchers.Default) {
            priming?.join()
            val native = session
            if (native == null || !primed || failure != null) {
                // Nothing to feed: drain so offers never accumulate.
                for (ignored in provisionalOffers) { }
                return@launch
            }
            var pending = FloatArray(0)
            var pendingRows = 0
            for (values in provisionalOffers) {
                val width = synchronized(provisionalLock) { provisionalWidth }
                if (width <= 0) continue // Unreachable: an accepted offer sets the width first.
                val merged = FloatArray(pending.size + values.size)
                pending.copyInto(merged)
                values.copyInto(merged, pending.size)
                pending = merged
                pendingRows += values.size / width
                while (pendingRows > RouteBPrefillSpike.CHUNK_ROWS &&
                    provisionalFeedFailure == null &&
                    synchronized(provisionalLock) { provisionalPoison } == null) {
                    val chunkRows = RouteBPrefillSpike.CHUNK_ROWS
                    val slice = pending.copyOfRange(0, chunkRows * width)
                    try {
                        native.appendAudioChunk(slice, chunkRows, width,
                            includeBeginMarker = provisionalRowsFed == 0, includeEndMarker = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        provisionalFeedFailure = error
                        log("route_b_provisional_feed_failed type=${error.javaClass.simpleName} " +
                            "fedRows=$provisionalRowsFed recovery=sealed_conversation")
                        break
                    }
                    fedProvisionalChunks += slice
                    provisionalRowsFed += chunkRows
                    log("route_b_provisional_fed chunkRows=$chunkRows totalFed=$provisionalRowsFed")
                    pending = pending.copyOfRange(chunkRows * width, pending.size)
                    pendingRows -= chunkRows
                }
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
            // Option A: stop provisional intake and drain the feeder before
            // touching the session — afterwards the fed-row count is final
            // and no append can race finalization.
            synchronized(provisionalLock) { provisionalIntakeOpen = false }
            provisionalOffers.close()
            feeder?.join()
            val reuseReason = when {
                failure != null -> "priming_failed"
                session == null || !primed -> "not_primed"
                !finalPrompt.startsWith(prefix) -> "prompt_prefix_changed"
                provisionalFeedFailure != null -> "provisional_feed_failed"
                provisionalPoison != null && provisionalRowsFed > 0 -> provisionalPoison ?: "provisional_rejected"
                // The sealed route places the whole final text before any
                // audio; rows already fed cannot move behind an extended
                // prompt, so this session can no longer mirror the turn.
                provisionalRowsFed > 0 && finalPrompt.length > prefix.length -> "prompt_extended_after_audio"
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
                "listeningPrefillMs=$prefillMs provisionalFed=$provisionalRowsFed")
            var outputStarted = false
            try {
                val remainder = finalPrompt.substring(prefix.length)
                if (remainder.isNotEmpty()) native.append(remainder)
                if (provisionalRowsFed > 0) {
                    // Verify every provisionally fed row against the sealed
                    // rows for the same steps before this session may answer:
                    // exact equality is the only honest tolerance, since both
                    // come from the one encode pass.
                    val width = sealed.embeddingWidth
                    val sealedRows = sealed.projectedRows()
                    val fedValues = FloatArray(provisionalRowsFed * width)
                    var at = 0
                    for (chunk in fedProvisionalChunks) {
                        if (at + chunk.size > fedValues.size) { at = -1; break }
                        chunk.copyInto(fedValues, at)
                        at += chunk.size
                    }
                    val diff = if (at == fedValues.size && provisionalRowsFed <= sealed.audioTokenCount)
                        RouteBPrefillSpike.compareRows(
                            sealedRows.copyOf(provisionalRowsFed * width), fedValues) else null
                    provisionalSealMaxAbsDiff = diff?.maxAbsDiff
                    log("route_b_seal_verification fedRows=$provisionalRowsFed " +
                        "sealedRows=${sealed.audioTokenCount} maxAbsDiff=${diff?.maxAbsDiff} " +
                        "equal=${diff?.exactlyEqual == true}")
                    if (diff == null || !diff.exactlyEqual) {
                        fallbackReason = "provisional_rows_mismatch"
                        log("route_b_finalized reuse=false reason=provisional_rows_mismatch " +
                            "policy=sealed_conversation")
                        closeSession()
                        return sealedFallback()
                    }
                    feedAudioTail(native, sealedRows, provisionalRowsFed, sealed.audioTokenCount, width)
                } else {
                    native.appendAudio(sealed.projectedRows(), sealed.audioTokenCount, sealed.embeddingWidth)
                }
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

    /**
     * Feeds the sealed rows past the provisionally fed prefix, in
     * spike-shaped chunks: the begin marker already rode the first
     * provisional chunk, and the end marker rides this tail's last chunk.
     * The feeder's holdback guarantees a non-empty tail whenever rows were
     * fed, so the payload always completes inside this call.
     */
    private fun feedAudioTail(native: VoicePrefillSession, sealedRows: FloatArray,
                              fromRow: Int, totalRows: Int, width: Int) {
        check(fromRow in 1 until totalRows) { "Provisional tail must be a proper remainder" }
        var start = fromRow
        while (start < totalRows) {
            val count = minOf(RouteBPrefillSpike.CHUNK_ROWS, totalRows - start)
            native.appendAudioChunk(
                sealedRows.copyOfRange(start * width, (start + count) * width),
                count, width, includeBeginMarker = false,
                includeEndMarker = start + count == totalRows)
            start += count
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
            synchronized(provisionalLock) { provisionalIntakeOpen = false }
            provisionalOffers.close()
            priming?.cancelAndJoin()
            feeder?.cancelAndJoin()
            closeSession()
        }
    }
}
