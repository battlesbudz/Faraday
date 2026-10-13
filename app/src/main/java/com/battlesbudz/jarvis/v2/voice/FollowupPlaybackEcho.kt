package com.battlesbudz.jarvis.v2.voice

/** Call-scoped playback evidence survives the speaker-to-command-listener handoff.
 * This is a conservative lexical check, not measured acoustic echo cancellation. */
internal class FollowupPlaybackEcho {
    private val reference = StringBuilder()
    private var endedAtMs: Long? = null

    @Synchronized fun remember(text: String) {
        if (text.isBlank()) return
        reference.append(' ').append(text)
        if (reference.length > 1600) reference.delete(0, reference.length - 1600)
    }
    @Synchronized fun ended(atMs: Long) { endedAtMs = atMs }
    @Synchronized fun clear() { reference.clear(); endedAtMs = null }

    /** Conservative eligibility for omitting display-only final ASR, not echo acceptance. */
    @Synchronized fun needsFinalTranscript(speechStartedAtMs: Long?): Boolean {
        if (reference.isBlank()) return false
        val end = endedAtMs ?: return true
        val onset = speechStartedAtMs ?: return true
        // Unknown, overlapping and the full existing 350ms tail retain lexical verification.
        return onset <= end || onset - end <= 350
    }

    @Synchronized fun rejects(transcript: String, speechStartedAtMs: Long?): Boolean {
        val end = endedAtMs ?: return false
        val onset = speechStartedAtMs ?: return false
        // Only the existing 350ms speaker/reverberation tail is ambiguous. A deliberate
        // later repetition by the user remains a fresh request, even if wording is identical.
        if (onset < end || onset - end > 350) return false
        val clauses = TranscriptContent.speech(transcript).split(Regex("[.!?;\\n]+"))
            .map(PlaybackEchoText::words).filter { it.isNotEmpty() }
        val spoken = PlaybackEchoText.words(reference.toString())
        return clauses.isNotEmpty() && clauses.all { heard ->
            if (heard.size < 3 || heard.size > spoken.size) false
            else (0..spoken.size - heard.size).any { start ->
                // Preserve novel leading/trailing words: "no", "open Settings", etc.
                // The broad active-barge fuzzy matcher is deliberately not used here.
                heard.first() == spoken[start] && heard.last() == spoken[start + heard.lastIndex] &&
                    heard.indices.count { heard[it] != spoken[start + it] } <= heard.size / 3
            }
        }
    }
}
