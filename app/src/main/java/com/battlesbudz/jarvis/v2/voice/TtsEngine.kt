package com.battlesbudz.jarvis.v2.voice

/** Keep precision, voice and model identity visible in every comparison. */
enum class TtsEngine(
    val id: String, val label: String, val directory: String, val modelFile: String,
    val speaker: Int, val archiveBytes: Long, val archiveSha256: String
) {
    PIPER_NORTHERN("piper_northern_english_male_medium", "Piper — Northern English Male",
        "vits-piper-en_GB-northern_english_male-medium", "en_GB-northern_english_male-medium.onnx", 0,
        67210490, "2bb2c1e709f58c11f17c693b3b38f500e110e7f54f2651774ec48b8d41f12c55");

    val modelBytes: Long get() = 63201430L
    val version: String get() = "$directory / speaker=$speaker / sherpa-1.13.7"
    val archiveUrl: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$directory.tar.bz2"
    companion object {
        // Historical diagnostics retain retired IDs rather than being relabeled as the fallback.
        fun diagnosticLabel(id: String) = entries.firstOrNull { it.id == id }?.label
            ?: when (id) {
                "kokoro" -> "Kokoro original (retired)"
                "pocket_paul" -> "Pocket TTS — Paul (retired)"
                "piper_miro_high" -> "Piper Miro High (British) (retired)"
                "kokoro_int8" -> "Kokoro INT8 (retired)"
                "piper" -> "Piper Lessac (US) (retired)"
                "piper_alan" -> "Piper Alan (British) (retired)"
                "piper_ryan_high" -> "Piper Ryan High (US) (retired)"
                else -> id.ifBlank { "Unknown voice" }
            }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: PIPER_NORTHERN
    }
}
