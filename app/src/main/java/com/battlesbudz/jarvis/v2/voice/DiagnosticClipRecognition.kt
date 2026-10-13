package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Full bounded diagnostic clip; the interruption probe API is limited to four seconds. */
internal suspend fun recognizeDiagnosticClip(transcriber: StreamingTranscriber, pcm: ByteArray): String {
    try {
        require(pcm.isNotEmpty() && pcm.size <= 12 * 32000 && pcm.size % 2 == 0)
        for (offset in pcm.indices step 3200) {
            currentCoroutineContext().ensureActive()
            transcriber.accept(pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size)), allowPartial = false)
        }
        currentCoroutineContext().ensureActive()
        return transcriber.finish()
    } finally { transcriber.close() }
}
