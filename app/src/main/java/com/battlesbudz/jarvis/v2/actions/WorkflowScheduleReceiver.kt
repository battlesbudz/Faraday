package com.battlesbudz.jarvis.v2.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.battlesbudz.jarvis.v2.JarvisRuntime

/**
 * M2 schedule receiver (D27/D63, T13).
 *
 * - Alarm fire: claims the occurrence atomically (SCHEDULED→RUNNING) and
 *   hands it to the runtime. A redelivered alarm finds the occurrence
 *   already claimed and does nothing — triggers never double-fire.
 * - Reboot: recovers the journal (interrupted runs are marked failed, never
 *   re-fired) and re-arms future occurrences from the ledger.
 * - Timezone/clock change: recomputes future occurrences from their trigger
 *   slots in the new zone instead of shifting wall-clock intent.
 *
 * Missed-run evaluation needs the local planner's circumstances, so
 * past-due occurrences are left for [JarvisRuntime.evaluateMissedWorkflowRuns]
 * when the app runs — the receiver never auto-runs a stale occurrence and
 * never creates a catch-up duplicate storm.
 */
class WorkflowScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WorkflowAlarmScheduler.ACTION_WORKFLOW_DUE -> {
                val occurrenceId = intent.getStringExtra(WorkflowAlarmScheduler.EXTRA_OCCURRENCE_ID)
                    ?: return
                val pending = goAsync()
                try {
                    val runtime = JarvisRuntime.get(context)
                    runtime.onWorkflowAlarm(occurrenceId) { pending.finish() }
                } catch (_: Exception) {
                    pending.finish()
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val pending = goAsync()
                try {
                    rescheduleFromLedger(context)
                } catch (_: Exception) {
                    // A failed reschedule must not crash the broadcast; the
                    // runtime re-evaluates on its next launch.
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun rescheduleFromLedger(context: Context) {
        // Runtime construction performs recovery once. Clock/locale changes in an already
        // live process must not reclassify an actively running occurrence as interrupted.
        if (!JarvisRuntime.get(context).taskRecoveryReady) return
        val store = FileToolTaskStore(workflowJournalFile(context))
        val ledger = WorkflowLedger(store)
        val scheduler = WorkflowAlarmScheduler(context)
        val now = System.currentTimeMillis()
        val journal = try { store.readJournal() } catch (_: Exception) { return }
        for (occurrence in journal.occurrences) {
            if (occurrence.state != WorkflowOccurrenceState.SCHEDULED) continue
            if (occurrence.scheduledForMs <= now) continue // left for missed-run evaluation
            try { scheduler.schedule(occurrence) } catch (_: Exception) { /* next launch retries */ }
        }
        // Finding 4 (timer continuation resume): a WAITING_EVENT occurrence
        // with an armed resume time lost its alarm on reboot. Future resumes
        // are re-armed; a resume whose time already passed is reported missed
        // with an honest receipt — never auto-run (no catch-up storm).
        val timerResumes = try { ledger.timerResumes() } catch (_: Exception) { return }
        for (resume in timerResumes) {
            val fireAt = resume.resumeAtMs ?: continue
            if (fireAt <= now) {
                try {
                    ledger.recordMissedEvaluation(resume.id, MissedRunDecision.Irrelevant(
                        "The timer resume passed while the phone was off, so the routine did not continue."))
                } catch (_: Exception) { }
                continue
            }
            try { scheduler.schedule(resume.copy(scheduledForMs = fireAt, windowEndMs = fireAt)) }
            catch (_: Exception) { /* next launch retries */ }
        }
    }
}
