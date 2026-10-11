package com.battlesbudz.jarvis.v2.voice

import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/** Local Jarvis voice output: Gemma text -> selected Sherpa model PCM -> Android audio route. */
class PiperVoiceOutput internal constructor(
    private val modelDirectory: String,
    private val engine: TtsEngine = TtsEngine.PIPER_NORTHERN,
    private val modelSession: VoiceModelSession? = null,
    private val deliveryLedger: SpeechDeliveryLedger? = null,
    private val onPlaybackEnded: () -> Unit = {},
    /** Real answer playback reference; survives into the next capture. */
    private val onEchoReference: (String) -> Unit = {},
    // Preserve each caller's existing bounded PCM headroom independently of speech content.
    private val maxQueuedPassages: Int = 2,
    private val audioTrace: SpeechAudioTrace? = null,
    private val onReady: () -> Unit = {},
    private val onPlayback: (VoicePlaybackFrame) -> Unit = {},
    private val onMetrics: (TtsSessionMetrics) -> Unit = {},
    private val speakerId: Int = engine.speaker,
    private val log: (String) -> Unit = {},
    /** Text-free System.nanoTime / 1e6 boundaries, emitted once per answer speech session. */
    private val onTiming: (stage: String, monotonicMs: Long) -> Unit = { _, _ -> }
) : VoiceOutput {
    private val numThreads = 4
    private val audioTrackFactory = SpeechAudioTrackFactory(log)
    private val evidenceStream = LiveCallAudioEvidence.newStream("answer")
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val playbackBoundaryReported = AtomicBoolean(false)
    private fun reportPlaybackEnded() {
        if (recentSpokenText().isNotBlank() && playbackBoundaryReported.compareAndSet(false, true)) onPlaybackEnded()
    }
    private val playbackLock = Any()
    @Volatile private var interrupted = false
    private val deliveryFailed = AtomicBoolean(false)
    private val playbackClock = PlaybackClock()
    private val spokenReference = StringBuilder()
    fun recentSpokenText(): String = synchronized(spokenReference) { spokenReference.toString() }
    private fun rememberPlayback(text: String) = synchronized(spokenReference) {
        if (text.isNotBlank()) { spokenReference.append(" ").append(text); onEchoReference(text) }
        if (spokenReference.length > 1600) spokenReference.delete(0, spokenReference.length - 1600)
    }
    @Volatile private var writtenFrames = 0L
    @Volatile private var lastAudibleAt = Long.MIN_VALUE / 2
    private val stoppedPlaybackHead = AtomicLong()

    val isPlayingAudio: Boolean get() = synchronized(playbackLock) {
        val now = System.nanoTime() / 1_000_000
        val audible = !stopped && !interrupted && audioTrack?.let {
            it.playState == AudioTrack.PLAYSTATE_PLAYING && unsignedHead(it) < writtenFrames
        } == true
        if (audible) lastAudibleAt = now
        audible || now - lastAudibleAt < 350 // Speaker/reverberation tail after drain.
    }
    fun queuedPlaybackMs(): Long? = synchronized(playbackLock) {
        audioTrack?.let { track ->
            (writtenFrames - unsignedHead(track)).coerceAtLeast(0) * 1000 / track.sampleRate
        }
    }
    fun hasInterruptionBudget(): Boolean = DuplexPlaybackBudget.allows(
        queuedPlaybackMs(), continuing = false, unavailable = stopped || interrupted)
    fun canContinueInterruption(): Boolean = DuplexPlaybackBudget.allows(
        queuedPlaybackMs(), continuing = true, unavailable = stopped || interrupted)
    private fun applyPause() {
        val paused = interrupted
        playbackClock.setPaused(paused)
        audioTrack?.let { if (paused) it.pause() else if (!stopped) it.play() }
    }

    fun setInterrupted(value: Boolean) = synchronized(playbackLock) {
        if (interrupted == value) return@synchronized
        interrupted = value
        applyPause()
        log("audio_interruption paused=$value playbackHead=${audioTrack?.let(::unsignedHead) ?: 0}")
    }
    private suspend fun awaitPlaybackPermission() {
        while (interrupted && !stopped) delay(25)
        currentCoroutineContext().ensureActive()
    }

    private data class SynthesizedPhrase(
        val index: Int,
        val text: String,
        val sampleRate: Int,
        val pcm: ShortArray,
        val startupWaitMs: Long,
        val captionGroup: Int? = null
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        playbackBoundaryReported.set(false)
        stoppedPlaybackHead.set(0)
        val speechScope = this
        log("tts_session_started engine=${engine.id} modelDir=$modelDirectory speaker=$speakerId threads=$numThreads workers=1")
        var framesWritten = 0
        var outputSampleRate = 0
        var totalSynthesisMs = 0L
        var totalAudioMs = 0L
        var totalQueueWaitMs = 0L
        var phraseCount = 0
        var loadMs = 0L
        var firstPcmMs: Long? = null
        var firstTextToPcmMs: Long? = null
        var firstTextToPlaybackMs: Long? = null
        var piperOpening = 0
        val firstPcmTiming = PiperFirstPcmTiming({ at -> onTiming("first_piper_pcm", at) })
        val firstTextAt = AtomicLong()
        val firstAudibleFrame = AtomicLong(-1)
        var inputChars = 0
        val textHash = java.security.MessageDigest.getInstance("SHA-256")
        val captions = SpokenCaptionTimeline()
        var playbackMonitor: Job? = null
        var playbackConfirmed = false
        var completed = false
        var failureMessage: String? = null
        var finalUnderruns = 0
        val streamingUnderruns = AtomicLong(0)
        val playbackStarvationMs = AtomicLong(0)
        val draining = AtomicBoolean(false)
        val playbackSpeed = 1f
        var estimatedGapMs = 0L
        var sourcePcmSummary: String? = null
        val pcmDelivery = "piper_first_clause_then_passages_max640_v2"
        // One owner creates, invokes and releases the native engine. Playback never owns it.
        // Bounded PCM backpressure prevents long answers from accumulating unlimited audio.
        val audio = NativeAudioQueue<SynthesizedPhrase>(maxQueuedPassages) {
            it.pcm.size * 1000L / it.sampleRate
        }
        val piperText = PiperTextStream()
        log("piper_text_policy version=first-clause-v2 subsequentTargetChars=320 maxChars=640 nativeMaxNumSentences=0 speed=1.0 threads=4")
        val startupReady = CompletableDeferred<Unit>()
        val tokens = Channel<String>(64)
        val collectTokens = launch {
            try {
                chunks.collect { token ->
                    if (token.isNotBlank()) {
                        val at = System.nanoTime()
                        if (firstTextAt.compareAndSet(0, at))
                            runCatching { onTiming("first_piper_text", at / 1_000_000) }
                    }
                    tokens.send(token)
                }
                tokens.close()
            } catch (error: Throwable) { tokens.close(error); throw error }
        }
        val nativeDispatcher = modelSession?.ttsDispatcher ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "jarvis-tts").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val producer = launch(nativeDispatcher) {
            var engine: OfflineTts? = null
            var engineLease: CallModelSlot<OfflineTts>.Lease? = null
            var failure: Throwable? = null
            var index = 0
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val modelKey = "${this@PiperVoiceOutput.engine.id}:$modelDirectory:$numThreads:natural-v1"
                engineLease = modelSession?.tts?.acquire(modelKey) {
                    OfflineTts(config = sherpaTtsConfig(this@PiperVoiceOutput.engine, modelDirectory, numThreads, piperWholePassage = true))
                }
                val tts = engineLease?.value ?: OfflineTts(config =
                    sherpaTtsConfig(this@PiperVoiceOutput.engine, modelDirectory, numThreads, piperWholePassage = true))
                engine = tts
                loadMs = elapsedMs(loadStart)
                log("tts_engine_preload_finished loadMs=$loadMs reused=${engineLease?.reused == true}")
                val generation = GenerationConfig(silenceScale = 1f, sid = speakerId)
                onReady()
                val synthesizer = PiperSpeechSynthesizer(tts, generation, owner, log)
                fun generate(text: String) {
                    owner.ensureActive()
                    if (stopped) return
                    run {
                        check(text.length <= PiperTextStream.MAX_CHARS)
                        log("piper_passage_submit index=$index chars=${text.length} nativeMaxNumSentences=0")
                        if (index == 0) log("piper_opening_wait_ms=${firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)} chars=${text.length}")
                    }
                    val result = synthesizer.synthesize(text)
                    if (stopped) return
                    firstPcmTiming.observe(result.pcm.size)
                    val phraseIndex = index++
                    if (phraseIndex == 0) {
                        piperOpening = text.length
                        firstPcmMs = result.synthesisMs
                        firstTextToPcmMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                        log("tts_opening_ready prepared=false firstTextToPcmMs=$firstTextToPcmMs openingChars=${text.length}")
                    }
                    val rate = result.sampleRate
                    val frames = result.pcm.size.toLong()
                    val waitStart = System.nanoTime()
                    deliveryLedger?.append(phraseIndex, text, result.pcm.size, rate)
                    deliveryLedger?.seal(phraseIndex)
                    audio.sendFromNative(SynthesizedPhrase(phraseIndex, text, rate, result.pcm,
                        PlaybackBufferPolicy.startupWaitMs(result.synthesisMs, frames * 1000 / rate)))
                    if (phraseIndex == 1) startupReady.complete(Unit)
                    val queueWaitMs = elapsedMs(waitStart)
                    val synthesisMs = result.synthesisMs
                    val audioMs = frames * 1000 / rate
                    val rtf = if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0
                    totalSynthesisMs += synthesisMs
                    totalAudioMs += audioMs
                    totalQueueWaitMs += queueWaitMs
                    phraseCount++
                    log("tts_generation_finished index=$phraseIndex synthesisMs=$synthesisMs queueWaitMs=$queueWaitMs " +
                        "audioDurationMs=$audioMs realtimeFactor=$rtf")
                }
                fun nextPhrase(final: Boolean = false): String? = piperText.take(final)
                var ended = false
                while (!ended && !stopped) {
                    owner.ensureActive()
                    select<Unit> {
                        tokens.onReceiveCatching { received ->
                            received.exceptionOrNull()?.let { throw it }
                            val token = received.getOrNull()
                            if (token == null) ended = true
                            else {
                                inputChars += token.length
                                textHash.update(token.toByteArray(Charsets.UTF_8))
                                run {
                                    piperText.append(token)
                                    while (true) {
                                        val queued = tokens.tryReceive()
                                        queued.exceptionOrNull()?.let { throw it }
                                        val more = queued.getOrNull() ?: break
                                        inputChars += more.length
                                        textHash.update(more.toByteArray(Charsets.UTF_8))
                                        piperText.append(more)
                                    }
                                }
                                while (true) generate(nextPhrase() ?: break)
                            }
                        }
                        piperText.openingWaitMs()?.let { remaining ->
                            onTimeout(remaining) {
                                while (true) generate(nextPhrase() ?: break)
                            }
                        }
                    }
                }
                while (true) generate(nextPhrase(final = true) ?: break)
            } catch (error: Throwable) {
                failureMessage = error.message ?: error.javaClass.simpleName
                if (error !is CancellationException) deliveryFailed.set(true)
                failure = error
                throw error
            } finally {
                // Release only after generate has returned, including when cancellation was requested.
                startupReady.complete(Unit)
                // No more PCM can arrive. Publish completion before potentially slow native cleanup.
                audio.close(failure)
                if (engineLease != null) engineLease?.finish(healthy = failure == null && !stopped)
                else engine?.release()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                var first = true
                var lastWriteAt = 0L
                var lastQueuedMs = 0L
                while (true) {
                    val received = audio.chunks.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val phrase = received.getOrNull() ?: break
                    audio.consumed(phrase)
                    ensureActive()
                    if (stopped || phrase.pcm.isEmpty()) continue
                    outputSampleRate = phrase.sampleRate
                    if (first) {
                        // Small startup headroom; never hold a short, completed answer for this delay.
                        val start = System.nanoTime()
                        // Retain the existing Piper startup policy.
                        val startupDeadline = phrase.startupWaitMs
                        val remainingHeadroom = (startupDeadline - elapsedMs(start)).coerceAtLeast(0)
                        if (remainingHeadroom > 0) withTimeoutOrNull(remainingHeadroom) { startupReady.await() }
                        log("audio_startup_buffer targetMs=${phrase.startupWaitMs} pcmTargetMs=0 deadlineMs=$startupDeadline waitMs=${elapsedMs(start)}")
                        first = false
                        if (stopped) break
                    }
                    awaitPlaybackPermission()
                    if (stopped) break
                    val track = audioTrack ?: audioTrackFactory.create(phrase.sampleRate, phrase.pcm.size).also {
                        synchronized(playbackLock) { audioTrack = it }
                        // Native sample-rate playback: no time stretching or experimental pace.
                        synchronized(playbackLock) { if (!interrupted && !stopped) it.play() }
                        val startedTrack = it
                        // A sibling of the IO writer: its infinite loop must not block the writer returning.
                        playbackMonitor = speechScope.launch {
                            var previousUnderruns = startedTrack.underrunCount
                            var previousPoll = System.nanoTime() / 1_000_000
                            var wasStarved = false
                            while (isActive && !stopped) {
                                val head = unsignedHead(startedTrack)
                                val underruns = startedTrack.underrunCount
                                val poll = System.nanoTime() / 1_000_000
                                val starved = !draining.get() && !interrupted && writtenFrames > 0 && head >= writtenFrames
                                if (wasStarved && starved) playbackStarvationMs.addAndGet((poll - previousPoll).coerceAtLeast(0))
                                wasStarved = starved; previousPoll = poll
                                if (underruns > previousUnderruns) {
                                    log("audio_underrun count=$underruns queuedFrames=${(writtenFrames - head).coerceAtLeast(0)}")
                                    if (!draining.get()) streamingUnderruns.addAndGet((underruns - previousUnderruns).toLong())
                                    previousUnderruns = underruns
                                }
                                val audibleFrame = firstAudibleFrame.get()
                                if (audibleFrame >= 0 && head > audibleFrame && !playbackConfirmed) {
                                    playbackConfirmed = true
                                    firstTextToPlaybackMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                                    log("audio_playback_confirmed playbackHead=$head routeType=${startedTrack.routedDevice?.type} " +
                                        "routeId=${startedTrack.routedDevice?.id}")
                                    onChunkStarted("audio")
                                }
                                deliveryLedger?.advance(head.coerceAtMost(writtenFrames))
                                onPlayback(captions.at(head))
                                LiveCallAudioEvidence.event("playback stream=$evidenceStream head=$head written=$writtenFrames state=${startedTrack.playState}", poll)
                                delay(if (playbackConfirmed) 40 else 10)
                            }
                        }
                    }
                    if (lastWriteAt != 0L) {
                        val gap = (elapsedMs(lastWriteAt) - lastQueuedMs).coerceAtLeast(0)
                        estimatedGapMs += gap
                        if (gap > 50) log("audio_supply_gap index=${phrase.index} estimatedMs=$gap")
                    }
                    val queued = (framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)
                    log("audio_phrase_ready index=${phrase.index} pcmFrames=${phrase.pcm.size} " +
                        "queuedBeforeFrames=$queued underruns=${track.underrunCount}")
                    if (firstAudibleFrame.get() < 0) {
                        val audible = phrase.pcm.indexOfFirst { kotlin.math.abs(it.toInt()) >= 64 }
                        if (audible >= 0) firstAudibleFrame.set(framesWritten.toLong() + audible)
                    }
                    rememberPlayback(phrase.text)
                    captions.append(framesWritten.toLong(), phrase.sampleRate, phrase.pcm, phrase.text, phrase.captionGroup)
                    val start = System.nanoTime()
                    var offset = 0
                    var lastWriteProgress = playbackClock.nowMs()
                    while (offset < phrase.pcm.size && !stopped) {
                        ensureActive()
                        awaitPlaybackPermission()
                        if (stopped) break
                        val written = track.write(phrase.pcm, offset, phrase.pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { "AudioTrack rejected voice PCM output: $written" }
                        if (written == 0) {
                            check(playbackClock.nowMs() - lastWriteProgress < 5000) { "AudioTrack stopped accepting speech PCM." }
                            delay(10)
                            continue
                        }
                        lastWriteProgress = playbackClock.nowMs()
                        audioTrace?.append(phrase.pcm, offset, written, phrase.sampleRate)
                        LiveCallAudioEvidence.recordOutput(evidenceStream, phrase.pcm, offset, written, phrase.sampleRate)
                        offset += written
                        framesWritten += written
                        writtenFrames = framesWritten.toLong()
                    }
                    lastWriteAt = System.nanoTime()
                    lastQueuedMs = ((framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0) *
                        1000.0 / phrase.sampleRate / playbackSpeed).toLong()
                    log("audio_phrase_written index=${phrase.index} writeMs=${elapsedMs(start)} " +
                        "queuedAfterFrames=${(framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)} underruns=${track.underrunCount}")
                }
                producer.join()
                if (!stopped && framesWritten > 0) {
                    val track = requireNotNull(audioTrack)
                    // Older Android versions cannot lower the start threshold. A finite
                    // short stream must fill that threshold with silence to start at all.
                    val threshold = if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames else track.bufferSizeInFrames
                    val padding = (threshold - framesWritten).coerceAtLeast(0)
                    if (padding > 0 && unsignedHead(track) == 0L) {
                        log("audio_short_clip_padding frames=$padding")
                        val zeros = ShortArray(padding)
                        var offset = 0
                        val deadline = playbackClock.nowMs() + 3000
                        while (offset < zeros.size && !stopped) {
                            ensureActive()
                            awaitPlaybackPermission()
                            if (stopped) break
                            check(playbackClock.nowMs() < deadline) { "AudioTrack short-clip padding timed out." }
                            val written = track.write(zeros, offset, zeros.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0) { "AudioTrack rejected short-clip padding: $written" }
                            if (written == 0) delay(10) else offset += written
                        }
                    }
                    draining.set(true)
                    completed = drainAudioTrack(framesWritten, outputSampleRate, playbackSpeed)
                    if (completed) reportPlaybackEnded()
                    if (!completed && !stopped) error("AudioTrack playback timed out before all speech was consumed.")
                }
                finalUnderruns = audioTrack?.underrunCount ?: 0
            }
        } catch (error: Throwable) {
            failureMessage = error.message ?: error.javaClass.simpleName
            if (error !is CancellationException) deliveryFailed.set(true)
            throw error
        } finally {
            val wasStopped = stopped
            stopped = true
            // Unblock the native producer waiting on a full queue before joining its native owner.
            reportPlaybackEnded()
            audio.cancel()
            tokens.cancel()
            withContext(NonCancellable) { playbackMonitor?.cancelAndJoin() }
            val playedFrames = synchronized(playbackLock) {
                if (!completed) audioTrack?.let { runCatching { it.pause() } }
                maxOf(audioTrack?.let(::unsignedHead) ?: 0L, stoppedPlaybackHead.get())
            }
            deliveryLedger?.advance(playedFrames.coerceAtMost(writtenFrames),
                if (completed && !wasStopped && failureMessage == null) SpeechDeliveryState.COMPLETED
                else if (deliveryFailed.get()) SpeechDeliveryState.FAILED else SpeechDeliveryState.INTERRUPTED)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { audioTrace?.finish(playedFrames) }
                    .onFailure { log("speech_audio_trace_failed reason=${it.message}") }
            }
            onPlayback(if (wasStopped) VoicePlaybackFrame() else captions.at(playedFrames).copy(level = 0f))
            val outputRoute = audioTrack?.routedDevice?.let { "type=${it.type} id=${it.id}" }
            finalUnderruns = audioTrack?.underrunCount ?: finalUnderruns
            synchronized(playbackLock) {
                audioTrack?.let(audioTrackFactory::releaseAfterWriterStops)
                audioTrack = null
            }
            withContext(NonCancellable) { collectTokens.cancelAndJoin(); producer.cancelAndJoin() }
            if (modelSession == null) nativeDispatcher.close()
            speaking.set(false)
            if (inputChars > 0 || failureMessage != null) runCatching {
                onMetrics(TtsSessionMetrics(loadMs, firstPcmMs, totalSynthesisMs, totalAudioMs,
                    totalQueueWaitMs, playbackSpeed, estimatedGapMs, finalUnderruns, phraseCount,
                    inputChars, textHash.digest().joinToString("") { "%02x".format(it) }, numThreads,
                    completed && !wasStopped && failureMessage == null, failureMessage,
                    playbackConfirmed, playedFrames, framesWritten.toLong(), outputRoute,
                    firstTextToPcmMs, firstTextToPlaybackMs, piperOpening,
                    0L, false, playbackStarvationMs.get(), sourcePcmSummary, pcmDelivery))
            }.onFailure { log("tts_metrics_failed reason=${it.message}") }
            log("tts_session_finished phrases=$phraseCount synthesisMs=$totalSynthesisMs " +
                "audioDurationMs=$totalAudioMs queueWaitMs=$totalQueueWaitMs playbackSpeed=$playbackSpeed " +
                "estimatedSupplyGapMs=$estimatedGapMs observedPlaybackStarvationMs=${playbackStarvationMs.get()} " +
                "realtimeFactor=${if (totalAudioMs > 0) totalSynthesisMs.toDouble() / totalAudioMs else 0.0}")
        }
    }

    override fun stopSpeaking() {
        val wasActive = speaking.get() && !stopped
        stopped = true
        // Never release an AudioTrack while its writer is using it.
        synchronized(playbackLock) {
            audioTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { stoppedPlaybackHead.accumulateAndGet(unsignedHead(track)) { previous, current -> maxOf(previous, current) } }
                deliveryLedger?.advance(stoppedPlaybackHead.get().coerceAtMost(writtenFrames), SpeechDeliveryState.INTERRUPTED)
                LiveCallAudioEvidence.event("playback_stop stream=$evidenceStream head=${stoppedPlaybackHead.get()} written=$writtenFrames")
                runCatching { track.flush() }
            }
            deliveryLedger?.advance(stoppedPlaybackHead.get().coerceAtMost(writtenFrames), SpeechDeliveryState.INTERRUPTED)
        }
        if (wasActive) reportPlaybackEnded()
    }

    fun release() { stopSpeaking() }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun unsignedHead(track: AudioTrack) = track.playbackHeadPosition.toLong() and 0xffffffffL

    private suspend fun drainAudioTrack(framesWritten: Int, sampleRate: Int, speed: Float): Boolean {
        val track = audioTrack ?: return false
        log("audio_track_drain_started frames=$framesWritten sampleRate=$sampleRate state=${track.state} underruns=${track.underrunCount}")
        val drained = PlaybackDrain.await(framesWritten.toLong(), sampleRate, speed,
            head = { unsignedHead(track) }, stopped = { stopped }, nowMs = playbackClock::nowMs)
        log("audio_track_drain_finished playbackHead=${unsignedHead(track)} targetFrames=$framesWritten " +
            "drained=$drained stopped=$stopped underruns=${track.underrunCount}")
        return drained
    }

}
