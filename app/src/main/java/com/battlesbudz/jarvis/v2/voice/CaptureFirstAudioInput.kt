package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One exact-turn raw reader, independently of either logical consumer's native cleanup.
 * Playback transfer is a lock-only phase change. It never joins a decoder or stops the
 * retained recorder. PCM is copied on admission and acknowledged only on logical
 * consumption; overflow fails the whole owner.
 */
internal class CaptureFirstAudioInput(
    private val source: AudioInput,
    private val scope: CoroutineScope,
    private val observe: (String) -> Unit = {},
    private val onRawFrame: (Long) -> Unit = {},
    private val onTransfer: (Long?, Long?) -> Unit = { _, _ -> },
) {
    private data class Frame(val sequence: Long, val atMs: Long, val pcm: ByteArray)
    private val lock = Any()
    private val lifecycle = Mutex()
    private val history = ArrayDeque<Frame>()
    private var historyBytes = 0
    private var lastEvictedAtMs: Long? = null
    private var lastSequence: Long? = null
    private var producer: Job? = null
    private var closed = false
    private var failure: Throwable? = null
    private var followup: Reader? = null
    private var candidateFirstSequence: Long? = null
    var transferredThroughSequence: Long? = null
        private set
    private val reply = Reader(false)
    private var current = reply
    private val maxBytes = source.sampleRateHz * source.channelCount * 2 * 6
    val replyInput: AudioInput get() = reply

    suspend fun start() = lifecycle.withLock {
        check(!closed)
        if (producer != null) return@withLock
        source.deferConsumptionAcknowledgement()
        source.start()
        producer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.chunks().collect { bytes ->
                    val sequence = requireNotNull(source.lastChunkSequence) { "capture_handoff_sequence_unavailable" }
                    val at = requireNotNull(source.lastChunkCaptureTimeMs) { "capture_handoff_timestamp_unavailable" }
                    synchronized(lock) {
                        check(!closed)
                        check(lastSequence?.let { sequence == it + 1 } != false) { "capture_handoff_sequence_gap" }
                        val frame = Frame(sequence, at, bytes.copyOf())
                        onRawFrame(sequence)
                        current.offer(frame)
                        if (followup == null) {
                            history.addLast(frame); historyBytes += bytes.size
                            while (historyBytes > maxBytes && history.isNotEmpty()) {
                                val evicted = history.removeFirst()
                                historyBytes -= evicted.pcm.size
                                lastEvictedAtMs = evicted.atMs
                            }
                        }
                        lastSequence = sequence
                    }
                }
                error("capture_handoff_source_ended")
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (!closed) fail(cancelled) }
                throw cancelled
            } catch (error: Throwable) {
                synchronized(lock) { fail(error) }
            }
        }
    }

    /** Pin the existing reply guard's entire candidate, including its original onset buffer. */
    fun retainCandidate(lastSequence: Long?, retainedBytes: Int) = synchronized(lock) {
        if (followup != null || closed) return@synchronized
        if (lastSequence == null || retainedBytes <= 0) throw AudioBacklogException()
        var remaining = retainedBytes.toLong()
        var first: Long? = null
        val frames = history.filter { it.sequence <= lastSequence }
        if (frames.lastOrNull()?.sequence != lastSequence) throw AudioBacklogException()
        for (frame in frames.asReversed()) {
            remaining -= frame.pcm.size
            first = frame.sequence
            if (remaining <= 0) break
        }
        if (remaining > 0) throw AudioBacklogException()
        candidateFirstSequence = first
    }

    fun clearCandidate() = synchronized(lock) {
        if (followup == null) candidateFirstSequence = null
    }

    /** Seeds exactly the retained onset prefix, then the same producer appends the live tail. */
    fun beginFollowup(playbackEndedAtMs: Long? = null, retainOverlap: Boolean = true): AudioInput = synchronized(lock) {
        check(!closed); failure?.let { throw it }
        check(followup == null) { "capture_handoff_already_transferred" }
        val next = Reader(true)
        // Without lexical echo verification, use the ordinary post-playback replay
        // boundary. Capture still builds its unchanged 1200ms pre-roll for later speech.
        val boundary = (playbackEndedAtMs ?: history.lastOrNull()?.atMs ?: 0L) - if (retainOverlap) 1200L else 0L
        if (lastEvictedAtMs?.let { it >= boundary } == true) throw AudioBacklogException()
        val required = candidateFirstSequence.takeIf { retainOverlap }
        if (required != null && history.firstOrNull()?.sequence?.let { it > required } != false) throw AudioBacklogException()
        val prefix = history.filter { it.atMs >= boundary || required?.let { first -> it.sequence >= first } == true }
        onTransfer(prefix.firstOrNull()?.sequence, lastSequence)
        prefix.forEach(next::offer)
        transferredThroughSequence = lastSequence
        followup = next
        current = next
        reply.retire()
        observe("capture_first_raw_ready prefixBytes=${prefix.sumOf { it.pcm.size }} firstSequence=${prefix.firstOrNull()?.sequence} lastSequence=$lastSequence")
        history.clear(); historyBytes = 0
        next
    }

    private fun fail(error: Throwable) {
        if (failure == null) failure = error
        reply.channel.close(error)
        followup?.channel?.close(error)
    }

    suspend fun close() = withContext(NonCancellable) {
        lifecycle.withLock {
            val stop = synchronized(lock) {
                if (closed) false else {
                    closed = true
                    reply.retire(); followup?.retire()
                    history.clear(); historyBytes = 0
                    true
                }
            }
            if (stop) {
                // Release the retained-session reader before joining our producer. Native
                // logical readers are joined by the structured reply owner, not this adapter.
                try { source.stop() } finally { producer?.cancelAndJoin() }
            }
        }
    }

    private inner class Reader(private val ordinary: Boolean) : AudioInput {
        val channel = Channel<Frame>(Channel.UNLIMITED) // Byte bound below, never a frame-count assumption.
        private var queuedBytes = 0
        private var retired = false
        private var collecting = false
        private var started = false
        private var deferredAcknowledgement = false
        override val sampleRateHz get() = source.sampleRateHz
        override val channelCount get() = source.channelCount
        override val priorAudioForKeywords get() = source.priorAudioForKeywords
        override val captureNoiseProfile get() = source.captureNoiseProfile.takeIf { ordinary }
        @Volatile override var lastChunkSequence: Long? = null
            private set
        @Volatile override var lastChunkCaptureTimeMs: Long? = null
            private set
        override val bufferedAudioMs get() = synchronized(lock) { queuedBytes.toLong() * 1000 / (sampleRateHz * 2) }
        override val stoppedUnconsumedPcmBytes get() = synchronized(lock) { queuedBytes.toLong() }
        // Raw admission is NOT downstream consumption. Unseen prefetched tail remains
        // unacknowledged in VoiceAudioSession for the next exact turn.
        override fun deferConsumptionAcknowledgement() = synchronized(lock) {
            check(!collecting && !retired)
            deferredAcknowledgement = true
        }
        override fun acknowledgeConsumed(sequence: Long) = synchronized(lock) {
            if (!retired && current === this && deferredAcknowledgement) {
                check(sequence <= (lastChunkSequence ?: 0L))
                source.acknowledgeConsumed(sequence)
            }
        }
        fun offer(frame: Frame) {
            check(!retired)
            if (queuedBytes.toLong() + frame.pcm.size > maxBytes) throw AudioBacklogException()
            check(channel.trySend(frame).isSuccess)
            queuedBytes += frame.pcm.size
        }
        fun retire() {
            if (!retired) { retired = true; channel.cancel(); queuedBytes = 0 }
        }
        override suspend fun start() = synchronized(lock) {
            check(!closed && !retired)
            check(producer != null) { "capture_handoff_source_not_started" }
            failure?.let { throw it }
            started = true
        }
        override fun chunks() = flow {
            synchronized(lock) { check(started && !retired && !collecting); collecting = true }
            try {
                for (frame in channel) {
                    synchronized(lock) {
                        failure?.let { throw it }
                        check(!retired)
                        queuedBytes -= frame.pcm.size
                        lastChunkSequence = frame.sequence
                        lastChunkCaptureTimeMs = frame.atMs
                        if (!deferredAcknowledgement) source.acknowledgeConsumed(frame.sequence)
                    }
                    emit(frame.pcm.copyOf())
                }
            } finally { synchronized(lock) { collecting = false } }
        }
        override suspend fun stop() {
            val closeOwner = synchronized(lock) { retire(); ordinary && current === this }
            if (closeOwner) this@CaptureFirstAudioInput.close()
        }
    }
}
