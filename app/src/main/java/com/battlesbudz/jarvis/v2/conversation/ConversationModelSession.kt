package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngineFactory
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext

/** The voice owner and answer owner share this state under their existing single model lease. */
internal interface ConversationSessionState {
    var engine: LiteRtLmEngine?
    var hasContext: Boolean
    var characters: Int
}

/** Owns native conversation replacement, engine reuse, and persisted bounded summary updates. */
internal class ConversationModelSession(
    private val state: ConversationSessionState,
    val selectedModel: () -> LocalModelSpec,
    /**
     * The single atomic admission mechanism shared with the tool reliability
     * check: a conversation turn acquires the model gate before it marks
     * itself active (see admitConversationTurn), so the check's admission
     * window and the turn's check-and-mark cannot interleave. Reading the
     * gate's state without acquiring it leaves the race where the check
     * closes the idle engine after the read but before the turn starts.
     */
    val tryBeginModelOperation: () -> Boolean,
    val endModelOperation: () -> Unit,
    private val verifyModel: (LocalModelSpec) -> Boolean,
    private val modelPath: (LocalModelSpec) -> String,
    private val cachePath: String,
    private val shortTermContext: ShortTermConversationContext,
    private val persistSummary: (String?) -> Unit
) {
    /** Turn-local guard, installed only under Conversation's existing exclusive admission. */
    var beforeNativeMutation: suspend () -> Unit = {}
    var hasContext: Boolean
        get() = state.hasContext
        set(value) { state.hasContext = value }
    val characters get() = state.characters
    /** Native quarantine guard: a quarantined engine must never be reused until restart. */
    val isNativeQuarantined: Boolean get() = state.engine?.isNativeQuarantined == true
    val residentBackend: ConversationBackend? get() = state.engine?.let(::LiteRtConversationBackend)

    fun verifyIntegrity(): Boolean = verifyModel(selectedModel())
    fun clearContextAccounting() { state.hasContext = false; state.characters = 0 }
    fun addCharacters(count: Int) { state.characters += count }
    fun clearSummary() { shortTermContext.clear() }
    suspend fun close() { beforeNativeMutation(); state.engine?.close(); state.engine = null; clearContextAccounting() }
    suspend fun reset() { beforeNativeMutation(); state.engine?.resetConversation(); clearContextAccounting() }

    suspend fun prepare(imageAttached: Boolean, audioAttached: Boolean, voiceAttached: Boolean,
                        reply: ConversationReply): ConversationBackend {
        check(state.engine?.isNativeQuarantined != true) { "Native model is still quarantined; ownership cannot be reused" }
        if (state.engine?.requiresConfirmedHistoryRebuild == true) reset()
        if (!voiceAttached) {
            // A prior direct-audio child has a fixed system preface. Change modes before
            // prompt assembly so confirmed history is reseeded after replacing that child.
            val resident = state.engine
            if (resident?.hasFixedVoicePreface == true) {
                beforeNativeMutation()
                if (resident.prepareOrdinaryConversation()) clearContextAccounting()
            }
        }
        if (imageAttached) {
            check(selectedModel().supportsVision) {
                "${selectedModel().id} is text-only. Select a model with image input."
            }
            if (state.engine?.visionEnabled != true) close() else reset()
        }
        if (audioAttached) {
            check(selectedModel().supportsAudio) { "This download does not support audio input." }
            if (state.engine?.audioEnabled != true) close() else reset()
        }
        if (voiceAttached && selectedModel().supportsAudio) {
            // Creation below keys audio on (voiceAttached || audioAttached);
            // the resident check must too. Without it, a text-built engine is
            // reused for a direct-audio turn and generateAudio's audio
            // requirement fails that turn instead of rebuilding once here.
            // Escalate only when audio is missing: an audio-capable resident
            // is left untouched, because direct-voice turns deliberately
            // retain their prepared conversation (no drain, no reseed).
            val resident = state.engine
            if (resident != null && !resident.audioEnabled) close()
        }
        val started = System.nanoTime()
        reply.benchmark.mark("model_load_started")
        val wasLoaded = state.engine != null
        val engine = state.engine ?: LiteRtLmEngineFactory.createInitialized(
            selectedModel().id, modelPath(selectedModel()), cachePath,
            useGpu = selectedModel().recommendedGpu,
            tools = if (selectedModel().supportsTools) MobileActionToolDefinitions.all() else emptyList(),
            visionEnabled = imageAttached && selectedModel().supportsVision,
            audioEnabled = (voiceAttached || audioAttached) && selectedModel().supportsAudio
        ).also { state.engine = it; clearContextAccounting() }
        if (!wasLoaded) reply.loadMs += (System.nanoTime() - started) / 1_000_000
        reply.benchmark.mark("model_load_finished")
        reply.benchmark.metric("reply_model_load_ms", reply.loadMs)
        return LiteRtConversationBackend(engine)
    }

    suspend fun prepareHistory(history: List<ChatEntry>, memoryHistoryInvalidated: Boolean,
                               pendingRequestSize: Int, limit: Int): List<ChatEntry> {
        check(state.engine?.isNativeQuarantined != true) { "Native model is still quarantined; ownership cannot be reused" }
        if (state.engine?.requiresConfirmedHistoryRebuild == true) reset()
        var promptHistory = if (memoryHistoryInvalidated) emptyList() else history
        if (state.characters + pendingRequestSize + ConversationPolicy.GENERATION_HEADROOM > limit) {
            val compacted = shortTermContext.compactSnapshot(promptHistory.map { it.role to it.text })
            if (compacted.isNotBlank()) {
                shortTermContext.updateSummary(compacted)
                persistSummary(shortTermContext.summaryForDiagnostics())
            }
            // The new summary contains recent turns; never seed them into native context twice.
            promptHistory = emptyList()
            reset()
        }
        return promptHistory
    }
}
