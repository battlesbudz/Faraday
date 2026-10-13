package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Remove only leading echo. Preserve later corrections, negation and original punctuation. */
object NaturalCorrectionText {
    fun isFloorOnly(text: String): Boolean {
        if (VoiceCallPolicy.isGoodbye(text)) return false
        val words = Regex("[\\p{L}\\p{N}']+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        return words.isNotEmpty() && (words.size == 1 || words.all { it in setOf("no", "yes", "yeah", "yep", "i", "wait", "stop", "mm", "hmm") })
    }

    fun resolve(finalText: String, reference: String): String? {
        val gate = BargeInGate()
        gate.update(true, false, 0, finalText, reference)
        if (gate.update(true, false, 300, finalText, reference) != BargeInGate.Action.CONFIRM) return null
        val tokens = Regex("[\\p{L}\\p{N}']+").findAll(finalText).toList()
        val request = gate.requestText.split(' ')
        for (start in tokens.indices) {
            if (start + request.size > tokens.size) break
            if (request.indices.all { offset -> tokens[start + offset].value.lowercase(Locale.ROOT) == request[offset] }) {
                val tail = finalText.substring(tokens[start].range.first).trim()
                val echo = Regex("[\\p{L}\\p{N}']+").findAll(reference.lowercase(Locale.ROOT))
                    .joinToString(" ") { it.value }
                // Playback captured after the interruption word is not part of the new request.
                return Regex("[^.!?;\\n]+[.!?;\\n]*").findAll(tail).map { it.value.trim() }
                    .filter { clause ->
                        val normalized = Regex("[\\p{L}\\p{N}']+").findAll(clause.lowercase(Locale.ROOT))
                            .joinToString(" ") { it.value }
                        normalized.isNotBlank() && !PlaybackEchoText.resemblesPlayback(normalized, echo) &&
                            !(" $echo ").contains(" $normalized ")
                    }.joinToString(" ").ifBlank { null }
            }
        }
        return null
    }
}
