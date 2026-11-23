package com.battlesbudz.jarvis.v2.voice

/** Select the authoritative user transcript after a post-answer audio caption. */
internal object TranscriptOfRecord {
    fun resolve(
        asrTranscript: String,
        finalCaption: String,
        captionEmittedToolCalls: Boolean,
        disagreementBlocked: Boolean
    ): String? {
        if (disagreementBlocked) return null

        val recognizerText = TranscriptContent.speech(asrTranscript).trim()
        if (VoiceTranscriptResolver.hasTranscript(asrTranscript) &&
            VoiceTranscriptResolver.hasTranscript(recognizerText)) return recognizerText

        val captionText = TranscriptContent.speech(finalCaption).trim()
        if (captionEmittedToolCalls || !VoiceTranscriptResolver.hasTranscript(finalCaption) ||
            !VoiceTranscriptResolver.hasTranscript(captionText) || TranscriptContent.isSoundOnly(finalCaption)) return null
        return captionText
    }
}
