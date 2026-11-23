package com.battlesbudz.jarvis.v2.voice

/**
 * The answer context of any later turn must contain no caption-submission output, so the caption
 * always runs between conversation resets regardless of how clean the resident conversation looks.
 * This invariant follows the 2026-11-22 device evidence: caption output joined resident history and
 * later delta submissions inherited its commentary style.
 */
internal object FinalCaptionSessionPolicy {
    enum class Session { ISOLATED }

    fun choose(residentConversationClean: Boolean): Session = Session.ISOLATED
}
