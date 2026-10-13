package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** Serializes a call-owned typed invocation and joins its exact native child before restart. */
class CallTurnOwner {
    private var activeId: String? = null
    private var completedId: String? = null

    @Synchronized fun begin(inputId: String): Boolean {
        if (activeId != null) return false
        activeId = inputId
        completedId = null
        return true
    }

    suspend fun finish(inputId: String, invocation: Job?, releaseLease: () -> Unit): Boolean =
        withContext(NonCancellable) {
            invocation?.cancelAndJoin()
            releaseLease()
            synchronized(this@CallTurnOwner) {
                if (activeId != inputId) false else {
                    activeId = null
                    completedId = inputId
                    true
                }
            }
        }

    /** Invoke only from the outer owner's completion callback, never from its finally block. */
    @Synchronized fun restartAfterOwnerCompletion(inputId: String, restart: () -> Unit) {
        if (completedId == inputId) {
            completedId = null
            restart()
        }
    }

    @Synchronized fun isActive(): Boolean = activeId != null
}

/** Exact lease handoff for an input whose atomic promotion has not yet admitted work. */
class CallPromotionLease {
    private var owned = false
    @Synchronized fun transfer(transfer: () -> Boolean): Boolean {
        if (owned) return true
        if (!transfer()) return false
        owned = true
        return true
    }
    @Synchronized fun markAdmitted() { owned = false }
    @Synchronized fun releaseIfUnadmitted(release: () -> Unit): Boolean {
        if (!owned) return false
        owned = false
        release()
        return true
    }
    @Synchronized fun isOwned(): Boolean = owned
}
