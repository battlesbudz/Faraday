package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.PreprocessedAudio
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Route B spike (Decision 1, 2026-11-01): does incremental audio prefill on the
 * reviewed bridge produce the same session state as the sealed one-shot
 * submission of the same encoder output? This harness is the ONLY caller of
 * [Session.preprocessAudioEmbeddings]; it is reachable solely from the call
 * screen's development-diagnostics spike button, uses throwaway greedy
 * sessions, and never touches the production voice path — the sealed
 * Conversation route stays exactly as it was.
 *
 * Both arms submit the SAME single encode pass's soft-token rows (the spike
 * slices the sealed capture's rows; it never re-encodes per chunk, because the
 * encoder is stateful across steps and a second pass could legitimately
 * differ). The only difference between the arms is how the rows are grouped
 * into prefill calls and where the begin/end-of-audio markers ride.
 */
internal object RouteBPrefillSpike {
    const val FIXTURE_ASSET = "routeb_spike_utterance.wav"

    /** Same fixed question for both arms, after the production turn opener. */
    const val QUESTION = "Transcribe exactly what was said in the audio."

    /**
     * Chunk size in audio tokens: ~3 s of audio at the encoder's ~25 rows/s,
     * aligned to the encoder's natural step output (6 steps x 12 rows = 72),
     * so no chunk boundary ever splits a step's rows.
     */
    const val CHUNK_ROWS = 72

    private const val TURN_OPENER = "<|turn>user\n"
    private const val GENERATION_BOUNDARY = "<turn|>\n<|turn>model\n"

    data class AudioChunk(
        val startRow: Int,
        val rowCount: Int,
        val includeBeginMarker: Boolean,
        val includeEndMarker: Boolean,
    )

    /** Splits [totalRows] into consecutive chunks; markers ride the first/last chunk only. */
    fun planAudioChunks(totalRows: Int, chunkRows: Int = CHUNK_ROWS): List<AudioChunk> {
        require(totalRows >= 1) { "An utterance must produce at least one audio token" }
        require(chunkRows >= 1) { "Chunk size must be positive" }
        val chunks = mutableListOf<AudioChunk>()
        var start = 0
        while (start < totalRows) {
            val count = minOf(chunkRows, totalRows - start)
            chunks += AudioChunk(
                startRow = start,
                rowCount = count,
                includeBeginMarker = start == 0,
                includeEndMarker = start + count == totalRows,
            )
            start += count
        }
        return chunks
    }

    data class RowDiff(val maxAbsDiff: Double, val meanAbsDiff: Double) {
        val exactlyEqual: Boolean get() = maxAbsDiff == 0.0
    }

    /**
     * Compares the rows the chunked feeder actually submitted (concatenated
     * slices) against the sealed rows. Slicing is pure bookkeeping over one
     * encode pass, so the only honest tolerance is exact equality — any
     * nonzero diff is a feeder bug, not numerical noise.
     */
    fun compareRows(expected: FloatArray, actual: FloatArray): RowDiff {
        require(expected.size == actual.size) {
            "Row sequences differ in length: ${expected.size} vs ${actual.size}"
        }
        var max = 0.0
        var sum = 0.0
        for (i in expected.indices) {
            val diff = abs(expected[i].toDouble() - actual[i].toDouble())
            if (diff > max) max = diff
            sum += diff
        }
        return RowDiff(max, if (expected.isEmpty()) 0.0 else sum / expected.size)
    }

    data class Generation(val answer: String, val ttftMs: Long)

    data class ArmResult(
        val answer: String,
        val prefillMs: Long,
        val ttftMs: Long,
        val totalMs: Long,
        val fedRows: FloatArray,
        val chunkSizes: List<Int>,
    )

