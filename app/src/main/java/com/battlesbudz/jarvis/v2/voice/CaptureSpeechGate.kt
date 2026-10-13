package com.battlesbudz.jarvis.v2.voice

/** Reject weak VAD at the recent room floor, without imposing an absolute whisper cutoff. */
/** A microphone-session estimate, shared only by ordinary listening consumers. */
class CaptureNoiseProfile {
    @Volatile var floorRms: Double = 0.0
        internal set
}

internal class CaptureSpeechGate(private val profile: CaptureNoiseProfile? = null) {
    private data class Noise(val atMs: Long, val rms: Double)
    private val noise = ArrayDeque<Noise>()
    var noiseFloorRms = profile?.floorRms ?: 0.0
        private set

    fun accept(decision: SpeechDecision, rms: Double, atMs: Long): SpeechDecision {
        // Age by captured audio time, even when no new low-VAD frames arrive.
        // Old observations cannot outweigh a new quieter room observation.
        while (noise.isNotEmpty() && atMs - noise.first().atMs > 3000) noise.removeFirst()
        // Learn only from confidently non-speech frames. Speech must not raise its own floor.
        if (decision.probability < 0.15f) {
            noise.addLast(Noise(atMs, rms))
            if (noise.size > 30) noise.removeFirst()
        }
        val calibrated = noise.size >= 3 && noise.last().atMs - noise.first().atMs >= 200
        if (calibrated) noiseFloorRms = noise.map { it.rms }.sorted()[(noise.size - 1) / 5]
        // Speech does not erase a calibrated room floor. Otherwise VAD's acoustic
        // tail at the unchanged room level becomes a new utterance after 3 seconds.
        // A quieter confident noise observation can lower an old floor immediately.
        if (noiseFloorRms > 0 && decision.probability < .15f) noiseFloorRms = minOf(noiseFloorRms, rms)
        profile?.floorRms = noiseFloorRms
        val ratio = if (decision.probability >= .8f) 1.1 else 1.8
        val nearFloor = noiseFloorRms > 0 && rms <= noiseFloorRms.coerceAtLeast(1.0) * ratio
        return if (rms == 0.0 || nearFloor) SpeechDecision(false, 0f) else decision
    }
}
