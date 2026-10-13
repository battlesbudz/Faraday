package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard

/** Exact assembled input and streaming authority transferred from generation to recovery. */
internal data class ConversationAnswer(
    val invocation: ConversationInvocation,
    val routed: RoutedConversation,
    val prepared: PreparedConversation,
    val promptHistory: List<ChatEntry>,
    val turnPrompt: ConversationPrompt,
    val engine: ConversationBackend,
    val reply: ConversationReply,
    val submittedPrompt: String,
    val imageBytes: ByteArray?,
    val directAudio: Boolean,
    val streamFilter: AssistantStreamFilter,
    val repetitionGuard: VoiceRepetitionGuard?,
    val telemetry: ConversationInferenceTelemetry
)

/** One native draft, including whether its resident session still contains this user turn. */
internal class ConversationDraft(
    val answer: ConversationAnswer,
    var generated: GenerationResult,
    var containsCurrentTurn: Boolean,
    var actionName: String? = null,
    var actionResultMessage: String? = null,
    var runawayLoopStopped: Boolean = false
) {
    var rawControlOutput = false
    var cleanedResponse = ""
}
