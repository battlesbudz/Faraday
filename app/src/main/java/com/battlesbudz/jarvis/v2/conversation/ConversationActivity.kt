package com.battlesbudz.jarvis.v2.conversation

/**
 * A turn-scoped, observation-only public progress sink. Its text is not a lifecycle enum: an
 * orchestrator may publish a task-specific sentence at an actual work boundary. Never connect
 * private reasoning, prompts, raw tool payloads, or an answer token stream to this interface.
 */
internal interface ConversationActivity : AutoCloseable {
    fun progress(publicBlurb: String, sequence: Long)
}
