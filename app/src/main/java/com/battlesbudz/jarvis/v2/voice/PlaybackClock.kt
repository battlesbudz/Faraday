package com.battlesbudz.jarvis.v2.voice

/** Playback deadlines count runnable time, excluding external microphone interruptions. */
internal class PlaybackClock(private val source: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pausedAt: Long? = null
    private var excluded = 0L
    @Synchronized fun setPaused(paused: Boolean) {
        if (paused && pausedAt == null) pausedAt = source()
        if (!paused) pausedAt?.let { excluded += source() - it; pausedAt = null }
    }
    @Synchronized fun nowMs(): Long = (pausedAt ?: source()) - excluded
}
