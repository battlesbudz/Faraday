package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.first

/** Never hands passive audio to the call pipeline, including on cancellation or mic loss. */
object WakeWordGate {
    suspend fun await(input: AudioInput, detectsWake: (ByteArray) -> Boolean) {
        input.chunks().first { detectsWake(it) }
    }
}
