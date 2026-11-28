package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** VAD-only interruption policy for the LFM3-Omni call. No ASR/Whisper probe is used. */
internal class OmniDuplexBargeInGate(
    private val playbackConfirmationMs: Long = 240,
    private val idleConfirmationMs: Long = 96
) {
    enum class Decision { WAIT, CONFIRMED }

    private var candidateSinceMs: Long? = null
    private var confirmed = false

    /**
     * U8: measured durations for the diagnostics evidence. [lastConfirmedSpeechMs]
     * is the VAD-positive non-echo speech that earned the last confirmation;
     * [lastRejectedSpeechMs] is the candidate the echo check killed, if any;
     * [echoRejectedCandidates] counts such rejections. Properties only — the
     * constructor is untouched, so the gate's unit tests are unaffected.
     */
    var lastConfirmedSpeechMs: Long = 0L
        private set
    var lastRejectedSpeechMs: Long = 0L
        private set
    var echoRejectedCandidates: Int = 0
        private set

    fun update(speech: Boolean, playing: Boolean, playbackEcho: Boolean, nowMs: Long): Decision {
        if (confirmed) return Decision.CONFIRMED
        if (!speech || playing && playbackEcho) {
            if (playing && playbackEcho) {
                candidateSinceMs?.let { since ->
                    lastRejectedSpeechMs = nowMs - since
                    echoRejectedCandidates++
                }
            }
            candidateSinceMs = null
            return Decision.WAIT
        }
        val since = candidateSinceMs ?: nowMs.also { candidateSinceMs = it }
        val required = if (playing) playbackConfirmationMs else idleConfirmationMs
        if (nowMs - since < required) return Decision.WAIT
        lastConfirmedSpeechMs = nowMs - since
        confirmed = true
        return Decision.CONFIRMED
    }

    fun reset() {
        candidateSinceMs = null
        confirmed = false
    }
}

/**
 * Keeps recent PCM submitted to the speaker and rejects microphone frames that
 * are strongly correlated with that playback after a plausible room delay.
 * The platform AEC remains enabled separately by AndroidAudioInput.
 */
