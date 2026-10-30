package com.battlesbudz.jarvis.v2.conversation

/**
 * Detects a generated claim that the current turn's audio is inaccessible.
 *
 * Every generation and regeneration route for an audio turn hands the engine
 * the audio itself (see [ConversationInput.retry] and the recovery stages in
 * [ConversationRecovery], which resubmit the retained bytes), so a draft that
 * answers as if no audio existed is a model-behavior failure, never a
 * routing one: Gemma sometimes refuses an audio turn in the words of a
 * text-only assistant ("I do not have access to the audio content") even
 * though the audio was attached to that very submission. Left alone, the
 * claim is false, it teaches the user the feature is broken, and it has
 * recurred across builds because no stage checked for it. The guards in
 * [ConversationGeneration] and [ConversationRecovery] use this detector to
 * regenerate the turn once with [CORRECTION] before the draft can be
 * answered, and to substitute [FALLBACK] when the model refuses again.
 *
 * The patterns deliberately require an access denial, not mere difficulty:
 * an honest "I couldn't make out the words" is a legitimate answer on a
 * noisy capture and must never trip the guard.
 */
internal object AudioAccessRefusal {
    /**
     * Honest replacement when the corrective regeneration still refuses: it
     * admits the words were not understood and asks for a repeat, in the
     * same words the voice pipeline already uses for a recognition failure.
     * It never claims the audio was inaccessible.
     */
    const val FALLBACK = "I couldn't make out that request, sir. Please say it again."

    /**
     * Appended to the submitted prompt for the one corrective regeneration.
     * Names the false claim directly: the model is told the audio is
     * attached to this message, because on the failed draft it answered as
     * if the prompt's "attached audio" framing were hypothetical.
     */
    const val CORRECTION = "\nThe previous draft wrongly claimed that the audio is not accessible. " +
        "The user's audio IS attached to this message and you can hear it. " +
        "Listen to the attached audio and answer what the user actually said. " +
        "Do not claim that you cannot access, hear or process the audio, and do not call a tool."

    // "I cannot respond to the audio", "unable to process the recording".
    // The negative lookahead keeps honest statements about audio settings,
    // volume or devices ("I can't access the audio settings") from matching.
    private val directDenial = Regex(
        "(?i)\\b(cannot|can't|can not|unable to|not able to)\\s+" +
            "(respond to|answer|process|access)\\s+the\\s+(audio|recording)" +
            "(?!\\s+(settings|volume|output|device|file|track))")

    // "do not have access to ...", "no access to ...", "I require the audio file".
    private val accessDenial = Regex(
        "(?i)\\b(do not|don't|does not|doesn't|no|without|never)\\s+(have\\s+)?(any\\s+)?access\\s+to\\b" +
            "|\\b(require|requires|need|needs)\\s+the\\s+audio\\s+file\\b")

    // An access denial counts as an audio-access refusal only when the text
    // is actually about the audio ("access to its content" follows a mention
    // of the audio in both observed specimens).
    private val audioAnchor = Regex("(?i)\\b(audio|recording|voice message|spoken)\\b")

    fun isRefusal(text: String): Boolean {
        val display = text.trim()
        if (display.isEmpty()) return false
        if (directDenial.containsMatchIn(display)) return true
        return accessDenial.containsMatchIn(display) && audioAnchor.containsMatchIn(display)
    }
}
