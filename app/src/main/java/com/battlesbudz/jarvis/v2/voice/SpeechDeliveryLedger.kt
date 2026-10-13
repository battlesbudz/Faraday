package com.battlesbudz.jarvis.v2.voice

/** AudioTrack frame delivery, not acoustic audibility or word-level alignment. Fillers are excluded. */
enum class SpeechDeliveryState { PENDING, PLAYING, COMPLETED, INTERRUPTED, FAILED }
data class DeliveredSpeechSpan(val index: Int, val text: String, val startFrame: Long,
    val endFrame: Long, val sampleRate: Int, val sealed: Boolean = false)
data class SpeechDelivery(val turnId: String, val state: SpeechDeliveryState = SpeechDeliveryState.PENDING,
    val playedFrames: Long = 0, val spans: List<DeliveredSpeechSpan> = emptyList(), val revision: Long = 0) {
    val terminal get() = state in setOf(SpeechDeliveryState.COMPLETED, SpeechDeliveryState.INTERRUPTED, SpeechDeliveryState.FAILED)
    val deliveredText get() = spans.filter { it.sealed && it.endFrame > it.startFrame && it.endFrame <= playedFrames }
        .joinToString(" ") { it.text }.trim()
    val partialSpanIndex get() = spans.firstOrNull {
        playedFrames > it.startFrame && (!it.sealed || playedFrames < it.endFrame)
    }?.index
}

/** Producer registers complete text spans before enqueue; playback advances only from the actual head. */
class SpeechDeliveryLedger(private val turnId: String, private val onChange: (SpeechDelivery) -> Unit = {}) {
    private val spans = mutableListOf<DeliveredSpeechSpan>()
    private var frames = 0L
    private var last = SpeechDelivery(turnId)
    private var notifiedKey: Triple<String, Int?, SpeechDeliveryState>? = null
    @Synchronized fun append(index: Int, text: String, frameCount: Int, sampleRate: Int) {
        if (last.terminal) return
        require(frameCount > 0 && sampleRate > 0)
        val previous = spans.lastOrNull()
        if (previous?.index == index) {
            check(!previous.sealed && previous.sampleRate == sampleRate)
            spans[spans.lastIndex] = previous.copy(endFrame = frames + frameCount)
        } else {
            check(previous == null || (previous.sealed && index > previous.index && previous.sampleRate == sampleRate))
            spans += DeliveredSpeechSpan(index, text, frames, frames + frameCount, sampleRate)
        }
        frames += frameCount
    }
    @Synchronized fun seal(index: Int) {
        if (last.terminal) return
        check(spans.lastOrNull()?.index == index)
        spans[spans.lastIndex] = spans.last().copy(sealed = true)
    }
    @Synchronized fun advance(head: Long, terminalState: SpeechDeliveryState? = null): SpeechDelivery {
        if (last.terminal) return last
        require(terminalState == null || terminalState in setOf(SpeechDeliveryState.COMPLETED, SpeechDeliveryState.INTERRUPTED, SpeechDeliveryState.FAILED))
        val played = head.coerceIn(last.playedFrames, frames)
        val state = terminalState ?: if (played > 0) SpeechDeliveryState.PLAYING else SpeechDeliveryState.PENDING
        last = SpeechDelivery(turnId, state, played, spans.toList(), last.revision + 1)
        val key = Triple(last.deliveredText, last.partialSpanIndex, state)
        // Persist at span/state boundaries, not on every 40 ms playback tick.
        if (key != notifiedKey) { notifiedKey = key; onChange(last) }
        return last
    }
}