internal class OmniPlaybackEchoGuard(
    private val sampleRateHz: Int = 16_000,
    private val maxDelayMs: Int = 300,
    private val correlationThreshold: Double = 0.82
) {
    private val reference = RollingAudioBuffer(AudioFormat(sampleRateHz), maxDurationMs = maxDelayMs + 500L)

    init {
        require(sampleRateHz > 0)
        require(maxDelayMs >= 0)
        require(correlationThreshold in 0.0..1.0)
    }

    /** Called with mono PCM at the sink's current playback position (24 kHz by default). */
    fun rememberPlayback(pcm: ByteArray, playbackSampleRateHz: Int = sampleRateHz) {
        require(playbackSampleRateHz > 0)
        reference.append(if (playbackSampleRateHz == sampleRateHz) pcm else resample(pcm, playbackSampleRateHz))
    }

    /** True only when the frame closely matches a delayed portion of speaker PCM. */
    fun isPlaybackEcho(microphonePcm: ByteArray): Boolean {
        val mic = samples(microphonePcm)
        if (mic.size < sampleRateHz / 100) return false // Require at least 10 ms.
        val ref = samples(reference.snapshot())
        if (ref.size < mic.size) return false
        val maxLag = minOf(maxDelayMs * sampleRateHz / 1000, ref.size - mic.size)
        val lagStride = maxOf(1, sampleRateHz / 800) // 1.25 ms coarse search.
        val sampleStride = maxOf(1, mic.size / 64)
        var bestLag = 0
        var bestCorrelation = 0.0
        for (lag in 0..maxLag step lagStride) {
            val score = normalizedCorrelation(mic, ref, ref.size - mic.size - lag, sampleStride)
            if (score > bestCorrelation) { bestCorrelation = score; bestLag = lag }
        }
        val fineStart = (bestLag - lagStride).coerceAtLeast(0)
        val fineEnd = (bestLag + lagStride).coerceAtMost(maxLag)
        for (lag in fineStart..fineEnd) {
            bestCorrelation = maxOf(bestCorrelation,
                normalizedCorrelation(mic, ref, ref.size - mic.size - lag, sampleStride = 1))
        }
        return bestCorrelation >= correlationThreshold
    }

    fun clear() = reference.clear()

    private fun samples(pcm: ByteArray): ShortArray {
        val count = pcm.size / 2
        return ShortArray(count) { i ->
            ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    private fun resample(pcm: ByteArray, sourceRateHz: Int): ByteArray {
        val source = samples(pcm)
        if (source.isEmpty()) return byteArrayOf()
        val targetCount = (source.size.toLong() * sampleRateHz / sourceRateHz).toInt().coerceAtLeast(1)
        val result = ByteArray(targetCount * 2)
        for (i in 0 until targetCount) {
            val position = i.toDouble() * sourceRateHz / sampleRateHz
            val left = position.toInt().coerceAtMost(source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val fraction = position - left
            val value = (source[left] * (1.0 - fraction) + source[right] * fraction)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            result[i * 2] = value.toByte()
            result[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        return result
    }

    private fun normalizedCorrelation(a: ShortArray, b: ShortArray, offset: Int, sampleStride: Int): Double {
        if (offset < 0 || offset + a.size > b.size) return 0.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices step sampleStride) {
            val av = a[i].toDouble()
            val bv = b[offset + i].toDouble()
            dot += av * bv
            aa += av * av
            bb += bv * bv
        }
        if (aa < 1.0 || bb < 1.0) return 0.0
        return kotlin.math.abs(dot) / kotlin.math.sqrt(aa * bb)
    }
}

/** Bounded microphone turn PCM, including six seconds preceding confirmed onset. */
internal class OmniDuplexTurnBuffer(
    private val sampleRateHz: Int = 16_000,
    preRollMs: Long = 6_000,
    private val maxTurnMs: Long = 30_000,
    private val endpointSilenceMs: Long = 300
) {
    data class Endpoint(val pcm: ByteArray, val reason: Reason, val decisionAtMs: Long = 0L)
    enum class Reason { SILENCE, AUDIO_CAP }

    // Keep a confirmation cushion as well as the promised pre-speech six seconds.
    private val preRoll = RollingAudioBuffer(AudioFormat(sampleRateHz), preRollMs + 1_000)
    private val turn = RollingAudioBuffer(AudioFormat(sampleRateHz), maxTurnMs)
    private var active = false
    private var lastSpeechAtMs = 0L
    private var endpointExtended = false
    private var capturedBytes = 0L

    init {
        require(sampleRateHz > 0 && preRollMs > 0 && maxTurnMs > 0 && endpointSilenceMs > 0)
    }

    /** Feed each microphone frame before gate evaluation so onset context is retained. */
    fun observe(pcm: ByteArray, speech: Boolean, nowMs: Long) {
        if (!active) {
            preRoll.append(pcm)
            return
        }
        turn.append(pcm)
        capturedBytes += pcm.size
        if (speech) lastSpeechAtMs = nowMs
    }

    /** Begin only after the VAD/echo gate confirms user speech. */
    fun begin(nowMs: Long): Boolean {
        if (active) return false
        active = true
        turn.append(preRoll.snapshot())
        capturedBytes = turn.sizeBytes()
        preRoll.clear()
        lastSpeechAtMs = nowMs
        return true
    }

    fun currentPcm(): ByteArray = turn.snapshot()
    fun isActive(): Boolean = active
    fun silenceDue(nowMs: Long): Boolean = active && nowMs - lastSpeechAtMs >= endpointSilenceMs

    /**
     * Deadline-first endpointing: silence submits at its deadline unless the
     * supplied Smart Turn verdict says a high-confidence continuation is needed.
     * That verdict may buy one additional silence window, never repeated holds.
     */
    fun endpoint(nowMs: Long, smartTurnWantsMore: Boolean = false): Endpoint? {
        if (!active) return null
        if (capturedBytes >= maxTurnMs * sampleRateHz * 2 / 1000) {
            return seal(Reason.AUDIO_CAP, nowMs)
        }
        if (nowMs - lastSpeechAtMs < endpointSilenceMs) return null
        if (smartTurnWantsMore && !endpointExtended) {
            endpointExtended = true
            lastSpeechAtMs = nowMs
            return null
        }
        return seal(Reason.SILENCE, nowMs)
    }

    fun reset() {
        preRoll.clear()
        turn.clear()
        active = false
        lastSpeechAtMs = 0L
        endpointExtended = false
        capturedBytes = 0L
    }

    private fun seal(reason: Reason, nowMs: Long): Endpoint {
        val endpoint = Endpoint(turn.snapshot(), reason, nowMs)
        reset()
        return endpoint
    }
}

/**
 * Call-lifetime microphone pump for U5. It never stops the AudioInput between
 * assistant playback and user turns. U5 consumes events while Omni generates.
 */
internal class OmniDuplexCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val createVad: () -> SpeechDetector,
    private val assistantActive: () -> Boolean,
    private val playbackAudible: () -> Boolean = assistantActive,
    private val onBargeIn: () -> Unit,
    private val smartTurnWantsMore: (ByteArray) -> Boolean = { false },
    private val onLog: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val gate: OmniDuplexBargeInGate = OmniDuplexBargeInGate(),
    private val echoGuard: OmniPlaybackEchoGuard = OmniPlaybackEchoGuard(),
    private val turnBuffer: OmniDuplexTurnBuffer = OmniDuplexTurnBuffer()
) {
    sealed interface Event {
        data class SpeechTurnStarted(val atMs: Long, val interruptedAssistantResponse: Boolean) : Event
        data class TurnReady(val pcm: ByteArray, val endpoint: OmniDuplexTurnBuffer.Reason, val decisionAtMs: Long = 0L) : Event
        data class CaptureFailed(val cause: Throwable) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** U8: invoked when the echo guard rejects a VAD candidate. Set by the call adapter; null keeps prior behavior. */
    var onEchoRejected: (() -> Unit)? = null

    /** U8: measured confirmed-speech duration of the last accepted gate decision. */
    val lastConfirmedSpeechMs: Long get() = gate.lastConfirmedSpeechMs
    /** U8: speech duration of the last candidate the echo check rejected. */
    val lastRejectedSpeechMs: Long get() = gate.lastRejectedSpeechMs
    private var pump: Job? = null
    private var detector: SpeechDetector? = null
    @Volatile private var closed = false
    private var smartTurnChecked = false

    init { require(input.sampleRateHz == 16_000 && input.channelCount == 1) }

    /** U4 supplies PCM in rendered playback order; do not register future queued audio. */
    fun rememberPlayback(pcm: ByteArray, playbackSampleRateHz: Int = 24_000) =
        echoGuard.rememberPlayback(pcm, playbackSampleRateHz)

    suspend fun start() {
        check(!closed && pump == null) { "Omni duplex capture is already started or closed." }
        input.start()
        detector = try { createVad() } catch (error: Throwable) {
            input.stop()
            throw error
        }
        pump = scope.launch(captureDispatcher) {
            try {
                input.chunks().collect { pcm -> process(pcm) }
            } catch (error: Throwable) {
                if (!closed) eventChannel.send(Event.CaptureFailed(error))
            } finally {
                detector?.close()
                detector = null
            }
        }
    }

    private suspend fun process(pcm: ByteArray) {
        val now = nowMs()
        val decision = requireNotNull(detector).accept(pcm)
        val responseActive = assistantActive()
        val audible = playbackAudible()
        val isEcho = audible && echoGuard.isPlaybackEcho(pcm)
        turnBuffer.observe(pcm, decision.isSpeech && !isEcho, now)
        if (!turnBuffer.isActive()) {
            val rejectionsBefore = gate.echoRejectedCandidates
            val gateDecision = gate.update(decision.isSpeech, responseActive, isEcho, now)
            if (gate.echoRejectedCandidates > rejectionsBefore) {
                // U8: echo rejection only ever records the decision — playback
                // and capture continue untouched.
                onEchoRejected?.invoke()
            }
            if (gateDecision == OmniDuplexBargeInGate.Decision.CONFIRMED) {
                gate.reset()
                val interrupted = responseActive
                if (interrupted) onBargeIn() // U5 cancels generation and flushes U4's AudioTrack.
                check(turnBuffer.begin(now)) { "A turn was already active when VAD confirmed speech." }
                smartTurnChecked = false
                eventChannel.send(Event.SpeechTurnStarted(now, interrupted))
                onLog("omni_barge_confirmed interrupted=$interrupted echoRejected=$isEcho")
            }
            return
        }
        var smartTurnHold = false
        if (!smartTurnChecked && turnBuffer.silenceDue(now)) {
            smartTurnChecked = true
            smartTurnHold = runCatching { smartTurnWantsMore(turnBuffer.currentPcm()) }.getOrDefault(false)
        }
        turnBuffer.endpoint(now, smartTurnHold)?.let { endpoint ->
            eventChannel.send(Event.TurnReady(endpoint.pcm, endpoint.reason, endpoint.decisionAtMs))
            gate.reset()
            smartTurnChecked = false
        }
    }

    suspend fun stop() {
        if (closed) return
        closed = true
        pump?.cancelAndJoin()
        pump = null
        try { input.stop() } finally {
            echoGuard.clear()
            turnBuffer.reset()
            eventChannel.close()
        }
    }

}
