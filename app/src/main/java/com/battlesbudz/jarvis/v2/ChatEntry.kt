package com.battlesbudz.jarvis.v2

/** A transcript entry shared by UI, context and conversation processing. */
data class ChatEntry(
    val role: String,
    val text: String,
    val imageUri: String? = null,
    val latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null
)

