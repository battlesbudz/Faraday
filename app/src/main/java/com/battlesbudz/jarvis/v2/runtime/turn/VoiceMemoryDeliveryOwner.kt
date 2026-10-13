package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Immutable answer authority; releasing a turn must never revoke a later answer's output. */
internal data class VoiceMemoryBinding(
    val ticket: MemoryDeliveryFence.Ticket,
    val context: MemoryTurnContext,
    val output: PiperVoiceOutput,
    val speechJob: Job?,
    val callId: String,
    val replyId: String,
    val expiryJob: AtomicReference<Job?>
)

/** One global delivery binding; individual turns retain their immutable local binding as well. */
internal class VoiceMemoryDeliveryOwner {
    val binding = AtomicReference<VoiceMemoryBinding?>(null)

    fun revoke(scope: CoroutineScope) {
        binding.getAndSet(null)?.let { answer ->
            answer.speechJob?.cancel()
            answer.expiryJob.getAndSet(null)?.cancel()
            scope.launch { answer.output.stopSpeaking() }
        }
    }
}
