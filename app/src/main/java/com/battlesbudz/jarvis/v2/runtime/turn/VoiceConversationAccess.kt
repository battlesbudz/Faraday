package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.conversation.ConversationCallbacks
import com.battlesbudz.jarvis.v2.conversation.ConversationInvocation
import kotlinx.coroutines.Job

/** Narrow admission/reset/job port; the process coordinator keeps the resident native engine. */
internal class VoiceConversationAccess(
    private val dispatch: VoiceConversationDispatch,
    private val currentJob: () -> Job?,
    private val resetConversation: suspend () -> Unit,
    val previewNativeAudioPrompt: () -> com.battlesbudz.jarvis.v2.voice.NativeVoicePromptPreview? = { null }
) {
    val job get() = currentJob()
    fun start(invocation: ConversationInvocation, callbacks: ConversationCallbacks): Job? = dispatch.start(invocation, callbacks)
    suspend fun reset() = resetConversation()
}
