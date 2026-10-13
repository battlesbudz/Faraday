package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable sealed PCM binding. It conveys no ASR, action or native admission authority. */
class VoiceCaptureHandoff internal constructor(
    val callId: String,
    val conversationId: String,
    val parentTurnId: String,
    private val utteranceId: String,
    wav: ByteArray,
) {
    private val claimed = AtomicBoolean(false)
    private val byteCount = wav.size
    private val digest = MessageDigest.getInstance("SHA-256").digest(wav)

    internal fun claim(callId: String, conversationId: String, input: CapturedVoiceTurn): Boolean =
        this.callId == callId && this.conversationId == conversationId && utteranceId == input.utteranceId &&
            input.wav.size == byteCount && MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(input.wav)) &&
            claimed.compareAndSet(false, true)
}
