package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope

/**
 * Capture adapter for an Omni audio turn. It deliberately delegates microphone
 * ownership, Silero framing, bounded pre-roll, Smart Turn observation, queue
 * draining and the 300 ms deadline policy to Faraday's established
 * [AudioTurnCapture]. Whisper/transcription and Gemma native-audio packaging
 * are not enabled on this path.
 */
internal class OmniTurnCapture(
    input: AudioInput,
    scope: CoroutineScope,
    createDetector: () -> SpeechDetector,
    shadowObserver: CaptureShadowObserver? = null,
    private val log: (String) -> Unit = {},
) {
    private val capture = AudioTurnCapture(
        input = input,
        scope = scope,
        createDetector = createDetector,
        log = log,
        allowAudioOnlyTurns = true,
        maxAudioDurationMs = MAX_TURN_MS,
        rejectAtAudioLimit = true,
        shadowObserver = shadowObserver,
        turnStopPolicy = TurnStopPolicy.DEADLINE,
    )

    init {
        require(input.sampleRateHz == SAMPLE_RATE_HZ && input.channelCount == 1) {
            "Omni turns require 16 kHz mono microphone input"
        }
        capture.configurePreRollDuration(PRE_ROLL_MS.toLong())
    }

    suspend fun start() = capture.start(initialSilenceTimeoutMs = null)

    suspend fun awaitTurnCompletion(): Boolean = capture.awaitTurnCompletion()

    /** Stops and joins capture, then returns the captured PCM16 payload without a WAV header. */
    suspend fun stopPcm16(): ByteArray {
        val wav = capture.stop()
        require(wav.size >= WAV_HEADER_BYTES && wav.size % 2 == 0) {
            "Omni capture returned a malformed PCM16 WAV"
        }
        require(wav.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 73, 70, 70)) &&
            wav.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 65, 86, 69))) {
            "Omni capture returned a non-WAV payload"
        }
        val pcm = wav.copyOfRange(WAV_HEADER_BYTES, wav.size)
        require(WavEncoder.pcm16Mono(pcm, SAMPLE_RATE_HZ).contentEquals(wav)) {
            "Omni capture returned non-canonical 16 kHz mono PCM"
        }
        return pcm
    }

    internal companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_TURN_MS = 30_000
        const val PRE_ROLL_MS = 6_000
        const val WAV_HEADER_BYTES = 44
    }
}

