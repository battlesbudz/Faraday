package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.PreprocessedAudio
import com.google.ai.edge.litertlm.PreprocessedImage
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session

/**
 * The native session rejected this turn's snapshot image — preprocessing or
 * submission failed, so the image never reached the model. Callers distinguish
 * it from text-prefill failures to keep the turn honest: an image turn whose
 * image was not delivered must not be answered from detector labels alone.
 */
internal class SnapshotImageSubmissionException(cause: Throwable) :
    Exception("Snapshot image submission failed: ${cause.message}", cause)

/**
 * The native session rejected one chunk of this turn's sealed audio —
 * preprocessing or submission failed, so the audio never fully reached the
 * model on the Session route. Callers treat it as a prefill failure: the
 * turn regenerates on the sealed Conversation route with the same rows
 * instead of answering from a partial feed.
 */
internal class AudioPrefillSubmissionException(cause: Throwable) :
    Exception("Audio prefill submission failed: ${cause.message}", cause)

/** SDK boundary. The streaming lifecycle can be tested without loading Android JNI classes. */
internal class LiteRtNativeVoiceSession(
    private val session: Session,
    private val checkWorkerThread: () -> Unit = {},
    private val runCallback: (() -> Unit) -> Unit = { it() },
    private val preprocessImage: (ByteArray) -> PreprocessedImage = { bytes ->
        session.preprocessImage(bytes)
    },
    private val submitPreprocessed: (PreprocessedImage) -> Unit = { image ->
        session.runPrefill(listOf(InputData.Preprocessed(image)))
    },
    private val preprocessAudio: (FloatArray, Int, Int, Boolean, Boolean) -> PreprocessedAudio =
        { rows, tokenCount, embeddingWidth, includeBeginMarker, includeEndMarker ->
            session.preprocessAudioEmbeddings(rows, tokenCount, embeddingWidth,
                includeBeginMarker, includeEndMarker)
        },
    private val submitPreprocessedAudio: (PreprocessedAudio) -> Unit = { audio ->
        session.runPrefill(listOf(InputData.PreprocessedAudio(audio)))
    }
) : VoiceNativeSession {
    override fun runPrefill(input: List<String>) {
        checkWorkerThread()
        session.runPrefill(input.map { InputData.Text(it) })
    }

    /**
     * Snapshot synthesis: one image into the pending turn. The native Session
     * gate admits only preprocessed tensor images, so the raw bytes are first
     * preprocessed through the bridge entry point that mirrors the
     * Conversation route's internal construction (same factory-derived
     * preprocessor for this session's model), then the payload is submitted.
     * Submission consumes the payload natively (the input sequence is
     * move-only), so each call preprocesses fresh bytes and the handle is
     * closed as soon as the submitting call returns. Any failure is thrown as
     * [SnapshotImageSubmissionException]: the image was not delivered.
     */
    override fun runPrefillImage(imageBytes: ByteArray) {
        checkWorkerThread()
        val preprocessed = try {
            preprocessImage(imageBytes)
        } catch (error: Throwable) {
            throw SnapshotImageSubmissionException(error)
        }
        try {
            submitPreprocessed(preprocessed)
        } catch (error: Throwable) {
            throw SnapshotImageSubmissionException(error)
        } finally {
            // The payload was copied by the submission (or the submission just
            // failed and is already reported): a cleanup failure here must
            // never mask the submission outcome or fail a delivered image.
            try { preprocessed.close() } catch (_: Throwable) { }
        }
    }

    /**
     * Route B: one chunk of the turn's sealed audio into the pending turn.
     * The native Session gate admits only preprocessed tensor audio, so the
     * chunk's rows are first preprocessed through the bridge entry point
     * that builds the payload exactly as the Conversation route builds its
     * sealed payload (factory-derived begin marker, validated embeddings
     * tensor, projected end marker), then the payload is submitted.
     * Submission consumes the payload natively (move-only), so each chunk
     * preprocesses fresh rows and the handle is closed as soon as the
     * submitting call returns — the Route B spike's discipline. Any failure
     * is thrown as [AudioPrefillSubmissionException]: the chunk was not
     * delivered, and the caller's fallback regenerates the whole turn on
     * the sealed Conversation route rather than decoding a partial feed.
     */
    override fun runPrefillAudio(rows: FloatArray, tokenCount: Int, embeddingWidth: Int,
                                 includeBeginMarker: Boolean, includeEndMarker: Boolean) {
        checkWorkerThread()
        val preprocessed = try {
            preprocessAudio(rows, tokenCount, embeddingWidth, includeBeginMarker, includeEndMarker)
        } catch (error: Throwable) {
            throw AudioPrefillSubmissionException(error)
        }
        try {
            submitPreprocessedAudio(preprocessed)
        } catch (error: Throwable) {
            throw AudioPrefillSubmissionException(error)
        } finally {
            // The payload was consumed by the submission (or the submission
            // just failed and is already reported): a cleanup failure here
            // must never mask the submission outcome.
            try { preprocessed.close() } catch (_: Throwable) { }
        }
    }

    override fun generateContentStream(input: List<String>, callback: VoiceNativeCallback) {
        checkWorkerThread()
        require(input.isNotEmpty()) { "Streaming generation requires a final input chunk" }
        session.generateContentStream(input.map { InputData.Text(it) }, object : ResponseCallback {
            override fun onNext(response: String) = runCallback { callback.onNext(response) }
            override fun onDone() = runCallback { callback.onDone() }
            override fun onError(throwable: Throwable) = runCallback { callback.onError(throwable) }
        })
    }

    override fun cancelProcess() { checkWorkerThread(); session.cancelProcess() }
    override fun awaitIdle() { checkWorkerThread(); session.awaitIdle() }
    override fun close() { checkWorkerThread(); session.close() }
}
