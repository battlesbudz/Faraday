package com.battlesbudz.jarvis.v2.voice

import kotlin.math.sqrt

data class VoicePlaybackFrame(val caption: String = "", val level: Float = 0f)

/** Captions use the playback clock; streaming text spans are independent of tiny PCM blocks. */
class SpokenCaptionTimeline {
    private data class Envelope(val start: Long, val frames: Int, val rate: Int, val levels: List<Float>)
    private data class Words(val start: Long, var frames: Long, val rate: Int,
        val words: List<String>, val group: Int?, var complete: Boolean, var visible: Int = 0)
    private val envelopes = ArrayDeque<Envelope>()
    private val spans = ArrayDeque<Words>()
    private val completedFrames = mutableMapOf<Int, Long>()
    private var previous = emptyList<String>()

    @Synchronized fun append(start: Long, rate: Int, pcm: ShortArray, text: String, group: Int? = null) {
        val block = (rate / 20).coerceAtLeast(1)
        val levels = (pcm.indices step block).map { offset ->
            val end = (offset + block).coerceAtMost(pcm.size)
            var squares = 0.0
            for (i in offset until end) squares += pcm[i].toDouble() * pcm[i]
            (sqrt(squares / (end - offset)) / 6000).toFloat().coerceIn(0f, 1f)
        }
        envelopes.addLast(Envelope(start, pcm.size, rate, levels))
        if (text.isNotBlank()) {
            val words = text.trim().split(Regex("\\s+"))
            val completed = group?.let { completedFrames.remove(it) }
            // Until native returns the utterance length, use a speech-rate estimate,
            // never the duration of its first 240 ms callback for the entire sentence.
            val estimated = if (group == null) pcm.size.toLong() else
                maxOf(pcm.size.toLong(), words.sumOf { it.length.coerceAtLeast(3) } * rate / 18L)
            spans.addLast(Words(start, completed ?: estimated, rate, words, group,
                complete = group == null || completed != null))
        } else if (group != null) {
            spans.lastOrNull { it.group == group }?.let {
                it.frames = maxOf(it.frames, start + pcm.size - it.start)
            }
        }
    }

    /** May precede the IO writer's first append; preserve that completion until it arrives. */
    @Synchronized fun complete(group: Int, frames: Long) {
        val span = spans.lastOrNull { it.group == group }
        if (span == null) completedFrames[group] = frames
        else { span.frames = frames; span.complete = true }
    }

    @Synchronized fun at(head: Long): VoicePlaybackFrame {
        if (head <= 0) return VoicePlaybackFrame()
        while (envelopes.isNotEmpty() && head >= envelopes.first().start + envelopes.first().frames)
            envelopes.removeFirst()
        val envelope = envelopes.firstOrNull()?.takeIf { head >= it.start }
        val level = envelope?.let {
            it.levels.getOrElse(((head - it.start) / (it.rate / 20).coerceAtLeast(1)).toInt()) { 0f }
        } ?: 0f
        while (spans.isNotEmpty() && spans.first().complete && head >= spans.first().start + spans.first().frames)
            previous = (previous + spans.removeFirst().words).takeLast(32)
        val span = spans.firstOrNull()?.takeIf { head >= it.start }
            ?: return VoicePlaybackFrame(previous.joinToString(" "), level)
        val fraction = ((head - span.start + span.rate * 0.18) / span.frames.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val total = span.words.sumOf { it.length.coerceAtLeast(3) }
        var weight = 0
        val visible = span.words.takeWhile { word ->
            val show = weight <= fraction * total
            weight += word.length.coerceAtLeast(3)
            show
        }.size
        span.visible = maxOf(span.visible, visible) // Refining the duration must not retract words.
        return VoicePlaybackFrame((previous + span.words.take(span.visible)).takeLast(32).joinToString(" "), level)
    }
}
