package com.battlesbudz.jarvis.v2.work

import java.util.concurrent.atomic.AtomicInteger

/** One process admission owner, shared by conversation work and model-file operations. */
internal object ProcessConversationAdmission {
    val activeJobs = AtomicInteger(0)

    /**
     * Process-wide turn sequence guard (M7): the coordinator claims one
     * sequence per admitted turn and fences every teardown path on it.
     * Quarantine retainers increment [activeJobs] without claiming a turn;
     * the two counters are deliberately independent.
     */
    val turnGuard = TurnGuard()

    fun isActive(): Boolean = activeJobs.get() != 0
}
