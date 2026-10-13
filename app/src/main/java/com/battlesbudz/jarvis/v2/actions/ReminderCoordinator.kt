package com.battlesbudz.jarvis.v2.actions

/**
 * Scheduling bridge behind the `create_reminder` / `show_schedule` tools.
 * The executor calls this; the runtime implements it over the M2
 * [WorkflowLedger]. Keeping the interface narrow lets JVM and release
 * journeys drive the real coordinator against a scratch store.
 */
interface ReminderScheduling {
    fun createReminder(message: String, atMs: Long): ExecutionResult
    fun describeSchedule(): ExecutionResult
}

/**
 * Real schedule creation through the M2 workflow engine: the reminder becomes
 * a versioned definition with a `post_notification` step and a Reminder
 * trigger, saved as a draft and immediately enabled (the user's explicit
 * "remind me" is the enable authority for a one-shot reminder, not a
 * reusable routine). The first scheduled occurrence gets an Android alarm
 * via [alarmScheduler].
 *
 * Receipt-gated replies: every path returns an [ExecutionResult] whose
 * message describes exactly what happened. Success is reported only when
 * the ledger write and the occurrence both exist; every failure says plainly
 * that nothing was set. The deterministic turn path finishes with the
 * outcome message, so the assistant can never claim "reminder set" on a
 * failed write.
 */
class ReminderCoordinator(
    private val ledger: WorkflowLedger,
    private val alarmScheduler: (WorkflowOccurrence) -> WorkflowAlarmScheduler.Scheduled,
    private val now: () -> Long = { System.currentTimeMillis() }
) : ReminderScheduling {

    override fun createReminder(message: String, atMs: Long): ExecutionResult {
        val at = now()
        val clean = message.trim()
        if (clean.isEmpty() || clean.length > MAX_REMINDER_MESSAGE) {
            return ExecutionResult(false, "I couldn't set that reminder: the message was empty or too long.")
        }
        if (atMs <= at) {
            return ExecutionResult(false, "That time has already passed, so I didn't set a reminder.")
        }
        val definition = try {
            buildReminderWorkflow(clean, atMs, at)
        } catch (e: IllegalArgumentException) {
            return ExecutionResult(false, "I couldn't set that reminder: ${e.message}")
        }
        return try {
            val saved = ledger.saveDraft(definition)
            val occurrence = ledger.enable(saved.id).firstOrNull()
                ?: return ExecutionResult(false, "I couldn't schedule that reminder, so nothing was set.")
            val scheduled = try {
                alarmScheduler(occurrence)
            } catch (e: Exception) {
                return ExecutionResult(false,
                    "I saved the reminder but couldn't arm its alarm, so it may not fire: ${e.message}")
            }
            val note = scheduled.honestNote?.let { " $it" }.orEmpty()
            ExecutionResult(true, "Reminder set for ${formatReminderTime(atMs, at)}: $clean.$note")
        } catch (_: ToolTaskStorageException) {
            ExecutionResult(false, "I couldn't save that reminder, so nothing was scheduled.")
        }
    }

    override fun describeSchedule(): ExecutionResult {
        return try {
            val definitions = ledger.list().associateBy { it.id }
            val upcoming = definitions.keys
                .flatMap { ledger.occurrencesFor(it) }
                .filter { it.state in WorkflowLedger.UNFINISHED_OCCURRENCE_STATES }
                .sortedBy { it.scheduledForMs }
            ExecutionResult(true, renderSchedule(upcoming, definitions, now()))
        } catch (_: ToolTaskStorageException) {
            ExecutionResult(false, "I couldn't read the schedule right now.")
        }
    }
}
