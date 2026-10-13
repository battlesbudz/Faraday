package com.battlesbudz.jarvis.v2.voice

/** A final ASR guess alone cannot turn a brief post-playback sound into a new request. */
internal class FollowupSpeechEvidence {
    var strongMs = 0L
        private set
    private var strongBytes = 0L
    private var liveWords = false
    private var quietCorroborated = false
    fun observe(bytes: Int, probability: Float, partial: String?, corroborated: Boolean, confirmedSpeech: Boolean) {
        if (confirmedSpeech && probability >= 0.5f) {
            strongBytes += bytes
            strongMs = strongBytes / 32
            if (!partial.isNullOrBlank()) liveWords = true
        }
        quietCorroborated = quietCorroborated || corroborated
    }
    // Keep short real commands with live words and the existing stable whisper path.
    // Sustained VAD also permits ASR-empty audio fallback; no phrase blacklist is used.
    fun accepts() = strongMs >= 240 || (strongMs >= 96 && liveWords) || quietCorroborated
    fun reset() { strongMs = 0; strongBytes = 0; liveWords = false; quietCorroborated = false }
    fun diagnostic() = "strongAudioMs=$strongMs liveWords=$liveWords quietCorroborated=$quietCorroborated"
}
