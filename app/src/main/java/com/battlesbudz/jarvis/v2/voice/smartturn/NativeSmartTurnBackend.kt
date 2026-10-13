package com.battlesbudz.jarvis.v2.voice.smartturn

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit local file only; never downloads, persists audio, or acquires another model owner. */
internal class NativeSmartTurnBackend(model: File) : SmartTurnBackend {
    private val handle: Long
    init {
        require(model.isFile && model.length() == MODEL_BYTES) { "Smart Turn model size mismatch" }
        // Bounded immutable bytes bind hash validation and ORT parsing, avoiding path replacement races.
        val bytes = model.inputStream().use { input ->
            val buffer = ByteArray(MODEL_BYTES.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                require(read > 0) { "Truncated Smart Turn model" }
                offset += read
            }
            require(input.read() == -1) { "Oversized Smart Turn model" }
            buffer
        }
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == MODEL_SHA256) {
            "Smart Turn model identity mismatch"
        }
        System.loadLibrary("sherpa-onnx-jni")
        handle = SmartTurnNative.create(bytes)
        check(handle != 0L) { "Smart Turn initialization failed" }
    }

    override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
        SmartTurnNative.prepare(handle, requestId)
        if (cancelled.get()) SmartTurnNative.cancel(handle, requestId)
        val result = SmartTurnNative.infer(handle, requestId, samples)
        check(result.size == 3) { "Smart Turn result ABI mismatch" }
        return SmartTurnInference(result[0].toFloat(), result[1].toLong(), result[2].toLong())
    }

    override fun cancel(requestId: Long) { SmartTurnNative.cancel(handle, requestId) }
    override fun close() { SmartTurnNative.close(handle) }

    companion object {
        const val MODEL_BYTES = 8_679_182L
        const val MODEL_SHA256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
    }
}

/** JNI methods are housed in the existing Sherpa library, using its exact pinned ORT. */
internal object SmartTurnNative {
    external fun create(model: ByteArray): Long
    external fun prepare(handle: Long, requestId: Long)
    external fun infer(handle: Long, requestId: Long, samples: FloatArray): DoubleArray
    external fun cancel(handle: Long, requestId: Long)
    external fun close(handle: Long)
}
