package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One hardware reader per call; sequential consumers borrow capture without releasing the recorder. */
class VoiceAudioSession(
    private val source: AudioInput,
    private val scope: CoroutineScope,
    private val historyMs: Long = 6000,
    private val log: (String) -> Unit = {}
) {
    private data class Frame(val sequence: Long, val pcm: ByteArray, val atMs: Long)
    private val lifecycle = Mutex()
    private val lock = Any()
    private val ring = ArrayDeque<Frame>()
    private val noiseProfile = CaptureNoiseProfile()
    private var ringBytes = 0
    private var sequence = 0L
    private var cursor = 0L
    private var active: BorrowedInput? = null
    private var pump: Job? = null
    @Volatile private var closed = false
    @Volatile private var failure: Throwable? = null
    val usable: Boolean get() = !closed && failure == null
    private val maxBytes = (source.sampleRateHz * source.channelCount * 2 * historyMs / 1000).toInt()
    init { require(historyMs in 100..6000); require(source.channelCount == 1) }

    private suspend fun start() = lifecycle.withLock {
        check(usable) { "Call microphone is no longer available." }
        if (pump != null) return@withLock
        try {
            source.start()
            pump = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    source.chunks().collect { pcm ->
                        synchronized(lock) {
                            val frame = Frame(++sequence, pcm.copyOf(), source.lastChunkCaptureTimeMs ?: System.nanoTime() / 1_000_000)
                            LiveCallAudioEvidence.record("microphone", pcm, source.sampleRateHz, frame.atMs)
                            ring.addLast(frame); ringBytes += pcm.size
                            while (ringBytes > maxBytes && ring.isNotEmpty()) ringBytes -= ring.removeFirst().pcm.size
                            active?.offer(frame)
                        }
                    }
                    if (!closed) error("Call microphone stream ended unexpectedly.")
                } catch (cancelled: CancellationException) {
                    if (!closed) failure = cancelled
                    synchronized(lock) { active?.channel?.close(cancelled) }
                    throw cancelled
                } catch (error: Throwable) {
                    failure = error
                    synchronized(lock) { active?.channel?.close(error) }
                    log("call_capture_failed reason=${error.javaClass.simpleName}")
                } finally { withContext(NonCancellable) { source.stop() } }
            }
            log("call_capture_started historyMs=$historyMs")
        } catch (error: Throwable) { failure = error; throw error }
    }

    fun borrow(label: String, replayAfterMs: Long? = null): AudioInput = BorrowedInput(label, replayAfterMs)

    private inner class BorrowedInput(private val label: String, private val replayAfterMs: Long?) : AudioInput {
        override val captureNoiseProfile get() = noiseProfile.takeIf { label == "command" }
        val channel = Channel<Frame>(64)
        private var started = false
        private var stopped = false
        @Volatile override var stoppedUnconsumedPcmBytes: Long? = null
            private set
        private var queued = 0L
        private var collecting = false
        private var deferredAcknowledgement = false
        @Volatile override var lastChunkSequence: Long? = null
            private set
        override fun deferConsumptionAcknowledgement() = synchronized(lock) {
            check(!collecting && !stopped)
            deferredAcknowledgement = true
        }
        override fun acknowledgeConsumed(sequence: Long) = synchronized(lock) {
            if (!stopped && deferredAcknowledgement) {
                check(sequence <= (lastChunkSequence ?: 0))
                cursor = maxOf(cursor, sequence)
            }
        }
        override var priorAudioForKeywords: ByteArray = byteArrayOf()
            private set
        override val sampleRateHz get() = source.sampleRateHz
        override val channelCount get() = source.channelCount
        @Volatile override var lastChunkCaptureTimeMs: Long? = null
            private set
        override val bufferedAudioMs get() = synchronized(lock) { queued.coerceAtLeast(0) * 1000 / (sampleRateHz * 2) }
        fun offer(frame: Frame) {
            if (!channel.trySend(frame).isSuccess) throw AudioBacklogException()
            queued += frame.pcm.size
        }
        override suspend fun start() {
            this@VoiceAudioSession.start()
            synchronized(lock) {
                check(!stopped && usable)
                if (started) return
                check(active == null) { "A call microphone consumer is still active." }
                if (replayAfterMs == null && ring.firstOrNull()?.sequence?.let { it > cursor + 1 } == true) {
                    log("call_capture_handoff_overflow consumer=$label partial_command_discarded=true")
                    throw AudioBacklogException()
                }
                if (replayAfterMs != null && ring.firstOrNull()?.atMs?.let { it > replayAfterMs + 200 } == true) {
                    log("call_capture_followup_overflow consumer=$label partial_command_discarded=true")
                    throw AudioBacklogException()
                }
                val replay = ring.filter { if (replayAfterMs != null) it.atMs >= replayAfterMs else it.sequence > cursor }
                val firstReplay = replay.firstOrNull()?.sequence ?: (sequence + 1)
                val history = RollingAudioBuffer(maxDurationMs = 3100)
                ring.filter { it.sequence < firstReplay }.forEach { history.append(it.pcm) }
                priorAudioForKeywords = history.snapshot()
                replay.forEach(::offer)
                active = this; started = true
                log("call_capture_borrow consumer=$label replayChunks=${replay.size} recorderRetained=true")
                if (replayAfterMs != null) {
                    log("call_capture_followup consumer=$label boundaryMs=$replayAfterMs " +
                        "firstRetainedMs=${replay.firstOrNull()?.atMs} replayMs=${replay.sumOf { it.pcm.size.toLong() } * 1000 / (sampleRateHz * 2)} " +
                        "handoffMs=${(System.nanoTime() / 1_000_000 - replayAfterMs).coerceAtLeast(0)}")
                }
            }
        }
        override fun chunks(): Flow<ByteArray> = flow {
            synchronized(lock) { check(started && !stopped && !collecting); collecting = true }
            try {
                for (frame in channel) {
                    failure?.let { throw it }
                    synchronized(lock) {
                        queued -= frame.pcm.size
                        if (!deferredAcknowledgement) cursor = maxOf(cursor, frame.sequence)
                        lastChunkSequence = frame.sequence
                        lastChunkCaptureTimeMs = frame.atMs
                    }
                    emit(frame.pcm.copyOf()) // A consumer's gain processing must not mutate retained raw PCM.
                }
            } finally { synchronized(lock) { collecting = false } }
        }
        override suspend fun stop() = synchronized(lock) {
            if (!stopped) {
                // Freeze the exact reader cutoff under the pump/cursor lock BEFORE the
                // channel is cleared. Unacknowledged frames remain in ring for the next reader.
                // A source frame not admitted under this lock yet belongs to the next reader;
                // this is a session admission cutoff, not an atomic hardware sampling barrier.
                stoppedUnconsumedPcmBytes = ring.asSequence().filter { it.sequence > cursor }
                    .sumOf { it.pcm.size.toLong() } + source.bufferedAudioMs * sampleRateHz * 2 / 1000
                stopped = true
                if (active === this) active = null
                channel.cancel(); queued = 0
                log("call_capture_return consumer=$label recorderRetained=${usable}")
                failure?.let { throw it }
            }
        }
    }
    suspend fun close(reason: Throwable? = null) = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!closed) {
                closed = true
                if (reason != null) failure = reason
                synchronized(lock) {
                    if (reason != null) active?.channel?.close(reason) else active?.channel?.cancel()
                    active = null; ring.clear(); ringBytes = 0
                }
                val reader = pump
                if (reader != null) reader.cancelAndJoin() else source.stop()
                log("call_capture_closed")
            }
        }
    }
}
