package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** One read-only repair, with checked sentences delivered before native generation finishes. */
internal object VoiceRepetitionRepair {
    private class ControlOutput : CancellationException("voice_repair_control_output")
    private class LimitReached : CancellationException("voice_repair_limit")
    data class Outcome(val reason: String, val generation: GenerationResult?)

    suspend fun run(guard: VoiceRepetitionGuard, timeoutMs: Long = 10_000,
                    generate: suspend ((String) -> Unit) -> GenerationResult): Outcome {
        guard.beginRepair()
        val before = guard.acceptedSentences
        var chars = 0
        var streamed = false
        var closed = false
        val protocolTail = StringBuilder()
        val control = Regex("(?i)(tool_call|function_call)")
        fun checkedText(text: String) {
            for (character in text) {
                protocolTail.append(character)
                if (protocolTail.length > 32) protocolTail.deleteCharAt(0)
                if (character == '<' || control.containsMatchIn(protocolTail)) throw ControlOutput()
                guard.accept(character.toString())
                if (guard.acceptedSentences - before >= 2) throw LimitReached()
            }
        }
        fun accept(token: String) {
            if (closed) return
            streamed = streamed || token.isNotEmpty()
            val remaining = 1600 - chars
            val bounded = token.take(remaining)
            chars += bounded.length
            checkedText(bounded)
            if (token.length > remaining) throw LimitReached()
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                val result = generate(::accept)
                if (!streamed && result.toolCalls.isEmpty()) accept(result.text)
                // Only a normally completed response may publish its final unfinished phrase.
                if (result.toolCalls.isEmpty()) guard.finish()
                Outcome("completed", result)
            } ?: Outcome("timeout", null)
        } catch (_: VoiceRepetitionGuard.RunawayLoop) {
            Outcome("runaway_repetition", null)
        } catch (_: ControlOutput) {
            Outcome("control_output", null)
        } catch (_: LimitReached) {
            Outcome("sentence_or_character_limit", null)
        } finally {
            closed = true
            guard.discardPending() // A timeout/Stop cannot leak an unfinished suffix into fallback.
        }
    }
}
