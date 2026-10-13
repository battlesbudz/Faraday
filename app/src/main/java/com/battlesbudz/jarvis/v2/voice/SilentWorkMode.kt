package com.battlesbudz.jarvis.v2.voice

/** Conversation posture while accepted work runs. */
enum class ConversationListenMode { CONVERSATIONAL, SILENT_WORK }

/**
 * Decision for one final captured utterance when silent-work mode is consulted.
 *
 * [Ignored] ordinary speech is dropped while silently working; [Wake] the wake
 * phrase reopens conversation; [Answer] the utterance answers a required
 * question asked during silent work; [Control] stop/cancel controls are always
 * honored, even while silent.
 */
sealed interface SilentSpeechDecision {
    data object Ignored : SilentSpeechDecision
    data object Wake : SilentSpeechDecision
    data object Answer : SilentSpeechDecision
    data object Control : SilentSpeechDecision
}

/**
 * M1d explicit silently-working mode (D21/D22, T05).
 *
 * While silently working, ordinary speech is ignored until the wake phrase
 * ("hey jarvis") reopens conversation. Accepted tasks continue unaffected:
 * this controller never touches task state, it only classifies speech. A
 * required question (for example an approval prompt) may temporarily open an
 * answer window via [requestAnswer]; the answer is classified [SilentSpeechDecision.Answer]
 * and the mode returns to silence afterwards. Stop/cancel controls are always
 * classified [SilentSpeechDecision.Control] so speech-stop and task
 * cancellation keep working in silent mode (D19/D24).
 *
 * JVM-pure: no microphone, TTS, model or Android dependency.
 */
class SilentWorkController(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val WAKE_PHRASE = "hey jarvis"

        /** How long a required question waits for its spoken answer before silence resumes. */
        const val DEFAULT_ANSWER_WINDOW_MS = 30_000L
    }

    private var mode = ConversationListenMode.CONVERSATIONAL
    private var answerWindowUntilMs: Long = 0L
    private var pendingQuestionId: String? = null

    val isSilent: Boolean get() = mode == ConversationListenMode.SILENT_WORK

    /** Puts Jarvis into explicit silent work. Ordinary speech is ignored from now on. */
    fun enterSilentWork(): Boolean {
        if (mode == ConversationListenMode.SILENT_WORK) return false
        mode = ConversationListenMode.SILENT_WORK
        closeAnswerWindow()
        return true
    }

    /**
     * The wake phrase reopens conversation without restarting tasks. Task
     * ownership lives elsewhere; this only restores the listening posture.
     */
    fun exitSilentWork(): Boolean {
        if (mode == ConversationListenMode.CONVERSATIONAL) return false
        mode = ConversationListenMode.CONVERSATIONAL
        closeAnswerWindow()
        return true
    }

    /**
     * A required question asked during silent work (D22): temporarily listen
     * for its answer, then return to silence. Only one window at a time; a new
     * request replaces an expired one but never extends a live one silently.
     */
    fun requestAnswer(questionId: String, windowMs: Long = DEFAULT_ANSWER_WINDOW_MS): Boolean {
        require(questionId.isNotBlank()) { "questionId must not be blank" }
        require(windowMs > 0) { "windowMs must be positive" }
        if (!isSilent) return false
        if (isAnswerWindowOpen()) return false
        pendingQuestionId = questionId
        answerWindowUntilMs = clock() + windowMs
        return true
    }

    /** The awaited answer arrived: close the window and return to silence. */
    fun onAnswerReceived(questionId: String): Boolean {
        if (!isAnswerWindowOpen() || pendingQuestionId != questionId) return false
        closeAnswerWindow()
        return true
    }

    fun isAnswerWindowOpen(): Boolean = isSilent && pendingQuestionId != null && clock() < answerWindowUntilMs

    fun pendingQuestionId(): String? = pendingQuestionId.takeIf { isAnswerWindowOpen() }

    /**
     * Classifies one final utterance. Pure: waking is reported, not performed;
     * the caller (the capture session) exits silent mode on [SilentSpeechDecision.Wake].
     */
    fun classify(text: String): SilentSpeechDecision {
        if (VoiceActionControl.parse(text, hasUnfinished = true) != VoiceActionControl.None) {
            return SilentSpeechDecision.Control
        }
        if (!isSilent) return SilentSpeechDecision.Answer
        if (isWakePhrase(text)) return SilentSpeechDecision.Wake
        if (isAnswerWindowOpen()) return SilentSpeechDecision.Answer
        return SilentSpeechDecision.Ignored
    }

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.trim().lowercase().trimEnd('.', '!', '?', ',')
        return normalized == WAKE_PHRASE || normalized.startsWith("$WAKE_PHRASE ") ||
            normalized.startsWith("$WAKE_PHRASE,")
    }

    private fun closeAnswerWindow() {
        pendingQuestionId = null
        answerWindowUntilMs = 0L
    }
}
