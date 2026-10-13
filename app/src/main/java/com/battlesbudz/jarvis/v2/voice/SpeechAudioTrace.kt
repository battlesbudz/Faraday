package com.battlesbudz.jarvis.v2.voice

import java.io.File

/** Last 30 seconds of PCM accepted by AudioTrack, trimmed to its final playback head. */
class SpeechAudioTrace(private val destination: File, private val turn: String,
                       private val seconds: Int = 30, private val log: (String) -> Unit = {}) {
    private var rate = 0
    private var samples = ShortArray(0)
    private var written = 0L
    fun append(pcm: ShortArray, offset: Int, count: Int, sampleRate: Int) {
        if (rate == 0) { require(sampleRate > 0 && seconds > 0); rate = sampleRate; samples = ShortArray(rate * seconds) }
        check(rate == sampleRate)
        for (i in offset until offset + count) { samples[(written % samples.size).toInt()] = pcm[i]; written++ }
    }
    fun finish(playedFrames: Long) {
        if (written == 0L) return
        val end = playedFrames.coerceIn(0, written)
        val start = (written - samples.size).coerceAtLeast(0)
        if (end <= start) return
        val pcm = ByteArray((end - start).toInt() * 2)
        for (i in 0 until pcm.size / 2) {
            val value = samples[((start + i) % samples.size).toInt()].toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        val wav = WavEncoder.pcm16Mono(pcm, rate)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeBytes(wav)
        check(temporary.renameTo(destination)) { "Could not replace speech diagnostic" }
        log("speech_audio_trace turn=$turn file=${destination.name} sampleRate=$rate " +
            "startFrame=$start endFrame=$end writtenFrames=$written bytes=${wav.size} " +
            "source=tts_pcm playbackGapsIncluded=false playbackTimeStretchIncluded=false microphoneRecorded=false")
    }
}
