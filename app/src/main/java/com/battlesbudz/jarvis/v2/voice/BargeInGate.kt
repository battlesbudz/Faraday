package com.battlesbudz.jarvis.v2.voice

/** Confirm new user words without pausing the speaker to test for echo. */
class BargeInGate(private val stableMs: Long = 300, private val allowShortEchoOverlap: Boolean = false) {
    enum class Action { WAIT, CONFIRM }
    private var candidate = ""
    private var candidateAt = 0L
    private var speechAt: Long? = null
    private var lastTranscript = ""
    private var lastReference = ""
    private var cachedRequest = emptyList<String>()
    var echoMatchedWords = 0
        private set
    var examinedFragments: List<String> = emptyList()
        private set
    var selectedRequest = ""
        private set
    var requestText = ""
        private set
    var reason = "no_words"
        private set
    var confirmed = false
        private set

    fun update(speech: Boolean, playing: Boolean, nowMs: Long,
               transcript: String = "", spokenText: String = ""): Action {
        if (confirmed) return Action.CONFIRM
        if (speech) speechAt = nowMs
        // Playback may underrun while its echo is still in ASR's rolling transcript.
        // Keep the reference in both phases and inspect clauses, not just the last words.
        val echoWords = words(spokenText)
        if (transcript != lastTranscript || spokenText != lastReference) {
            cachedRequest = requestWords(transcript, echoWords)
            lastTranscript = transcript
            lastReference = spokenText
        }
        val heard = cachedRequest
        // Interruption is a floor handoff, not authorization of a command.
        // A single non-echo word is enough; the complete request is captured after stopping.
        if (heard.isEmpty() || speechAt?.let { nowMs - it > 1000 } != false) {
            reason = when {
                heard.isEmpty() -> if (transcript.isBlank()) "no_words" else "no_new_speech_in_mixed_transcript"
                else -> "no_recent_speech"
            }
            candidate = ""; return Action.WAIT
        }
        val key = heard.joinToString(" ")
        reason = "words_settling"
        if (key != candidate) {
            val extendsCandidate = candidate.isNotEmpty() && key.startsWith("$candidate ")
            candidate = key
            if (!extendsCandidate) { candidateAt = nowMs; if (stableMs > 0) return Action.WAIT }
        }
        if (nowMs - candidateAt < stableMs) return Action.WAIT
        reason = "confirmed_new_request"
        requestText = key
        confirmed = true
        return Action.CONFIRM
    }

    private fun requestWords(transcript: String, echo: List<String>): List<String> {
        echoMatchedWords = 0
        examinedFragments = emptyList()
        selectedRequest = ""
        for (clause in TranscriptContent.speech(transcript).takeLast(1200).split(Regex("[.!?;\\n]+"))) {
            val tokens = words(clause)
            if (PlaybackEchoText.resemblesPlayback(tokens, echo)) {
                echoMatchedWords += tokens.size
                if (examinedFragments.size < 4) examinedFragments += "near_echo:${tokens.joinToString(" ").take(160)}"
                continue
            }
            val matched = BooleanArray(tokens.size)
            // Remove contiguous echoed phrases, not a bag of common English words.
            for (i in tokens.indices) for (j in echo.indices) {
                var length = 0
                while (i + length < tokens.size && j + length < echo.size &&
                    tokens[i + length] == echo[j + length]) length++
                if (length >= 3) for (k in i until i + length) matched[k] = true
            }
            echoMatchedWords += matched.count { it }
            var i = 0
            while (i < tokens.size) {
                if (matched[i]) { i++; continue }
                val start = i
                while (i < tokens.size && !matched[i]) i++
                val run = tokens.subList(start, i)
                val novel = run.filter { it !in echo }
                // Short literal echo and isolated ASR substitutions inside an echoed
                // sentence are still playback, not a user taking the floor.
                val internalSubstitution = start > 0 && i < tokens.size && run.size == 1
                val nearEcho = run.size >= 3 && novel.size * 2 < run.size
                val shortOwnerCandidate = allowShortEchoOverlap && run.size in 1..2
                val userSpeech = run.isNotEmpty() && (novel.isNotEmpty() || shortOwnerCandidate) && !internalSubstitution && !nearEcho
                if (examinedFragments.size < 4) examinedFragments = examinedFragments +
                    "${if (userSpeech) "speech" else "echo"}:${run.joinToString(" ").take(160)}"
                if (userSpeech) {
                    selectedRequest = run.joinToString(" ")
                    return run
                }
            }
        }
        return emptyList()
    }

    private fun words(text: String) = RecognizedInterruptionWords.words(text)
}
