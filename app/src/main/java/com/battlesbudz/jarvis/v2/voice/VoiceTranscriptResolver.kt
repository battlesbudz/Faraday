package com.battlesbudz.jarvis.v2.voice

/** Resolve recognition without turning failed transcription into a user message. */
object VoiceTranscriptResolver {
    const val UNTRANSCRIBED = "[Voice message — transcription unavailable]"
    val instructions = """
        Transcribe only the words spoken in the attached audio. Do not answer the
        request or call tools. Do not infer words from prior conversation.
        Return only the transcript without commentary. If no intelligible words
        are audible, return exactly [NO_SPEECH].
    """.trimIndent()

    suspend fun resolve(asr: String, wav: ByteArray, hearAudio: suspend (ByteArray) -> String): String {
        if (asr.isNotBlank()) return asr.trim()
        val heard = hearAudio(wav).trim()
        // A positive no-speech result is not an invitation to invent a reply.
        // Runtime recognizes this caption and keeps listening without answer generation.
        if (heard.equals("[NO_SPEECH]", ignoreCase = true)) return "[NO_SPEECH]"
        return heard.takeUnless { it == UNTRANSCRIBED } ?: ""
    }

    /** Retry only missing output, never an explicit no-speech result. Caller owns the overall deadline. */
    internal suspend fun retryEmptyAudio(attempt: suspend (Int) -> String): String {
        repeat(2) { index ->
            val text = attempt(index + 1).trim()
            if (text.isNotEmpty() && text != UNTRANSCRIBED) return text
        }
        return ""
    }

    internal fun hasTranscript(text: String): Boolean = text != UNTRANSCRIBED &&
        TranscriptContent.speech(text).any { it.isLetterOrDigit() }
}
