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
    // Admission requires evidence of words: live partial words over at least
    // 96 ms of confirmed strong audio, or the existing stable corroborated
    // whisper path. Sustained strong audio alone no longer admits: on build
    // 1669, 704 ms of wordless, uncorroborated sound became a follow-up turn,
    // burned the ASR recovery loop and ended in a spoken clarify to an empty
    // room. A wordless, uncorroborated candidate dies silently at the
    // endpoint instead; no phrase blacklist is used.
    fun accepts() = (strongMs >= 96 && liveWords) || quietCorroborated
    fun reset() { strongMs = 0; strongBytes = 0; liveWords = false; quietCorroborated = false }
    fun diagnostic() = "strongAudioMs=$strongMs liveWords=$liveWords quietCorroborated=$quietCorroborated"
}
