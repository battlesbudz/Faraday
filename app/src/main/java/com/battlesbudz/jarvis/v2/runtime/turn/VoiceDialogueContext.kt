package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy

/** Owns call-bound context reset, separate from the microphone and native operation lifetime. */
internal class VoiceDialogueContext(
    private val shortTermContext: ShortTermConversationContext,
    private val turnOrchestrator: TurnOrchestrator,
    private val preferences: SharedPreferences,
    private val recordDiagnostic: (String) -> Unit
) {
    private var contextCallId: String? = null
    fun enterCall(callId: String) {
        if (contextCallId == callId) return
        shortTermContext.clear()
        turnOrchestrator.reset()
        preferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
        contextCallId = callId
        recordDiagnostic("Voice context boundary: call=$callId summary=cleared subject=cleared nativeConversation=fresh")
    }
    fun resetForComparison() { shortTermContext.clear(); turnOrchestrator.reset() }
    fun summaryCharacters(): Int = shortTermContext.summaryForDiagnostics()?.length ?: 0
}
