package com.battlesbudz.jarvis.v2.voice

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android microphone adapter. It emits bounded PCM chunks and owns only the
 * microphone lifecycle; turn detection and model decisions stay in the voice coordinator.
 */
class AndroidAudioInput(
    private val scope: CoroutineScope,
    private val format: AudioFormat = AudioFormat(),
    private val chunkSamples: Int = 1_600,
    private val audioManager: android.media.AudioManager? = null,
    private val onWaiting: (Boolean) -> Unit = {},
    private val dictation: Boolean = false,
    private val echoCancellation: Boolean = false,
    private val noiseSuppression: Boolean = false,
    private val onLevel: (Float) -> Unit = {},
    private val evidence: DuplexAudioEvidence? = null,
    private val communicationInput: Boolean = false,
    private val log: (String) -> Unit = {}
) : AudioInput {
    override val sampleRateHz: Int = format.sampleRateHz
    override val channelCount: Int = format.channelCount

    private data class CapturedChunk(val pcm: ByteArray, val atMs: Long)
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong()
    private val emittedChunks = Channel<CapturedChunk>(64, onUndeliveredElement = {
        queuedBytes.addAndGet(-it.pcm.size.toLong())
    })
    @Volatile override var lastChunkCaptureTimeMs: Long? = null
        private set
    override val bufferedAudioMs: Long get() = queuedBytes.get().coerceAtLeast(0) * 1000 / (sampleRateHz * 2)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile var ownsRecorder = false
        private set
    private var captureJob: Job? = null
    private val priorityLost = java.util.concurrent.atomic.AtomicBoolean(false)
    private val preferredRouteId = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)

    fun discardBufferedAudio() {
        while (true) queuedBytes.addAndGet(-(emittedChunks.tryReceive().getOrNull() ?: break).pcm.size.toLong())
    }

    override fun chunks(): Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        for (chunk in emittedChunks) {
            queuedBytes.addAndGet(-chunk.pcm.size.toLong())
            lastChunkCaptureTimeMs = chunk.atMs
            emit(chunk.pcm)
        }
    }

    override suspend fun start() {
        if (captureJob?.isActive == true) return
        check(format.channelCount == 1 && format.bitsPerSample == 16) {
            "AndroidAudioInput currently requires PCM16 mono input."
        }
        var waiting = false
        while (busy(null)) {
            if (!waiting) { onWaiting(true); waiting = true }
            kotlinx.coroutines.delay(250)
        }
        if (waiting) onWaiting(false)
        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "The microphone could not be initialized." }
        val bufferSize = maxOf(minBuffer, format.sampleRateHz * 2)
        val builder = AudioRecord.Builder()
            .setAudioSource(if (communicationInput) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(format.sampleRateHz)
                .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferSize)
        if (android.os.Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false)
        val created = builder.build()
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            error("The microphone could not be initialized.")
        }
        var aecDefaultEnabled: Boolean? = null
        var aecEnableStatus: Int? = null
        var aecEnableError: String? = null
        val aec = if (echoCancellation && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(created.audioSessionId) }.getOrNull()
                ?.also { effect ->
                    aecDefaultEnabled = runCatching { effect.enabled }.getOrNull()
                    runCatching { effect.setEnabled(true) }
                        .onSuccess { aecEnableStatus = it }
                        .onFailure { aecEnableError = it.javaClass.simpleName }
                }
        } else null
        log("capture_effects session=${created.audioSessionId} " +
            "aecAvailable=${android.media.audiofx.AcousticEchoCanceler.isAvailable()} " +
            "aecControl=${runCatching { aec?.hasControl() }.getOrNull()} " +
            "aecId=${runCatching { aec?.id }.getOrNull()} " +
            "aecImplementation=${runCatching { aec?.descriptor?.implementor }.getOrNull()} " +
            "aecUuid=${runCatching { aec?.descriptor?.uuid }.getOrNull()} " +
            "preHardwarePcm=unavailable effectiveness=unmeasured")
        log("capture_aec requested=$echoCancellation defaultEnabled=$aecDefaultEnabled " +
            "enableStatus=$aecEnableStatus enableError=$aecEnableError enabled=${runCatching { aec?.enabled }.getOrNull()} " +
            "platformUncontrolledProcessing=unknown")
        var nsDefaultEnabled: Boolean? = null
        var nsEnableStatus: Int? = null
        var nsEnableError: String? = null
        // Explicitly disable NS when this profile requests speech preservation.
        // Some platforms insert it by audio source; merely not creating an NS
        // object does not make requested=false a verified disabled state.
        val suppressor = if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            runCatching { android.media.audiofx.NoiseSuppressor.create(created.audioSessionId) }.getOrNull()
                ?.also { effect ->
                    nsDefaultEnabled = runCatching { effect.enabled }.getOrNull()
                    runCatching { effect.setEnabled(noiseSuppression) }
                        .onSuccess { nsEnableStatus = it }
                        .onFailure { nsEnableError = it.javaClass.simpleName }
                }
        } else null
        log("capture_noise_suppression requested=$noiseSuppression available=${android.media.audiofx.NoiseSuppressor.isAvailable()} " +
            "defaultEnabled=$nsDefaultEnabled enableStatus=$nsEnableStatus enableError=$nsEnableError " +
            "enabled=${runCatching { suppressor?.enabled }.getOrNull()} control=${runCatching { suppressor?.hasControl() }.getOrNull()} " +
            "implementation=${runCatching { suppressor?.descriptor?.implementor }.getOrNull()} " +
            "uuid=${runCatching { suppressor?.descriptor?.uuid }.getOrNull()} platformUncontrolledProcessing=unknown")
        if (evidence != null) {
            aec?.setControlStatusListener { _, granted -> log("capture_aec_control granted=$granted") }
            aec?.setEnableStatusListener { _, enabled -> log("capture_aec_state enabled=$enabled") }
        }
        val callback = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                val config = created.activeRecordingConfiguration
                if (!dictation) MicrophoneHandoff.ownRecorderSilenced = config?.isClientSilenced == true
                applyMicrophonePreference(created, config?.audioDevice)
                log("capture_route silenced=${config?.isClientSilenced} source=${config?.clientAudioSource} routeType=${config?.audioDevice?.type} routeId=${config?.audioDevice?.id} " +
                    "clientRate=${config?.clientFormat?.sampleRate} clientChannels=${config?.clientFormat?.channelCount} " +
                    "clientEncoding=${config?.clientFormat?.encoding} deviceRate=${config?.format?.sampleRate} " +
                    "deviceChannels=${config?.format?.channelCount} deviceEncoding=${config?.format?.encoding}")
            }
        }
        try {
            created.registerAudioRecordingCallback(java.util.concurrent.Executor { it.run() }, callback)
            MicrophoneHandoff.withRecorderLock {
                if (!dictation) {
                    if (MicrophoneHandoff.shouldYield) throw MicrophoneBusyException()
                    MicrophoneHandoff.registerRecorder(created,
                        recording = { created.recordingState == AudioRecord.RECORDSTATE_RECORDING },
                        stop = {
                            priorityLost.set(true)
                            runCatching { created.stop() }
                            log("capture_priority_stop session=${created.audioSessionId}")
                        })
                }
                created.startRecording()
                check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start recording." }
                applyMicrophonePreference(created, created.routedDevice)
            }
        } catch (error: Throwable) {
            MicrophoneHandoff.withRecorderLock {
                created.unregisterAudioRecordingCallback(callback)
                aec?.release()
                suppressor?.release()
                created.release()
                if (!dictation) MicrophoneHandoff.unregisterRecorder(created)
            }
            throw error
        }
        log("capture_open source=${if (communicationInput) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"} " +
            "actualClientSource=${created.activeRecordingConfiguration?.clientAudioSource} " +
            "requestedRate=${format.sampleRateHz} actualRate=${created.sampleRate} actualChannels=${created.channelCount} actualEncoding=${created.audioFormat} " +
            "routeType=${created.routedDevice?.type} routeId=${created.routedDevice?.id} session=${created.audioSessionId} " +
            "silenced=${created.activeRecordingConfiguration?.isClientSilenced} pcmScope=after_platform_effects")
        val ready = CompletableDeferred<Unit>()
        recorder = created
        ownsRecorder = true
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val pcm = ByteArray(chunkSamples * 2)
                    val assembler = PcmChunkAssembler(pcm.size)
                    var capturedBytes = 0
                    val timestamp = if (evidence != null) android.media.AudioTimestamp() else null
                    while (isActive) {
                        if (busy(created)) throw MicrophoneBusyException()
                        val count = created.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                        if (busy(created)) throw MicrophoneBusyException()
                        if (count == 0) { kotlinx.coroutines.delay(20); continue }
                        if (count > 0) {
                            if (evidence != null && timestamp != null) {
                                val observed = System.nanoTime()
                                val valid = runCatching { created.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS }.getOrDefault(false)
                                evidence.capture(pcm, count, observed, timestamp.framePosition.takeIf { valid }, timestamp.nanoTime.takeIf { valid })
                            }
                            var squares = 0.0
                            for (i in 0 until count - 1 step 2) {
                                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                                squares += sample * sample
                            }
                            onLevel((kotlin.math.sqrt(squares / (count / 2).coerceAtLeast(1)) / 4000.0).toFloat().coerceIn(0f, 1f))
                            assembler.accept(pcm, count) { chunk ->
                                queuedBytes.addAndGet(chunk.size.toLong())
                                if (!emittedChunks.trySend(CapturedChunk(chunk, System.nanoTime() / 1_000_000)).isSuccess) {
                                    queuedBytes.addAndGet(-chunk.size.toLong())
                                    throw AudioBacklogException()
                                }
                            }
                            capturedBytes += count
                            // Retain startup audio while allowing the hardware capture path to warm up.
                            if (capturedBytes >= format.sampleRateHz * 2 * 300 / 1000) ready.complete(Unit)
                        }
                        else if (count < 0) error("The microphone stopped recording unexpectedly.")
                    }
                }
            } catch (cancelled: CancellationException) {
                ready.cancel()
                throw cancelled
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                emittedChunks.close(error)
            } finally {
                MicrophoneHandoff.withRecorderLock {
                    runCatching { created.stop() }
                    created.unregisterAudioRecordingCallback(callback)
                    aec?.release()
                suppressor?.release()
                    created.release()
                    if (!dictation) MicrophoneHandoff.unregisterRecorder(created)
                }
                log("capture_released session=${created.audioSessionId}")
                if (recorder === created) { recorder = null; ownsRecorder = false }
                onLevel(0f)
            }
        }
        try { withTimeout(5000) { ready.await() } }
        catch (error: Throwable) { stop(); throw error }
    }

    private fun busy(record: AudioRecord?): Boolean = MicrophoneHandoff.withRecorderLock {
        if (!dictation && (priorityLost.get() || MicrophoneHandoff.shouldYield)) return@withRecorderLock true
        val manager = audioManager ?: return@withRecorderLock false
        val occupied = MicrophonePolicy.shouldYield(
            manager.activeRecordingConfigurations.size,
            record?.recordingState == AudioRecord.RECORDSTATE_RECORDING,
            record?.activeRecordingConfiguration?.isClientSilenced == true,
            MicrophonePolicy.externalCommunication(manager.mode, CommunicationAudioSession.ownsMode()) || manager.isMicrophoneMute)
        if (occupied && !dictation) MicrophoneHandoff.requestInterruption("capture_detected_contention")
        occupied
    }

    private fun applyMicrophonePreference(record: AudioRecord, route: android.media.AudioDeviceInfo?) {
        val routeId = route?.id ?: Int.MIN_VALUE
        if (!preferredRouteId.compareAndSet(Int.MIN_VALUE, routeId) && preferredRouteId.get() == routeId) return
        preferredRouteId.set(routeId)
        val preference = MicrophonePreference.forRoute(android.os.Build.VERSION.SDK_INT,
            route?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC)
        val directionApplied = if (preference.requestTowardsUser) runCatching {
            record.setPreferredMicrophoneDirection(AudioRecord.MIC_DIRECTION_TOWARDS_USER)
        }.getOrDefault(false) else runCatching {
            record.setPreferredMicrophoneDirection(AudioRecord.MIC_DIRECTION_UNSPECIFIED)
        }.getOrDefault(false)
        val fieldApplied = preference.fieldDimension?.let { field -> runCatching {
            record.setPreferredMicrophoneFieldDimension(field)
        }.getOrDefault(false) } ?: runCatching { record.setPreferredMicrophoneFieldDimension(0f) }.getOrDefault(false)
        log("capture_microphone_preference reason=${preference.reason} towardsUserRequested=${preference.requestTowardsUser} " +
            "towardsUserApplied=$directionApplied fieldDimension=${preference.fieldDimension} fieldApplied=$fieldApplied " +
            "actualRouteType=${route?.type} actualRouteId=${route?.id} physicalBottomMic=not_addressable")
    }

    override suspend fun stop() {
        val job = captureJob
        job?.cancel()
        // Unblock read(), then wait for its sole owner to release the recorder.
        MicrophoneHandoff.withRecorderLock { recorder?.let { runCatching { it.stop() } } }
        job?.join()
        captureJob = null
        emittedChunks.cancel()
    }
}
