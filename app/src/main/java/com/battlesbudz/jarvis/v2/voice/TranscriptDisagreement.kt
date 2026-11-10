package com.battlesbudz.jarvis.v2.voice

/**
 * The transcript-disagreement hard block (Battles, 2026-11-09: "that needs
 * to be a hard block"). On build 1771 (call 83164c01) a barge turn's two
 * recognizers invented unrelated words on the same audio — Whisper heard
 * "and it does not matter." while Gemma's final caption said "प्रस्थान मेरा
 * है" — and the pipeline committed the caption as the user's words, where
 * it entered dialogue history and recent-request evidence and later turns
 * planned from it. Neither text was his. When the two transcripts share
 * no words at all AND their dominant scripts differ, the turn has no
 * trustworthy transcript: neither text may be committed as the user's
 * words, recorded as request evidence, or captured to memory.
 *
 * Deliberately narrow. Any shared word is agreement enough to stay out of
 * the way; a same-script disagreement, however total, is ordinary ASR
 * noise and not this block's business; and a turn where only one
 * recognizer produced words has nothing to disagree with (that is the
 * follow-up word-confirmation gate's territory, not this one's).
 */
internal object TranscriptDisagreement {
    fun hardBlock(whisper: String, gemma: String): Boolean {
        val whisperWords = words(whisper)
        val gemmaWords = words(gemma)
        if (whisperWords.isEmpty() || gemmaWords.isEmpty()) return false
        if (whisperWords.any { it in gemmaWords }) return false
        val whisperScript = dominantScript(whisper) ?: return false
        val gemmaScript = dominantScript(gemma) ?: return false
        return whisperScript != gemmaScript
    }

    /** Word tokens of the speech content; sound captions never count as words. */
    private fun words(text: String): Set<String> =
        TranscriptContent.speech(text).lowercase()
            .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .filter { it.isNotEmpty() }
            .toSet()

    /** The script most of the text's letters belong to; digits and punctuation carry no script. */
    private fun dominantScript(text: String): Character.UnicodeScript? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (Character.isLetter(codePoint)) {
                val script = Character.UnicodeScript.of(codePoint)
                if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) {
                    counts[script] = (counts[script] ?: 0) + 1
                }
            }
            offset += Character.charCount(codePoint)
        }
        return counts.maxByOrNull { it.value }?.key
    }
}
