package com.battlesbudz.jarvis.v2.voice.whisper

import java.util.concurrent.CancellationException

/** Call-owned backend; utterance owner serializes decode and joins before close. */
internal interface WhisperDecoder : AutoCloseable {
    val backend: String
    val lastDecodeBackend: String get() = backend
    fun decode(samples: FloatArray): String
    fun cancel() {}
}

/** Explicit independent CPU failover, never a mixed CPU graph reported as NPU. */
internal class FallbackWhisperDecoder(
    primary: () -> WhisperDecoder,
    private val cpu: () -> WhisperDecoder,
    private val log: (String) -> Unit,
) : WhisperDecoder {
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var fellBack = false
    @Volatile private var decoder: WhisperDecoder = try { primary() } catch (error: Exception) {
        if (error is CancellationException) throw error
        fellBack = true; log("whisper_backend requested=qnn_htp actual=sherpa_cpu reason=qnn_setup_unavailable")
        cpu()
    } catch (_: LinkageError) {
        fellBack = true; log("whisper_backend requested=qnn_htp actual=sherpa_cpu reason=qnn_linkage_unavailable")
        cpu()
    }
    override val backend get() = decoder.backend
    @Volatile override var lastDecodeBackend: String = "not_executed"
        private set
    override fun decode(samples: FloatArray): String {
        checkActive()
        return try {
            decoder.decode(samples).also { checkActive(); lastDecodeBackend = decoder.lastDecodeBackend }
        } catch (error: Exception) {
            checkActive()
            if (error is CancellationException || fellBack) throw error
            decoder.close(); fellBack = true
            decoder = cpu()
            log("whisper_backend requested=qnn_htp actual=sherpa_cpu reason=qnn_inference_failed")
            checkActive()
            decoder.decode(samples).also { checkActive(); lastDecodeBackend = "qnn_failed_attempt_then_sherpa_cpu" }
        }
    }
    private fun checkActive() { if (cancelled || closed) throw CancellationException("Whisper decode cancelled") }
    override fun cancel() { cancelled = true; decoder.cancel() }
    override fun close() { if (!closed) { closed = true; decoder.close() } }
}
