package com.battlesbudz.jarvis.v2.voice

import java.io.*
import java.security.MessageDigest

/** Persist only short generated filler PCM; a missing/corrupt entry is a cache miss. */
internal class FillerAudioCache(private val directory: File) {
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".pcm")
    fun read(key: String, text: String): SpeechAudio? = runCatching {
        DataInputStream(file(key).inputStream().buffered()).use {
            require(it.readInt() == 0x4a465231)
            val rate = it.readInt(); val count = it.readInt()
            require(rate in 8000..48000 && count in 1..rate * 4)
            val pcm = ShortArray(count) { _ -> it.readShort() }
            require(it.read() == -1)
            FillerPcm.prepare(SpeechAudio(text, rate, pcm, 0))
        }
    }.getOrNull()
    fun write(key: String, audio: SpeechAudio) {
        require(audio.sampleRate in 8000..48000 && audio.pcm.size in 1..audio.sampleRate * 4)
        check(directory.isDirectory || directory.mkdirs())
        val target = file(key); val tmp = File(directory, target.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(0x4a465231); out.writeInt(audio.sampleRate); out.writeInt(audio.pcm.size)
            audio.pcm.forEach { out.writeShort(it.toInt()) }
        }
        check(tmp.renameTo(target))
    }
}
