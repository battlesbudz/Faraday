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
    // Where the transcript is the request, admission requires evidence of
    // words: live partial words over at least 96 ms of confirmed strong
    // audio, or the existing stable corroborated whisper path. Sustained
    // strong audio alone no longer admits there: on build 1669, 704 ms of
    // wordless, uncorroborated sound became a follow-up turn, burned the ASR
    // recovery loop and ended in a spoken clarify to an empty room. A
    // wordless, uncorroborated candidate dies silently at the endpoint
    // instead; no phrase blacklist is used.
    fun accepts() = (strongMs >= 96 && liveWords) || quietCorroborated
    // Where the retained audio itself is the request (captionOnly captures),
    // Gemma is the recognizer: there are no live words to require when no
    // caption transcriber runs, and a caption's ASR issue is suppressed
    // downstream when the retained audio is complete
    // (GemmaAudioInputPolicy.retainedAudioIssue), so the ASR recovery /
    // clarify chain never sees these turns. Sustained strong audio keeps
    // its historical admission on that path.
    fun acceptsAudioOnly() = strongMs >= 240 || (strongMs >= 96 && liveWords) || quietCorroborated
    // Whether any word evidence was observed while this candidate was
    // captured: live partial words, or the corroborated stable-whisper
    // path. A guarded follow-up admitted with neither rested on sustained
    // strong audio alone, so nothing has yet confirmed that a person
    // actually said words; recognition must confirm real words by
    // transcription before such a turn may be answered.
    fun hasWordEvidence() = liveWords || quietCorroborated
    fun reset() { strongMs = 0; strongBytes = 0; liveWords = false; quietCorroborated = false }
    fun diagnostic() = "strongAudioMs=$strongMs liveWords=$liveWords quietCorroborated=$quietCorroborated"
}
