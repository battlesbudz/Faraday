package com.battlesbudz.jarvis.v2.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * M2 Android alarm scheduling for workflow occurrences (D34, T13).
 *
 * Reminders target the requested time: when Android grants exact-alarm
 * access the alarm is exact; otherwise the scheduler falls back to an
 * inexact alarm and says so honestly — it never claims exact timing it
 * cannot keep. Fired alarms land in [WorkflowScheduleReceiver], which
 * claims the occurrence atomically so a redelivery can never double-fire.
 */

/** App-private journal file shared by the task ledger and the workflow ledger. */
internal fun workflowJournalFile(context: Context): File =
    File(context.noBackupFilesDir, "phone-action-attempts.json")

class WorkflowAlarmScheduler(private val context: Context) {

    data class Scheduled(val mode: WorkflowScheduling.AlarmMode, val honestNote: String?)

    fun schedule(occurrence: WorkflowOccurrence): Scheduled {
        val alarm = context.getSystemService(AlarmManager::class.java)
            ?: return Scheduled(WorkflowScheduling.AlarmMode.INEXACT_FALLBACK, "Alarm scheduling is unavailable on this device.")
        val intent = Intent(context, WorkflowScheduleReceiver::class.java).apply {
            action = ACTION_WORKFLOW_DUE
            putExtra(EXTRA_OCCURRENCE_ID, occurrence.id)
        }
        val pending = PendingIntent.getBroadcast(context, occurrence.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val schedule = WorkflowScheduling.alarmSchedule(canScheduleExactAlarms(alarm))
        try {
            if (schedule.mode == WorkflowScheduling.AlarmMode.EXACT) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.scheduledForMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.scheduledForMs, pending)
            }
        } catch (_: SecurityException) {
            return Scheduled(WorkflowScheduling.AlarmMode.INEXACT_FALLBACK,
                "Android refused the exact alarm; scheduled inexactly instead.")
        }
        return Scheduled(schedule.mode, schedule.honestNote)
    }

    fun cancel(occurrenceId: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, WorkflowScheduleReceiver::class.java).apply {
            action = ACTION_WORKFLOW_DUE
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        }
        val pending = PendingIntent.getBroadcast(context, occurrenceId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        pending.cancel()
    }

    private fun canScheduleExactAlarms(alarm: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            try { alarm.canScheduleExactAlarms() } catch (_: SecurityException) { false }
        } else {
            // Before Android 12 there is no exact-alarm permission gate.
            true
        }

    companion object {
        const val ACTION_WORKFLOW_DUE = "com.battlesbudz.jarvis.v2.actions.WORKFLOW_DUE"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    }
}
