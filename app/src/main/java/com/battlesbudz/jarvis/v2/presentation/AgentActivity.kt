package com.battlesbudz.jarvis.v2.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle/pose categories only. The public progress sentence is intentionally open-ended. */
enum class AgentActivityKind { CHECKING_REFERENCES, WORKING, ERROR }

/** Ephemeral public metadata. Never include prompts, URLs, source contents or private reasoning. */
data class AgentActivitySnapshot(
    val operationId: Long,
    val conversationId: String,
    val kind: AgentActivityKind,
    val label: String,
)

/**
 * Observation without operation authority. The admitted turn owns a background lease; actual
 * reads temporarily supersede it. Old reads never reappear and stale completion/progress cannot
 * mutate a newer lease. Closing the turn (including cancellation) removes its public progress.
 */
internal class AgentActivityMonitor {
    private val lock = Any()
    private var nextOperationId = 0L
    private var turn: AgentActivitySnapshot? = null
    private var foreground: AgentActivitySnapshot? = null
    private var foregroundTurnId: Long? = null
    private val mutableState = MutableStateFlow<AgentActivitySnapshot?>(null)
    val state: StateFlow<AgentActivitySnapshot?> = mutableState.asStateFlow()

    fun beginReferences(conversationId: String, blurb: String = "Checking references"): Lease =
        begin(conversationId, AgentActivityKind.CHECKING_REFERENCES, blurb)

    /** Freeze reads to the admitted turn even if the user navigates to another conversation. */
    fun beginTurnReferences(fallbackConversationId: String, blurb: String): Lease = synchronized(lock) {
        beginReferences(turn?.conversationId ?: fallbackConversationId, blurb)
    }

    fun beginWork(conversationId: String, blurb: String = "Preparing your request"): Lease =
        begin(conversationId, AgentActivityKind.WORKING, blurb)

    fun beginFailure(conversationId: String): Lease = begin(conversationId,
        AgentActivityKind.ERROR, "Something went wrong")

    fun clearFailure() = synchronized(lock) {
        if (foreground?.kind == AgentActivityKind.ERROR) foreground = null
        publish()
    }

    private fun publish() {
        mutableState.value = foreground?.takeIf { it.kind != AgentActivityKind.ERROR } ?: turn ?: foreground
    }

    private fun begin(conversationId: String, kind: AgentActivityKind, label: String): Lease = synchronized(lock) {
        val id = ++nextOperationId
        val snapshot = AgentActivitySnapshot(id, conversationId, kind, ActivityText.publicBlurb(label))
        if (kind == AgentActivityKind.WORKING) {
            turn = snapshot
            foreground = null
            foregroundTurnId = null
        } else {
            foreground = snapshot
            foregroundTurnId = turn?.operationId
        }
        publish()
        var sequence = 0L
        Lease(update = { publicProgress, eventSequence ->
            synchronized(lock) {
                val current = if (kind == AgentActivityKind.WORKING) turn else foreground
                // Explicit event sequence permits future asynchronous public-progress producers.
                // A lifecycle enum never constrains the sentence, but an old event cannot win.
                if (current?.operationId == id && eventSequence > sequence) {
                    sequence = eventSequence
                    val updated = current.copy(label = ActivityText.publicBlurb(publicProgress))
                    if (kind == AgentActivityKind.WORKING) turn = updated else foreground = updated
                    publish()
                }
            }
        }, finish = {
            synchronized(lock) {
                if (turn?.operationId == id) turn = null
                if (foreground?.operationId == id || foregroundTurnId == id) {
                    foreground = null
                    foregroundTurnId = null
                }
                publish()
            }
        })
    }

    class Lease internal constructor(
        private val update: (String, Long) -> Unit,
        private val finish: () -> Unit
    ) : AutoCloseable {
        /** Only producer-authored public progress; never feed model answer/reasoning tokens here. */
        fun progress(publicBlurb: String, sequence: Long) = update(publicBlurb, sequence)
        override fun close() = finish()
    }
}
