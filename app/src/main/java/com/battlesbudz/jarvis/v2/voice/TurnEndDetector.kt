package com.battlesbudz.jarvis.v2.voice

/** Turn completion stays separate from ASR segment finalization and call inactivity. */
interface TurnEndDetector {
    fun update(text: String, nowMs: Long)
    fun decision(nowMs: Long): AdaptiveTurnEnd.Decision
    fun reset()
}
