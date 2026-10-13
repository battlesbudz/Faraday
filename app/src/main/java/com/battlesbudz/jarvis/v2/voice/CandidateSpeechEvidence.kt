package com.battlesbudz.jarvis.v2.voice

/** Evidence belongs to the audio submitted to a probe, never to its waiting time. */
internal class CandidateSpeechEvidence {
    data class Snapshot(val admittedMs: Long, val strongMs: Long)
    private var admittedBytes = 0L
    private var strongBytes = 0L
    fun observe(bytes: Int, decision: SpeechDecision) {
        if (decision.isSpeech) {
            admittedBytes += decision.speechSamples?.times(2) ?: bytes
            strongBytes += decision.strongSpeechSamples?.times(2) ?: if (decision.probability >= .8f) bytes else 0
        }
    }
    fun snapshot() = Snapshot(admittedBytes / 32, strongBytes / 32)
    fun reset() { admittedBytes = 0; strongBytes = 0 }
}

/** Extra ASR work only for acoustically uncertain candidates; all words remain eligible. */
internal class CandidateConfirmation {
    private var previousWords = emptyList<String>()
    private var previousAudioAt = Long.MIN_VALUE
    private var previousBytes = 0L
    var reason = "no_words"
        private set

    fun observe(text: String, audioAt: Long, audioBytes: Long,
                speech: CandidateSpeechEvidence.Snapshot): Boolean {
        val words = PlaybackEchoText.words(text)
        if (words.isEmpty()) { reset(); return false }
        // Use the same sustained-speech duration as final follow-up admission.
        if (speech.strongMs >= 240) { reason = "sustained_speech"; return true }
        val agrees = previousWords.isNotEmpty() &&
            (words == previousWords || (words.size > previousWords.size && words.take(previousWords.size) == previousWords))
        val fresh = previousAudioAt != Long.MIN_VALUE && audioAt > previousAudioAt && audioBytes >= previousBytes + 250 * 32
        val accepted = speech.admittedMs >= 96 && agrees && fresh
        if (audioAt > previousAudioAt) {
            previousWords = words; previousAudioAt = audioAt; previousBytes = audioBytes
        }
        reason = if (accepted) "fresh_audio_agreement" else "fresh_audio_required"
        return accepted
    }
    fun reset() { previousWords = emptyList(); previousAudioAt = Long.MIN_VALUE; previousBytes = 0; reason = "no_words" }
}
