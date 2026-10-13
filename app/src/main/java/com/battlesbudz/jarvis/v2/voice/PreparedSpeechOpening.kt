package com.battlesbudz.jarvis.v2.voice

/** One bounded PCM opening. Creation/synthesis gives no permission to play it. */
class PreparedSpeechOpening(val text: String) {
    private var discarded = false
    private var authorized = false
    private var audio: SpeechAudio? = null

    @Synchronized fun isDiscarded() = discarded
    @Synchronized internal fun complete(value: SpeechAudio) {
        if (!discarded && value.text == text && value.sampleRate > 0 &&
            value.pcm.size <= value.sampleRate * 12) audio = value
    }
    @Synchronized internal fun authorize() { if (!discarded) authorized = true }
    @Synchronized internal fun takeFor(finalOpening: String): SpeechAudio? {
        if (discarded || !authorized || finalOpening != text) return null
        return audio.also { audio = null; discarded = true }
    }
    @Synchronized fun discard() { discarded = true; authorized = false; audio = null }
}

internal data class SpeechAudio(val text: String, val sampleRate: Int, val pcm: ShortArray, val synthesisMs: Long)
