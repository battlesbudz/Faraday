package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** One microphone and one temporary ASR owner. Playback is never paused for a probe. */
class BargeInAudioInput(
    private val input: AudioInput,
    private val createDetector: () -> SpeechDetector,
    private val playing: () -> Boolean,
    private val createTranscriber: () -> StreamingTranscriber,
    private val spokenText: () -> String,
    private val onConfirmed: () -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : AudioInput {
    override val sampleRateHz = input.sampleRateHz
    override val channelCount = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        val preRoll = RollingAudioBuffer(AudioFormat(sampleRateHz), maxDurationMs = 6000)
        var gate = BargeInGate()
        val detector = createDetector()
        val quietEvidence = QuietSpeechEvidence()
        var recognizer: StreamingTranscriber? = null
        var recognizedBytes = 0
        var delivered = false
        var lastCandidateLog = Long.MIN_VALUE / 2
        try {
            input.chunks().collect { pcm ->
                if (delivered) { emit(pcm); return@collect }
                preRoll.append(pcm)
                val decision = detector.accept(pcm)
                val audible = playing()
                // Recognition is required while preparing the reply as well as during
                // playback. VAD alone previously let a rustle cancel unfinished synthesis.
                val asr = recognizer ?: createTranscriber().also { recognizer = it }
                recognizedBytes += pcm.size
                asr.observeSpeech(decision.isSpeech)
                val transcript = TranscriptContent.speech(asr.accept(pcm))
                val speech = decision.isSpeech || quietEvidence.accept(transcript, decision.probability, nowMs(), false)
                if (gate.update(speech, audible, nowMs(), transcript, spokenText()) == BargeInGate.Action.CONFIRM) {
                    // Close the probe ASR before the turn's lazy ASR can load: never two models.
                    recognizer?.close(); recognizer = null
                    delivered = true
                    onConfirmed()
                    log("barge_speech_confirmed method=new_words phase=${if (audible) "playback" else "preparing"} preRollMs=${preRoll.sizeBytes() / 32} request=${gate.requestText.take(100)}")
                    emit(preRoll.snapshot()); preRoll.clear()
                } else if (recognizedBytes >= 16_000 * 2 * 10) {
                    recognizer?.close(); recognizer = null
                    recognizedBytes = 0; gate = BargeInGate(); quietEvidence.reset()
                    log("barge_echo_window_reset playback_uninterrupted=true")
                }
                if (!delivered && (speech || transcript.isNotBlank()) && nowMs() - lastCandidateLog >= 1000) {
                    lastCandidateLog = nowMs()
                    log("barge_candidate_rejected reason=${gate.reason} playing=$audible transcript=${transcript.takeLast(80)}")
                }
            }
        } finally { recognizer?.close(); detector.close(); preRoll.clear() }
    }
}

/** Delay the final-turn ASR until the interruption recognizer has released its model. */
class LazyStreamingTranscriber(private val create: () -> StreamingTranscriber) : StreamingTranscriber {
    private var delegate: StreamingTranscriber? = null
    private fun active() = delegate ?: create().also { delegate = it }
    override val recognitionWorkMetrics: AsrRecognitionWorkMetrics? get() = delegate?.recognitionWorkMetrics
    override val noTextSilenceMs: Long get() = delegate?.noTextSilenceMs ?: 900
    override fun observeSpeech(speech: Boolean) { if (speech) active().observeSpeech(true) else delegate?.observeSpeech(false) }
    override fun accept(pcm: ByteArray) = active().accept(pcm)
    override fun finish() = delegate?.finish().orEmpty()
    override fun recover(pcm: ByteArray) = active().recover(pcm)
    override fun close() { delegate?.close(); delegate = null }
}
