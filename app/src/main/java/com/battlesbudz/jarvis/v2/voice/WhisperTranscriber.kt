package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.voice.whisper.*
import java.io.File

/** Optional background window decoding: Whisper base is not a native streaming recognizer. Decoding never runs on the live microphone thread. VAD bounds the retained input and Gemma still handles blanks. */
class WhisperTranscriber(private val directory: File, live: Boolean = true, private val log: (String) -> Unit = {}, modelSession: VoiceModelSession? = null, private val audioEvidence: RecognitionAudioEvidence? = null, warmProbe: Boolean = false, private val preferNpu: Boolean = false) : StreamingTranscriber {
    init { require(!warmProbe || (!live && modelSession != null)) }
    private val audio = RollingAudioBuffer(AudioFormat(16_000), maxDurationMs = 25_000)
    // Bound idle room audio, then retain the contiguous phrase through endpoint.
    // Final-only probes without observeSpeech() still receive their complete PCM.
    private val speechGate = ExternalSpeechGate.completePhrase()
    private fun createRecognizer(): WhisperDecoder {
        if (!preferNpu) return CpuWhisperDecoder(directory)
        val cpuDirectory = if (directory.name == WhisperQnnModelStore.DIRECTORY)
            File(directory.parentFile, "sherpa-onnx-whisper-base.en") else directory
        return FallbackWhisperDecoder(
            primary = { check(directory.name == WhisperQnnModelStore.DIRECTORY) { "QNN bundle unavailable" }; WhisperQnnDecoder(directory, log) },
            cpu = { CpuWhisperDecoder(cpuDirectory) }, log = log)
    }
    private val modelKey = "${directory.path}:qnn=$preferNpu"
    private val lease = if (warmProbe) requireNotNull(modelSession).whisper.acquireWarm(modelKey)
        else modelSession?.whisper?.acquire(modelKey, create = ::createRecognizer)
    private val recognizer = lease?.value ?: createRecognizer()
    private var healthy = true
    private fun releaseRecognizer() { if (lease != null) lease.finish(healthy) else recognizer.close() }
    private var closed = false
    private val providersUsed = java.util.concurrent.ConcurrentSkipListSet<String>()
    private val directWork = AsrRecognitionWorkAccumulator("whisper_batch_decode_callback_wall")
    private val streaming = if (live) AsyncWhisperSession(::decode, ::releaseRecognizer, log, cancel = { healthy = false; recognizer.cancel() }) else null
    override val recognitionWorkMetrics get() = (streaming?.recognitionWorkMetrics ?: directWork.snapshot())
        .let { it.copy(scope = "${it.scope}:${providersUsed.takeIf { used -> used.isNotEmpty() }?.joinToString("+") ?: "not_executed"}") }
    override val completedEndpointCue get() = streaming?.completedEndpointCue
    override val noTextSilenceMs: Long get() = 900
    override fun prepareForBoundedProbe(maxAudioMs: Long): String {
        check(streaming == null && audio.sizeBytes() == 0L)
        require(maxAudioMs in 1..4000)
        return "whisper_final_only_v1"
    }
    override fun observeSpeech(speech: Boolean) {
        if (streaming != null) streaming.observeSpeech(speech) else speechGate.observe(speech)
    }
    override fun accept(pcm: ByteArray): String = accept(pcm, true)
    override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
        check(!closed)
        if (streaming != null) return streaming.accept(pcm, allowPartial)
        audio.append(speechGate.accept(pcm))
        return ""
    }
    override fun retireIdleCaption(): Boolean = !closed && streaming?.retireIdleCaption() == true
    override fun resumeRetiredCaption(): Boolean = !closed && streaming?.resumeRetiredCaption() == true
    override fun finish(): String {
        if (streaming != null) return streaming.finish()
        val snapshot = audio.snapshot()
        return directWork.measure(AsrRecognitionWorkAccumulator.Phase.FINAL, snapshot.size / 2) { decode(snapshot) }
    }
    override fun recover(pcm: ByteArray): String = streaming?.recover(pcm) ?: directWork.measure(AsrRecognitionWorkAccumulator.Phase.RECOVERY, pcm.size / 2) { decode(pcm) }
    private fun decode(pcm: ByteArray): String {
        check(!closed)
        if (pcm.isEmpty()) return ""
        audioEvidence?.record(pcm, replace = true)
        return try { recognizer.decode(pcmFloats(pcm)).also { providersUsed.add(recognizer.lastDecodeBackend) } }
        catch (error: Throwable) { healthy = false; throw error }
    }
    override fun close() { if (!closed) { if (streaming != null) streaming.close() else releaseRecognizer(); closed = true; audio.clear(); speechGate.clear() } }
}

internal fun pcmFloats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
    ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
}
