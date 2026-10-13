package com.battlesbudz.jarvis.v2.runtime

/** One startup barrier for the shared phone/workflow journal; never reruns over live owners. */
internal class DurableTaskRecovery {
    @Volatile var ready: Boolean = false
        private set
    private var attempted = false

    @Synchronized fun recover(phone: () -> Boolean, workflows: () -> Boolean): Boolean {
        if (attempted) return ready
        attempted = true
        val phoneReady = phone()
        val workflowsReady = workflows()
        ready = phoneReady && workflowsReady
        return ready
    }

    fun runWhenReady(action: () -> Unit): Boolean {
        if (!ready) return false
        action()
        return true
    }
}
