package com.battlesbudz.jarvis.v2.memory

/** Serialized publication gate: a mutation cannot race a queued ordinary-answer callback. */
class MemoryDeliveryFence {
    class Ticket internal constructor(internal val generation: Long, internal val expiresAtMs: Long?)
    private val lock = Any()
    private var generation = 0L
    fun ticket(expiresAtMs: Long? = null): Ticket = synchronized(lock) { Ticket(generation, expiresAtMs) }
    fun isValid(ticket: Ticket, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        generation == ticket.generation && (ticket.expiresAtMs == null || nowMs < ticket.expiresAtMs)
    }
    fun publish(ticket: Ticket, stillValid: () -> Boolean = { true }, block: () -> Unit): Boolean = synchronized(lock) {
        if (!isValid(ticket) || !stillValid()) false else { block(); true }
    }
    fun invalidate() = synchronized(lock) { generation++ }
}

/**
 * Retains only immutable publication authority after an answer releases audio/model resources.
 * A previously bound answer fails closed if its ticket was invalidated; unbound safe terminals
 * remain publishable.
 */
class MemoryPublicationGuard(private val fence: MemoryDeliveryFence) {
    private data class Bound(val ticket: MemoryDeliveryFence.Ticket, val current: () -> Boolean)
    private val bound = java.util.concurrent.atomic.AtomicReference<Bound?>(null)
    private val wasBound = java.util.concurrent.atomic.AtomicBoolean(false)

    fun bind(ticket: MemoryDeliveryFence.Ticket, current: () -> Boolean) {
        // Publish immutable state before the bound marker; callbacks never observe an empty
        // bound answer as safe/unbound.
        bound.set(Bound(ticket, current))
        wasBound.set(true)
    }
    fun publish(block: () -> Unit): Boolean {
        // Do not hold this guard while entering the fence. Token delivery can already hold the
        // fence while it reaches voice publication, so lock nesting would deadlock Main/IO.
        val snapshot = bound.get()
        if (!wasBound.get()) { block(); return true }
        val active = snapshot ?: return false
        return fence.publish(active.ticket, active.current, block)
    }
}
