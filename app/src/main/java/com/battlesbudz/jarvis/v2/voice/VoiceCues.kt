package com.battlesbudz.jarvis.v2.voice

import android.media.ToneGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Distinct mode cues, serialized and released on cancellation. Uses the user's media volume. */
object VoiceCues {
    enum class Cue { COMMAND_READY, WAKE_LISTENING }
    private val playback = Mutex()
    suspend fun play(cue: Cue, log: (String) -> Unit = {}) = playback.withLock {
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(CallAudioRouting.stream, 100)
            val type = if (cue == Cue.COMMAND_READY) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
            // ACK: two bright pulses. NACK: a single lower, rounded tone.
            val duration = if (cue == Cue.COMMAND_READY) 320 else 280
            check(tone.startTone(type, duration)) { "Audio cue did not start" }
            delay(duration + 50L)
            log("Voice cue played: $cue gain=100 durationMs=$duration")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log("Voice cue unavailable: $cue ${error.message}") }
        finally { tone?.release() }
    }
}
