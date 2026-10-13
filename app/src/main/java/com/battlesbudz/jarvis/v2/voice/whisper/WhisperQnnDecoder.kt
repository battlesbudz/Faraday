package com.battlesbudz.jarvis.v2.voice.whisper

import java.io.File
import java.util.concurrent.CancellationException

internal object WhisperQnnNative {
    init { System.loadLibrary("sherpa-onnx-jni") }
    external fun create(directory: String, libraryDir: String, dspDir: String): Long
    external fun decode(handle: Long, samples: FloatArray): IntArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

internal class WhisperQnnDecoder(directory: File, private val log: (String) -> Unit) : WhisperDecoder {
    private val tokenizer: WhisperTokenizer
    private val runtime = File(requireNotNull(directory.parentFile?.parentFile), WhisperQnnRuntime.DIRECTORY)
    private val handle: Long
    @Volatile private var closed = false
    @Volatile private var cancelled = false
    @Volatile private var completed = false
    override val backend get() = if (completed) "qnn_htp_v75_base_en_policy" else "qnn_htp_unexecuted"
    init {
        WhisperQnnModelStore.verifyInstalled(directory)
        tokenizer = WhisperTokenizer(File(directory, "multilingual.tiktoken"))
        WhisperQnnRuntime.load(runtime)
        handle = WhisperQnnNative.create(directory.path, runtime.path, runtime.path)
        check(handle != 0L) { "QNN session not created" }
        log("whisper_backend requested=qnn_htp actual=qnn_htp state=sessions_ready cpu_ep_fallback=disabled device_performance=unmeasured")
    }
    override fun decode(samples: FloatArray): String {
        if (closed || cancelled) throw CancellationException("Whisper QNN cancelled")
        val result = try { WhisperQnnNative.decode(handle, samples) }
        catch (error: IllegalStateException) {
            if (cancelled || closed || error.message == "whisper_qnn_cancelled") throw CancellationException("Whisper QNN cancelled")
            throw error
        }
        if (closed || cancelled) throw CancellationException("Whisper QNN cancelled")
        completed = true
        log("whisper_backend requested=qnn_htp actual=qnn_htp state=inference_completed cpu_ep_fallback=disabled generatedTokens=${result.size}")
        return tokenizer.decode(result)
    }
    override fun cancel() { cancelled = true; if (!closed) WhisperQnnNative.cancel(handle) }
    override fun close() { if (!closed) { closed = true; WhisperQnnNative.close(handle) } }
}
