package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import com.battlesbudz.jarvis.v2.voice.whisper.WhisperQnnModelStore
import java.io.File

enum class AsrEngine(val id: String, val label: String, val modelVersion: String) {
    WHISPER("whisper_base_en", "Whisper base.en", "base.en-int8 / sherpa-1.13.7"),
    WHISPER_NPU("whisper_base_qnn", "Whisper base · Snapdragon NPU", "base-fp16-sm8650 / qnn-2.6.0-qairt-2.50 / CPU-fallback-base.en");
    fun create(directory: File, live: Boolean = true, log: (String) -> Unit = {}, modelSession: VoiceModelSession? = null,
               audioEvidence: RecognitionAudioEvidence? = null): StreamingTranscriber =
        WhisperTranscriber(directory, live, log, modelSession, audioEvidence, preferNpu = this == WHISPER_NPU)
    fun createDiagnostic(directory: File, log: (String) -> Unit = {}, audioEvidence: RecognitionAudioEvidence? = null): StreamingTranscriber =
        create(directory, live = false, log = log, audioEvidence = audioEvidence)
    fun createProbe(directory: File, log: (String) -> Unit, modelSession: VoiceModelSession?): StreamingTranscriber =
        WhisperTranscriber(directory, live = false, log = log, modelSession = modelSession, warmProbe = true, preferNpu = this == WHISPER_NPU)
    suspend fun prepare(context: Context, status: (String) -> Unit): File = when (this) {
        WHISPER -> RecognitionModelStore(context).whisper(status)
        WHISPER_NPU -> WhisperQnnModelStore(context).prepare(status)
    }
    companion object {
        fun selected(context: Context): AsrEngine {
            val preferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
            val stored = preferences.getString("engine", null)
            val engine = entries.firstOrNull { it.id == stored } ?: WHISPER_NPU
            if (stored == "moonshine_small") preferences.edit().putString("engine", engine.id).apply()
            return engine
        }
        fun select(context: Context, engine: AsrEngine) { context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putString("engine", engine.id).apply() }
    }
}
