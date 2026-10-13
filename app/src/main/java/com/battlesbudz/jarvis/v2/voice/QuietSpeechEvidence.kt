package com.battlesbudz.jarvis.v2.voice

/** Weak VAD needs corroborating, stable words; low volume alone never confirms speech. */
class QuietSpeechEvidence {
    private var previous = ""
    private var changedAt = 0L
    private var evidenceSince: Long? = null
    fun accept(text: String?, probability: Float, nowMs: Long, alreadySpeaking: Boolean): Boolean {
        // A deferred decode is not a new empty hypothesis. Keep the last words so
        // the same cached result cannot become fresh again after a silent/queued frame.
        if (text.isNullOrBlank() || probability < 0.15f) {
            evidenceSince = null
            return false
        }
        val normalized = Regex("[\\p{L}\\p{N}']+").findAll(text.lowercase(java.util.Locale.ROOT))
            .joinToString(" ") { it.value }
        if (normalized != previous) { previous = normalized; changedAt = nowMs }
        // Above-threshold VAD must pass acoustic frame confirmation, not use ASR
        // as a shortcut around it. Still remember these words for later weak frames.
        if (probability >= 0.5f) { evidenceSince = null; return false }
        if (normalized.split(Regex("\\s+")).size < 2) { evidenceSince = null; return false }
        val since = evidenceSince ?: nowMs.also { evidenceSince = it }
        if (!alreadySpeaking && nowMs - since < 300) return false
        val age = (nowMs - changedAt).coerceAtLeast(0)
        return if (alreadySpeaking) age in 150..500 else age in 150..1000
    }
    fun reset() { previous = ""; changedAt = 0; evidenceSince = null }
}
