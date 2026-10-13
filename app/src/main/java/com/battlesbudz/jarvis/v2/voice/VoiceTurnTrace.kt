package com.battlesbudz.jarvis.v2.voice

/** One bounded monotonic timeline per turn. Missing events remain unknown, never zero. */
class VoiceTurnTrace(val turnId: String, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Stage {
        TURN_STARTED, MICROPHONE_READY, RECOGNITION_FINALIZED, CAPTURE_RELEASED, CAPTURE_CONSUMER_RELEASED,
        PREPARATION_SEALED, AUDIO_FALLBACK_STARTED, AUDIO_FALLBACK_FINISHED,
        REPLY_DISPATCHED, FIRST_REPLY_TEXT, FIRST_REPLY_AUDIO,
        INTERRUPTION_CONFIRMED, PLAYBACK_STOP_REQUESTED, TURN_FINISHED
    }
    private val startedAt = nowMs()
    private val stages = linkedMapOf(Stage.TURN_STARTED to 0L)
    private var finished = false

    @Synchronized fun mark(stage: Stage) {
        if (finished) return
        stages.putIfAbsent(stage, (nowMs() - startedAt).coerceAtLeast(0))
        if (stage == Stage.TURN_FINISHED) finished = true
    }

    @Synchronized fun snapshot(): Map<String, Long> = stages.mapKeys { it.key.name.lowercase(java.util.Locale.ROOT) }

    @Synchronized fun duration(start: Stage, end: Stage): Long? {
        val from = stages[start] ?: return null
        val to = stages[end] ?: return null
        return (to - from).takeIf { it >= 0 }
    }
}