    /**
     * Runs one arm on a fresh spike session: the production session turn shape
     * (raw delimiters, Session adds BOS), with the audio submitted either as
     * one payload (sealed shape: both markers on the single chunk) or as the
     * planned chunk sequence. Throws on any native rejection — the caller
     * records that as this arm's outcome.
     */
    suspend fun runArm(
        session: RouteBSpikeSession,
        rows: FloatArray,
        tokenCount: Int,
        embeddingWidth: Int,
        chunked: Boolean,
    ): ArmResult {
        val startedNs = System.nanoTime()
        var prefillNs = 0L
        val textStartedNs = System.nanoTime()
        session.prefillText(TURN_OPENER + QUESTION)
        prefillNs += System.nanoTime() - textStartedNs
        val fed = FloatArray(rows.size)
        val chunkSizes = mutableListOf<Int>()
        if (!chunked) {
            val chunkStartedNs = System.nanoTime()
            session.prefillAudioChunk(rows, tokenCount, embeddingWidth,
                includeBeginMarker = true, includeEndMarker = true)
            prefillNs += System.nanoTime() - chunkStartedNs
            rows.copyInto(fed)
            chunkSizes += tokenCount
        } else {
            for (chunk in planAudioChunks(tokenCount)) {
                val slice = rows.copyOfRange(
                    chunk.startRow * embeddingWidth,
                    (chunk.startRow + chunk.rowCount) * embeddingWidth)
                val chunkStartedNs = System.nanoTime()
                session.prefillAudioChunk(slice, chunk.rowCount, embeddingWidth,
                    chunk.includeBeginMarker, chunk.includeEndMarker)
                prefillNs += System.nanoTime() - chunkStartedNs
                slice.copyInto(fed, chunk.startRow * embeddingWidth)
                chunkSizes += chunk.rowCount
            }
        }
        val generation = session.generate(GENERATION_BOUNDARY)
        return ArmResult(
            answer = generation.answer,
            prefillMs = prefillNs / 1_000_000,
            ttftMs = generation.ttftMs,
            totalMs = (System.nanoTime() - startedNs) / 1_000_000,
            fedRows = fed,
            chunkSizes = chunkSizes,
        )
    }

    /** [runArm] with the failure captured as this arm's outcome instead of thrown. */
    suspend fun runArmCatching(
        session: RouteBSpikeSession,
        rows: FloatArray,
        tokenCount: Int,
        embeddingWidth: Int,
        chunked: Boolean,
    ): Result<ArmResult> = try {
        Result.success(runArm(session, rows, tokenCount, embeddingWidth, chunked))
    } catch (error: Throwable) {
        Result.failure(error)
    }

    /**
     * Builds the paste-ready parity report. PASS requires every chunk prefill
     * accepted (an arm failure is a FAIL with its error), the fed-row
     * reconstruction exactly equal to the sealed rows, and the two greedy
     * answers exactly identical — greedy decoding over identical prefill
     * state should be deterministic, so an answer mismatch is signal about
     * session-state divergence, not noise to tolerate away.
     */
    fun buildReport(
        encodeMs: Long,
        tokenCount: Int,
        embeddingWidth: Int,
        sealed: Result<ArmResult>,
        chunked: Result<ArmResult>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "Route B prefill parity spike (bridge 0.16, feature/video-call)"
        lines += "Fixture: $FIXTURE_ASSET (synthetic tones, 7.5 s, not a human recording)"
        lines += "Sampler: greedy (topK=1, topP=1.0, temperature=0.0, seed=0); sessions are throwaway"
        lines += "Question: \"$QUESTION\""
        lines += "Encode (one pass, production capture): ${encodeMs} ms, tokens=$tokenCount width=$embeddingWidth"
        fun armLine(label: String, arm: Result<ArmResult>): String =
            arm.fold(
                onSuccess = { "$label: prefill=${it.prefillMs} ms ttft=${it.ttftMs} ms total=${it.totalMs} ms chunks=${it.chunkSizes}" },
                onFailure = { "$label: FAILED: ${it.message ?: it.javaClass.simpleName}" },
            )
        lines += armLine("Sealed one-shot", sealed)
        lines += armLine("Chunked incremental", chunked)
        val sealedArm = sealed.getOrNull()
        val chunkedArm = chunked.getOrNull()
        var parity: String? = null
        if (sealedArm != null && chunkedArm != null) {
            val diff = compareRows(sealedArm.fedRows, chunkedArm.fedRows)
            lines += "Rows: fed reconstruction maxAbsDiff=${diff.maxAbsDiff} meanAbsDiff=${diff.meanAbsDiff} (tolerance: exact 0.0 — slicing is bookkeeping)"
            lines += "Answer sealed: \"${sealedArm.answer.trim().take(300)}\""
            lines += "Answer chunked: \"${chunkedArm.answer.trim().take(300)}\""
            val answersEqual = sealedArm.answer.trim() == chunkedArm.answer.trim()
            parity = when {
                !diff.exactlyEqual ->
                    "FAIL — the chunked feeder's rows diverge from the sealed rows (feeder bookkeeping bug)"
                !answersEqual ->
                    "FAIL — rows identical but greedy answers differ (prefill chunking changed session state)"
                else ->
                    "PASS — every chunk prefill accepted, fed rows exactly equal the sealed rows, greedy answers identical"
            }
        }
        lines += "PARITY: " + (parity
            ?: "FAIL — an arm did not complete (see above); the native gate outcome for the failed arm is the result")
        return lines.joinToString("\n")
    }
}

