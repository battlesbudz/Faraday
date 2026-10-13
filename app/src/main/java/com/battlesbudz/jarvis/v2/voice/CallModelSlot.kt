package com.battlesbudz.jarvis.v2.voice

/** One resident resource with one exclusive borrower. Close never destroys an in-flight resource. */
class CallModelSlot<T : Any>(private val release: (T) -> Unit, private val log: (String) -> Unit = {}) {
    private var resource: T? = null
    private var key: String? = null
    private var borrowed = false
    private var closed = false
    private var uses = 0
    inner class Lease internal constructor(val value: T, val reused: Boolean) {
        private var returned = false
        fun finish(healthy: Boolean = true) = synchronized(this@CallModelSlot) {
            if (returned) return@synchronized
            returned = true
            borrowed = false
            log("model_return healthy=$healthy closing=$closed")
            if (!healthy || closed) discard()
        }
    }
    /** Optional work may borrow warm weights, but must not force a load or rotation. */
    @Synchronized fun canReuse(requestKey: String, maxUses: Int): Boolean =
        !closed && !borrowed && resource != null && key == requestKey && uses < maxUses

    /** Atomic admission for optional probes: never load, rotate, or compete with a command. */
    @Synchronized fun acquireWarm(requestKey: String): Lease {
        check(canReuse(requestKey, Int.MAX_VALUE)) { "probe_model_not_warm" }
        borrowed = true
        uses++
        log("model_acquired reused=true uses=$uses probe=true")
        return Lease(requireNotNull(resource), true)
    }

    @Synchronized fun acquire(requestKey: String, maxUses: Int = Int.MAX_VALUE, requiredUses: Int = 1, fresh: Boolean = false, create: () -> T): Lease {
        check(!closed && !borrowed) { "Call model is closed or already in use." }
        require(maxUses > 0 && requiredUses in 1..maxUses)
        if (resource != null && (fresh || key != requestKey || uses > maxUses - requiredUses)) {
            log("model_rotation reason=${if (fresh) "fresh_command" else if (key != requestKey) "configuration_changed" else if (uses >= maxUses) "bounded_sdk_cache" else "reserved_reply_capacity"} uses=$uses")
            discard()
        }
        val reused = resource != null
        val value = resource ?: create().also { resource = it; key = requestKey }
        borrowed = true
        uses++
        log("model_acquired reused=$reused uses=$uses")
        return Lease(value, reused)
    }
    @Synchronized fun close() {
        closed = true
        if (!borrowed) discard()
    }
    private fun discard() {
        val old = resource
        resource = null; key = null; uses = 0
        if (old != null) { release(old); log("model_released") }
    }
}
