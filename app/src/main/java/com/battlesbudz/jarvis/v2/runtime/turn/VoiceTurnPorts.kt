package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.conversation.ConversationCallbacks
import com.battlesbudz.jarvis.v2.conversation.ConversationInvocation
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import kotlinx.coroutines.Job

/** Calls cross the platform/UI boundary through these events, never through the runtime object. */
internal class VoiceCallEvents(
    val post: (() -> Unit) -> Unit,
    val report: (String) -> Unit,
    val serviceStatus: (String) -> Unit,
    val transcript: (String, String, Boolean) -> Unit,
    val finished: (String) -> Unit,
    val startDiagnostics: (String) -> Unit,
    val endCall: () -> Unit,
    val returnToWake: (callId: String) -> Unit,
    val stopService: () -> Unit,
    val restartTurn: () -> Unit
)

/** Shared call authority is distinct from the per-turn model/capture lifetime. */
internal class VoiceCallAccess(
    val state: VoiceCallState,
    val controller: VoiceSessionController,
    val events: VoiceCallEvents
) {
    fun status(message: String) {
        state.latestStatus = message
        if (!com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.interrupted.value) {
            events.serviceStatus(message)
            events.post { events.report(message) }
        }
    }
}

/** Native answer admission returns the exact child job which the call owner must join. */
internal fun interface VoiceConversationDispatch {
    fun start(invocation: ConversationInvocation, callbacks: ConversationCallbacks): Job?
}

internal class VoiceMemoryAccess(
    val fence: MemoryDeliveryFence,
    val delivery: VoiceMemoryDeliveryOwner,
    val capture: (eventId: String, conversationId: String, callId: String?, source: ConversationMemorySource,
                  text: String, capturedAtMs: Long) -> Boolean
)
