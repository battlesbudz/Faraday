package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.voice.whisper.WhisperDecoder
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Call-owned weights; utterance streams and decoder session IDs still belong to individual turns. */
class VoiceModelSession(log: (String) -> Unit = {}) {
    internal val workScheduler = VoiceWorkScheduler()
    internal val tts = CallModelSlot<OfflineTts>({ it.release() }, { log("tts_$it") })
    internal val whisper = CallModelSlot<WhisperDecoder>({ it.close() }, { log("whisper_$it") })
    internal val ttsDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jarvis-call-tts").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    /** Invoke after the call's capture and output borrowers have joined. */
    fun close() {
        try { tts.close() } finally {
            try { whisper.close() } finally { ttsDispatcher.close() }
        }
    }
}
