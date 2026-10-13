package com.battlesbudz.jarvis.v2.work

import java.util.concurrent.atomic.AtomicInteger

/** One process admission owner, shared by conversation work and model-file operations. */
internal object ProcessConversationAdmission {
    val activeJobs = AtomicInteger(0)

    fun isActive(): Boolean = activeJobs.get() != 0
}
