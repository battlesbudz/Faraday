package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream

/** Record first. Stop transcribes locally; Send retains the original PCM as an audio attachment. */
internal interface ChatDictation {
    suspend fun record(onStatus: (String) -> Unit): ByteArray
    fun finish()
    suspend fun transcribe(pcm: ByteArray, onStatus: (String) -> Unit): String
}

internal class LocalChatDictation(context: Context) : ChatDictation {
    private val context = context.applicationContext
    @Volatile private var finishRequested = false
    override fun finish() { finishRequested = true }

    override suspend fun record(onStatus: (String) -> Unit): ByteArray = withContext(Dispatchers.Default) {
        check(!VoiceSessionUi.armed.value) { "End the voice call before recording a message." }
        check(MicrophoneHandoff.requestDictation()) { "The microphone is already in use for dictation." }
        val input = AndroidAudioInput(this, audioManager = context.getSystemService(AudioManager::class.java), dictation = true)
        val pcm = ByteArrayOutputStream()
        try {
            onStatus("Preparing microphone…")
            withTimeout(10_000) { input.start() }
            onStatus("Recording · 0 / 30 seconds")
            var lastSecond = 0
            // The existing audio-message contract accepts up to 30 seconds, without truncating a tail.
            withTimeoutOrNull(31_000) {
                input.chunks().takeWhile { chunk ->
                    if (finishRequested) false else {
                        val count = minOf(chunk.size, MAX_PCM_BYTES - pcm.size())
                        pcm.write(chunk, 0, count)
                        val second = pcm.size() / 32_000
                        if (second != lastSecond) { lastSecond = second; onStatus("Recording · $second / 30 seconds") }
                        pcm.size() < MAX_PCM_BYTES
                    }
                }.collect()
            }
            ensureActive()
            pcm.toByteArray().also { check(it.isNotEmpty()) { "No audio recorded. Try again." } }
        } finally {
            try { withContext(NonCancellable) { input.stop() } }
            finally { MicrophoneHandoff.finishDictation(); pcm.reset() }
        }
    }

    override suspend fun transcribe(pcm: ByteArray, onStatus: (String) -> Unit): String = withContext(Dispatchers.Default) {
        require(pcm.isNotEmpty() && pcm.size <= MAX_PCM_BYTES && pcm.size % 2 == 0)
        val hasSpeech = SileroSpeechDetector.create(context.assets).use { it.accept(pcm).isSpeech }
        check(hasSpeech) { "No speech detected. Try recording again." }
        val engine = AsrEngine.selected(context)
        onStatus("Preparing ${engine.label}…")
        val directory = engine.prepare(context, onStatus)
        ensureActive()
        onStatus("Transcribing…")
        // Full-clip recovery avoids Whisper's rolling 25-second window for complete clip recognition.
        val text = engine.createDiagnostic(directory).use { it.recover(pcm) }.trim()
        ensureActive()
        check(text.isNotBlank()) { "No speech detected. Try recording again." }
        text
    }

    companion object { private const val MAX_PCM_BYTES = 30 * 16_000 * 2 }
}