/** The session operations one spike arm needs; faked in JVM tests. */
internal interface RouteBSpikeSession {
    fun prefillText(text: String)
    fun prefillAudioChunk(
        rows: FloatArray,
        tokenCount: Int,
        embeddingWidth: Int,
        includeBeginMarker: Boolean,
        includeEndMarker: Boolean,
    )
    suspend fun generate(boundaryText: String): RouteBPrefillSpike.Generation
}

/**
 * Production seam over a throwaway SDK [Session]. Each chunk payload is
 * preprocessed fresh for its submission — submission consumes the payload
 * natively — and the handle is closed as soon as the submitting call returns,
 * mirroring the snapshot-image discipline.
 */
internal class SdkRouteBSpikeSession(private val session: Session) : RouteBSpikeSession {
    override fun prefillText(text: String) {
        session.runPrefill(listOf(InputData.Text(text)))
    }

    override fun prefillAudioChunk(
        rows: FloatArray,
        tokenCount: Int,
        embeddingWidth: Int,
        includeBeginMarker: Boolean,
        includeEndMarker: Boolean,
    ) {
        val preprocessed: PreprocessedAudio =
            session.preprocessAudioEmbeddings(rows, tokenCount, embeddingWidth,
                includeBeginMarker, includeEndMarker)
        try {
            session.runPrefill(listOf(InputData.PreprocessedAudio(preprocessed)))
        } finally {
            try { preprocessed.close() } catch (_: Throwable) { }
        }
    }

    override suspend fun generate(boundaryText: String): RouteBPrefillSpike.Generation =
        suspendCancellableCoroutine { continuation ->
            val startedNs = System.nanoTime()
            val firstTokenAtNs = AtomicLong(0)
            val answer = StringBuilder()
            continuation.invokeOnCancellation { try { session.cancelProcess() } catch (_: Throwable) { } }
            try {
                session.generateContentStream(listOf(InputData.Text(boundaryText)), object : ResponseCallback {
                    override fun onNext(response: String) {
                        firstTokenAtNs.compareAndSet(0, System.nanoTime())
                        synchronized(answer) { answer.append(response) }
                    }
                    override fun onDone() {
                        if (continuation.isActive) {
                            val ttftMs = if (firstTokenAtNs.get() == 0L) -1
                                else (firstTokenAtNs.get() - startedNs) / 1_000_000
                            continuation.resume(RouteBPrefillSpike.Generation(
                                synchronized(answer) { answer.toString() }, ttftMs))
                        }
                    }
                    override fun onError(throwable: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(throwable)
                    }
                })
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
}
