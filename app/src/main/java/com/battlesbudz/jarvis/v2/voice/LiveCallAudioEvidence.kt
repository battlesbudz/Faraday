package com.battlesbudz.jarvis.v2.voice

/** Explicitly armed, bounded RAM evidence. Disk/network writes belong only to user export. */
object LiveCallAudioEvidence {
    data class Export(val call: String, val files: Map<String, ByteArray>, val report: String)
    private data class Chunk(val start: Long, val pcm: ByteArray)
    private class Stream(val rate: Int) {
        val chunks = ArrayDeque<Chunk>()
        var frames = 0L
        var bytes = 0
    }
    private val streams = linkedMapOf<String, Stream>()
    private val events = ArrayDeque<String>()
    private val names = linkedSetOf<String>()
    private var serial = 0L
    private var call = ""
    private var bytes = 0
    private var dropped = false
    @Volatile var armed = false
        private set
    @Volatile var active = false
        private set
    @Synchronized fun arm() { clear(); armed = true }
    @Synchronized fun clear() {
        armed = false; active = false; call = ""; bytes = 0; dropped = false
        streams.clear(); events.clear(); names.clear()
    }
    @Synchronized fun begin(label: String) {
        val requested = armed
        clear()
        if (requested) { active = true; call = label; event("capture_started") }
    }
    @Synchronized fun finish() { if (active) event("capture_finished"); active = false }
    @Synchronized fun newStream(kind: String): String {
        if (!active) return ""
        val name = "$kind-${++serial}"
        if (names.size == 64) names.remove(names.first())
        names.add(name)
        return name
    }
    @Synchronized fun event(text: String, atMs: Long = System.nanoTime() / 1_000_000) {
        if (!active) return
        if (events.size == 1500) { events.removeFirst(); dropped = true }
        events.addLast("atMs=$atMs $text")
    }
    @Synchronized fun record(name: String, pcm: ByteArray, rate: Int, atMs: Long = System.nanoTime() / 1_000_000) {
        if (!active || name.isEmpty() || pcm.isEmpty() || (name != "microphone" && name !in names)) return
        require(rate > 0 && pcm.size % 2 == 0)
        if (pcm.size > 800_000) { dropped = true; return }
        val stream = streams.getOrPut(name) {
            if (streams.size == 16) {
                val oldest = streams.keys.first { it != "microphone" }
                bytes -= streams.remove(oldest)!!.bytes; dropped = true
            }
            Stream(rate)
        }
        check(stream.rate == rate)
        val start = stream.frames
        stream.frames += pcm.size / 2
        stream.chunks.addLast(Chunk(start, pcm.copyOf())); stream.bytes += pcm.size; bytes += pcm.size
        while (stream.bytes > 800_000) {
            val removed = stream.chunks.removeFirst(); stream.bytes -= removed.pcm.size; bytes -= removed.pcm.size; dropped = true
        }
        while (bytes > 4_000_000) {
            val victim = streams.values.first { it.chunks.isNotEmpty() }
            val removed = victim.chunks.removeFirst(); victim.bytes -= removed.pcm.size; bytes -= removed.pcm.size; dropped = true
        }
        event("stream=$name startFrame=$start frames=${pcm.size / 2} rate=$rate", atMs)
    }
    fun recordOutput(name: String, pcm: ShortArray, offset: Int, count: Int, rate: Int) {
        if (!active || name.isEmpty()) return
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val sample = pcm[offset + i].toInt()
            data[i * 2] = sample.toByte(); data[i * 2 + 1] = (sample shr 8).toByte()
        }
        record(name, data, rate)
    }
    @Synchronized fun snapshot(): Export? {
        if (call.isEmpty()) return null
        val files = linkedMapOf<String, ByteArray>()
        val metadata = StringBuilder()
        streams.forEach { (name, stream) ->
            if (stream.chunks.isNotEmpty()) {
                val pcm = ByteArray(stream.bytes)
                var offset = 0
                stream.chunks.forEach { it.pcm.copyInto(pcm, offset); offset += it.pcm.size }
                files["$name.wav"] = WavEncoder.pcm16Mono(pcm, stream.rate)
                metadata.append("file=$name.wav startFrame=${stream.chunks.first().start} endFrame=${stream.frames} rate=${stream.rate}\n")
            }
        }
        return Export(call, files, "call=$call active=$active truncated=$dropped memoryLimitBytes=4000000\n" +
            "capture=opt_in_bounded_sample whole_call_recording=false perStreamLimitBytes=800000 retention=rolling_tail\n" +
            "microphone=post_platform_processing pre_platform_pcm=unavailable\n" +
            "probe=exact_submitted_pcm output=written_reference_not_acoustic_recording\n" +
            "clock=monotonic_ms microphone_time=read_time output_time=write_time\n" +
            "playback_head_events=render_progress_proxy acoustic_alignment=not_calibrated\n" +
            "tone_cues=not_captured output_volume_and_fades=not_applied_to_reference\n" + metadata + events.joinToString("\n"))
    }
}
