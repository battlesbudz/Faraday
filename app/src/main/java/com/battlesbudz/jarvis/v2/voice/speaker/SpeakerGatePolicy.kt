package com.battlesbudz.jarvis.v2.voice.speaker

import kotlin.math.sqrt

enum class SpeakerState { NOT_ENROLLED, MATCH, NO_MATCH, UNVERIFIABLE }
data class SpeakerDecision(
    val state: SpeakerState, val score: Float?, val chatThreshold: Float = SpeakerGatePolicy.CHAT_THRESHOLD,
    val actionThreshold: Float = SpeakerGatePolicy.ACTION_THRESHOLD, val admitted: Boolean, val actionEligible: Boolean
)

object SpeakerGatePolicy {
    const val CHAT_THRESHOLD = 0.50f
    const val ACTION_THRESHOLD = 0.60f
    const val MIN_SPEECH_MS = 800L

    fun decide(enrolled: Boolean, score: Float?, speechMs: Long): SpeakerDecision {
        if (!enrolled) return SpeakerDecision(SpeakerState.NOT_ENROLLED, null, admitted = true, actionEligible = true)
        if (speechMs < MIN_SPEECH_MS || score == null || !score.isFinite())
            return SpeakerDecision(SpeakerState.UNVERIFIABLE, score, admitted = true, actionEligible = false)
        val match = score >= CHAT_THRESHOLD
        return SpeakerDecision(if (match) SpeakerState.MATCH else SpeakerState.NO_MATCH, score,
            admitted = match, actionEligible = match && score >= ACTION_THRESHOLD)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float? {
        if (a.isEmpty() || a.size != b.size || a.any { !it.isFinite() } || b.any { !it.isFinite() }) return null
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        if (aa <= 0.0 || bb <= 0.0) return null
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat().coerceIn(-1f, 1f)
    }
}
