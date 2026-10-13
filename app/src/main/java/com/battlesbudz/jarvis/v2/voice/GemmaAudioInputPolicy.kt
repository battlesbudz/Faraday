package com.battlesbudz.jarvis.v2.voice

internal object GemmaAudioInputPolicy {
    // Leave room below the native 30-second limit for framing; never submit a rolling tail.
    /** One shared switch gates download, main captions and all reply recognition probes. */
    fun usesRecognizer(directAudio: Boolean, captions: Boolean, nativeAudioComparison: Boolean = false): Boolean =
        !nativeAudioComparison && (!directAudio || captions)
    const val PENDING_TRANSCRIPT = "[Audio request; final transcription pending]"
    fun isPendingTranscript(role: String, text: String): Boolean = role == "You" && text == PENDING_TRANSCRIPT
    const val MAX_CAPTURE_MS = 28_000
    const val REQUEST = "Respond to the user's spoken request in the attached audio. The current request is the audio, not a caption. Do not claim you executed a phone action; this experimental mode does not execute phone actions."
    fun retainedAudioIssue(captionIssue: String?, audioEnabled: Boolean, complete: Boolean, bytes: Int): String? =
        captionIssue?.takeIf { it == "gemma_audio_request_exceeds_limit" } ?: rejection(audioEnabled, complete, bytes)
    fun rejection(audioEnabled: Boolean, complete: Boolean, bytes: Int): String? = when {
        !audioEnabled -> "selected_model_has_no_audio_input"
        !complete -> "gemma_audio_request_exceeds_limit"
        bytes <= 44 -> "gemma_audio_empty"
        else -> null
    }
}
