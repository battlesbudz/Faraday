package com.battlesbudz.jarvis.v2.voice

/** One native owner; segment boundaries are internal and never complete a conversational turn. */
class SegmentedTranscriber(
    private val create: () -> StreamingTranscriber,
    private val log: (String) -> Unit = {}
) : StreamingTranscriber {
    private var current: StreamingTranscriber? = create()
    private val text = UtteranceAccumulator()
    private val overlap = RollingAudioBuffer(maxDurationMs = 1200)
    private var segmentBytes = 0L
    private var receivedBytes = 0L
    private var engineInputStartSample = 0L
    private var cueEngine: StreamingTranscriber? = null
    private var cueRevision = Long.MIN_VALUE
    private var endpointCueSequence = 0L
    private var mappedCue: CompletedEndpointCue? = null
    override val completedEndpointCue: CompletedEndpointCue? get() {
        // A hard-overlap seam can suppress a new suffix. Never assign its new audio
        // watermark to old committed words; segmented turns retain ordinary endpointing.
        if (segments > 0) return null
        val engine = current ?: return mappedCue
        val cue = engine.completedEndpointCue ?: return mappedCue
        if (engine !== cueEngine || cue.revision != cueRevision) {
            cueEngine = engine; cueRevision = cue.revision
            mappedCue = CompletedEndpointCue(++endpointCueSequence, text.partial(cue.text),
                engineInputStartSample + cue.coveredAudioSamples)
        }
        return mappedCue
    }
    private var speech = false
    private var silenceBytes = 0L
    private var segmentHadSpeech = false
    private var replay = byteArrayOf()
    private var closed = false
    private var sealed = false
    private var captionRetired = false
    private var last = ""
    private var finalSegment = ""
    private val workLedger = AsrRecognitionWorkLedger()
    override val recognitionWorkMetrics get() = workLedger.withActive(current?.recognitionWorkMetrics, current != null)
    val segments get() = text.segments
    val issue get() = text.issue
    override val noTextSilenceMs get() = current?.noTextSilenceMs ?: 3000L
    override fun observeSpeech(speech: Boolean) {
        this.speech = speech
        if (speech) segmentHadSpeech = true
        current?.observeSpeech(speech)
    }
    override fun accept(pcm: ByteArray): String = accept(pcm, true)
    override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
        check(!closed && !sealed)
        val engine = current ?: create().also {
            current = it
            engineInputStartSample = (receivedBytes - replay.size) / 2
            if (replay.isNotEmpty()) { it.observeSpeech(true); it.accept(replay, allowPartial) }
            segmentBytes = replay.size.toLong(); replay = byteArrayOf()
            it.observeSpeech(speech)
        }
        receivedBytes += pcm.size
        overlap.append(pcm)
        segmentBytes += pcm.size
        silenceBytes = if (speech) 0 else silenceBytes + pcm.size
        val partial = engine.accept(pcm, allowPartial)
        last = text.partial(partial)
        val quietBoundary = segmentBytes >= engine.segmentSoftLimitMs * 32 && silenceBytes >= 6400
        val forcedBoundary = segmentBytes >= engine.segmentHardLimitMs * 32
        if (segmentHadSpeech && (quietBoundary || forcedBoundary)) {
            val finalized = engine.finish()
            val hard = !quietBoundary
            text.commit(finalized, nextOverlaps = hard)
            current = null
            engine.close() // Release before constructing the next stream, including Whisper workers.
            workLedger.retain(engine.recognitionWorkMetrics)
            replay = if (hard) overlap.snapshot() else byteArrayOf()
            overlap.clear(); silenceBytes = 0; segmentHadSpeech = false
            last = text.partial("")
            log("asr_segment_committed index=$segments chars=${last.length} overlapMs=${replay.size / 32} " +
                "softLimitMs=${engine.segmentSoftLimitMs} hardLimitMs=${engine.segmentHardLimitMs} " +
                "issue=$issue turnComplete=false")
        }
        return last
    }
    override fun retireIdleCaption(): Boolean {
        // A segmented/uncertain request keeps complete final ASR and recovery behavior.
        if (closed || sealed || segments != 0 || issue != null) return false
        if (current?.retireIdleCaption() != true) return false
        sealed = true
        captionRetired = true
        last = ""
        finalSegment = ""
        return true
    }
    override fun finish(): String {
        check(!closed)
        if (!sealed) {
            sealed = true
            last = if (current == null) last else {
                finalSegment = current!!.finish()
                if (segments == 0 && TranscriptContent.isSoundOnly(finalSegment)) finalSegment
                else text.finish(finalSegment, speechExpected = segments > 0 && segmentHadSpeech)
            }
        }
        return last
    }
    override fun recover(pcm: ByteArray): String {
        check(sealed && !closed)
        // Caller must own the complete <=25 s recording. A truncated rolling tail
        // must never replace the full request or authorize a partial command.
        require(pcm.size <= 25 * 32_000)
        return current?.recover(pcm).orEmpty()
    }
    fun resumeAfterEndpoint() {
        check(sealed && !closed)
        val owned = current
        if (captionRetired) {
            // New speech invalidated the proposal during acoustic drain. There was no
            // final ASR segment to commit: keep the original recognizer and full PCM.
            check(owned?.resumeRetiredCaption() == true) { "Retired caption owner cannot resume." }
            captionRetired = false
            sealed = false
            return
        }
        if (owned != null) {
            text.commit(finalSegment, nextOverlaps = false)
            current = null
            owned.close()
            workLedger.retain(owned.recognitionWorkMetrics)
        }
        sealed = false; replay = byteArrayOf(); segmentBytes = 0
        segmentHadSpeech = false; silenceBytes = 0; overlap.clear()
    }
    override fun close() {
        if (closed) return
        closed = true
        val owned = current; current = null
        try { owned?.close() }
        finally {
            if (owned != null) workLedger.retain(owned.recognitionWorkMetrics)
            overlap.clear(); replay = byteArrayOf()
        }
    }
}
