package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioManager

/** Filler, answer and cues follow the same call-owned Android communication route. */
internal object CallAudioRouting {
    val usage: Int get() = if (CommunicationAudioSession.ownsMode())
        AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
    val stream: Int get() = if (CommunicationAudioSession.ownsMode())
        AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
}
