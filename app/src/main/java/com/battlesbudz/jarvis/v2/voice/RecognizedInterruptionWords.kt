package com.battlesbudz.jarvis.v2.voice

/** Lexical evidence from ASR, excluding its explicit non-speech annotations. */
internal object RecognizedInterruptionWords {
    private val noiseLabels = setOf("rustle", "rustling", "cloth rustle", "handling noise", "microphone noise")

    fun text(transcript: String): String = TranscriptContent.speech(transcript)
        .replace(Regex("[\\[(](?:${noiseLabels.joinToString("|") { Regex.escape(it) }})[\\])]", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun words(transcript: String): List<String> = Regex("[\\p{L}\\p{N}']+")
        .findAll(text(transcript).lowercase(java.util.Locale.ROOT))
        .map { it.value.trim('\'') }.filter { it.any(Char::isLetterOrDigit) }.toList()

    /** A keyword detector is never lexical authorization to interrupt. */
    fun confirmsKeyword(keyword: String, transcript: String, spokenReference: String): Boolean {
        val heard = words(transcript)
        val playback = words(spokenReference)
        if (heard.isEmpty() || PlaybackEchoText.resemblesPlayback(heard, playback) ||
            (heard.size <= 2 && playback.windowed(heard.size, 1).any { it == heard })) return false
        return when (keyword.lowercase(java.util.Locale.ROOT)) {
            "stop" -> !words(spokenReference).contains("stop") && "stop" in heard &&
                (VoiceStopRequest.matches(text(transcript)) || VoiceCallPolicy.isGoodbye(text(transcript)))
            else -> heard.windowed(2, 1, partialWindows = true).any { it.joinToString("_") == keyword.lowercase(java.util.Locale.ROOT) }
        }
    }
}
