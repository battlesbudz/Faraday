package com.battlesbudz.jarvis.v2.actions

/**
 * One alarm delivery, claim to terminal checkpoint, on the caller's thread.
 * JVM-pure: it owns no dispatcher, scheduler or storage, so the Android
 * [com.battlesbudz.jarvis.v2.runtime.WorkflowCoordinator] can run it inline
 * on a background coroutine and finish its BroadcastReceiver PendingResult
 * only when this returns.
 */
class WorkflowAlarmRunner(
    private val claimDue: (String) -> WorkflowOccurrence?,
    private val claimResume: (String) -> WorkflowOccurrence?,
    private val runOccurrence: (WorkflowOccurrence, Boolean) -> Unit
) {
    /**
     * Claim -> run -> terminal checkpoint / resume re-arm for one alarm
     * delivery. Returns only after the terminal work completes, so a
     * BroadcastReceiver can safely finish its PendingResult when this
     * returns. Redeliveries find the occurrence already claimed and do
     * nothing - triggers never double-fire. Never throws.
     */
    fun onAlarm(occurrenceId: String) {
        val claimed = try { claimDue(occurrenceId) } catch (_: Exception) { null }
        if (claimed != null) { runQuietly { runOccurrence(claimed, false) }; return }
        val resumed = try { claimResume(occurrenceId) } catch (_: Exception) { null }
        if (resumed != null) runQuietly { runOccurrence(resumed, true) }
    }

    private inline fun runQuietly(block: () -> Unit) { try { block() } catch (_: Exception) { } }
}
