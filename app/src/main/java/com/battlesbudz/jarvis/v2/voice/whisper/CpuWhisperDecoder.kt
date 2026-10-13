package com.battlesbudz.jarvis.v2.voice.whisper

import com.k2fsa.sherpa.onnx.*
import java.io.File

internal class CpuWhisperDecoder(directory: File) : WhisperDecoder {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(
            encoder = File(directory, "base.en-encoder.int8.onnx").path,
            decoder = File(directory, "base.en-decoder.int8.onnx").path),
            tokens = File(directory, "base.en-tokens.txt").path, modelType = "whisper", numThreads = 2)))
    override val backend = "sherpa_cpu_base_en"
    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() = recognizer.release()
}
