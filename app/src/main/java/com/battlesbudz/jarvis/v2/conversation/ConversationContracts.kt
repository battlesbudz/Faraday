package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison

/** Immutable accepted input; the call owner retains the model lease until its returned job joins. */
internal data class ConversationInvocation(
    val prompt: String,
    val history: List<ChatEntry>,
    val imageUri: Uri? = null,
    val incrementalVoice: IncrementalVoiceInput? = null,
    val voiceAudio: ByteArray? = null,
    val voiceAudioIsComplete: Boolean = true,
    val directVoiceAudio: Boolean = false,
    val replyIdentity: String? = null,
    val conversationIdentity: String? = null,
    val callIdentity: String? = null,
    val comparison: LiveComparison.Trial? = null,
    val audioUri: Uri? = null,
    val frozenActionPlan: ActionTurnPlan.Ready? = null,
    val frozenVoiceFinal: Boolean = false,
    val callOwned: Boolean = false,
    val benchmarkCapture: PipelineBenchmarkCapture? = null,
    /** Immutable, complete native encoder result; never provisional action authority. */
    val sealedVoiceAudio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings? = null,
    /** Candidate output is still held until exact final prompt/input and ordinary routing match. */
    val nativeSpeculation: com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation? = null
)

internal data class ConversationCallbacks(
    val onToken: (String) -> Unit,
    val onComplete: (String) -> Unit,
    val onLatency: (TurnLatency) -> Unit = {},
    val onLiveInference: (Long?, Long?, Double?, Boolean) -> Unit = { _, _, _, _ -> },
    val onActionResult: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onPhonePlanFinished: (ActionTurnRunner.Outcome) -> Unit = {},
    val onMemoryBound: (MemoryDeliveryFence.Ticket, MemoryTurnContext) -> Unit = { _, _ -> }
)

/** Approved-memory authority only; no tools, Android state or model leases are exposed here. */
internal interface ConversationMemoryAccess {
    val deliveryFence: MemoryDeliveryFence
    fun approvedSnapshot(query: String, maxChars: Int): MemoryTurnContext?
    fun adopt(context: MemoryTurnContext): Boolean
    fun clearNativeToken()
    fun isCurrent(context: MemoryTurnContext): Boolean
    fun consumeHistoryCutoff(): Boolean
    fun takeCaptureReceipt(prompt: String): ConversationMemoryResult?
}

/** Reference evidence and answer-gap policy; generated text never authorizes a phone action. */
internal interface ConversationReferences {
    suspend fun fetch(query: String): String?
    fun isInsufficientAnswer(answer: String): Boolean
}

internal class ConversationDiagnostics(
    val record: (String) -> Unit,
    val important: (String) -> Unit,
    val summary: (String) -> Unit,
    val inferencePrompt: (String) -> Unit
)
