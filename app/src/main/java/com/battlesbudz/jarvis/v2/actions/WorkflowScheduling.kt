package com.battlesbudz.jarvis.v2.actions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * M2 scheduling policy (D33/D34, T13/T14). JVM-pure: the runtime supplies
 * the clock and enforces the decisions through AlarmManager.
 *
 * - Reminders fire at the requested time; flexible routines use windows.
 * - Timezone/DST changes recompute future occurrences from their trigger
 *   slots instead of shifting wall-clock intent.
 * - Occurrence dedup keys make every trigger idempotent across restarts:
 *   a reboot never replays a trigger and missed runs never cause a
 *   catch-up duplicate storm.
 */
object WorkflowScheduling {

    fun systemZone(): ZoneId = ZoneId.systemDefault()

    /** Next fire for a daily trigger strictly after [nowMs], in [zone]. */
    fun nextDailyFire(trigger: WorkflowTrigger.Daily, nowMs: Long, zone: ZoneId): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val target = LocalTime.of(trigger.hour, trigger.minute)
        var day: LocalDate = now.toLocalDate()
        repeat(3) {
            val candidate = day.atTime(target).atZone(zone)
            // atZone resolves DST gaps/overlaps by java.time rules: a
            // nonexistent local time shifts forward, an ambiguous one takes
            // the earlier offset. The wall-clock intent is preserved.
            if (candidate.toInstant().toEpochMilli() > nowMs) return candidate.toInstant().toEpochMilli()
            day = day.plusDays(1)
        }
        return day.atTime(target).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Stable dedup key for a trigger slot: the same workflow, trigger and
     * wall-clock slot always map to the same key, so scheduling the same
     * slot twice — after a reboot, a timezone change or a retry — yields
     * one occurrence, never two (T13).
     */
    fun dedupKey(workflowId: String, triggerIndex: Int, trigger: WorkflowTrigger, fireAtMs: Long): String {
        val slot = when (trigger) {
            is WorkflowTrigger.Reminder -> "reminder@${trigger.atMs}"
            is WorkflowTrigger.Deadline -> "deadline@${trigger.atMs}"
            is WorkflowTrigger.Daily -> {
                val day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fireAtMs), systemZone()).toLocalDate()
                "daily@$day@${"%02d:%02d".format(trigger.hour, trigger.minute)}"
            }
            is WorkflowTrigger.Window -> "window@${trigger.earliestMs}-${trigger.latestMs}"
            is WorkflowTrigger.OnNotification -> "notification@${trigger.appKey}"
            is WorkflowTrigger.OnLocation -> "location@${trigger.latitude},${trigger.longitude}"
            is WorkflowTrigger.Manual -> "manual"
        }
        return "$workflowId|$triggerIndex|$slot"
    }

    /** How precisely the next alarm may be scheduled — checked honestly, never assumed. */
    enum class AlarmMode { EXACT, INEXACT_FALLBACK }

    data class AlarmSchedule(val mode: AlarmMode, val honestNote: String?)

    /**
     * Exact-alarm eligibility is an Android permission
     * (canScheduleExactAlarms). When denied, the scheduler falls back to an
     * inexact alarm and says so — it never claims exact timing it cannot
     * keep (T13).
     */
    fun alarmSchedule(canScheduleExactAlarms: Boolean): AlarmSchedule =
        if (canScheduleExactAlarms) AlarmSchedule(AlarmMode.EXACT, null)
        else AlarmSchedule(AlarmMode.INEXACT_FALLBACK,
            "Exact alarms are not allowed, so this reminder may fire within a few minutes of the requested time.")

    /**
     * Recompute future SCHEDULED occurrences after a timezone change or a
     * manual clock change. Each occurrence is re-derived from its trigger
     * slot in the new zone; occurrences whose trigger no longer applies
     * are left for the missed-run policy.
     */
    fun rescheduleForZoneChange(
        occurrences: List<WorkflowOccurrence>,
        definitions: List<WorkflowDefinition>,
        nowMs: Long,
        zone: ZoneId
    ): List<WorkflowOccurrence> = occurrences.map { occurrence ->
        if (occurrence.state != WorkflowOccurrenceState.SCHEDULED || occurrence.scheduledForMs <= nowMs) return@map occurrence
        val definition = definitions.find { it.id == occurrence.workflowId && it.version == occurrence.definitionVersion }
            ?: return@map occurrence
        val trigger = definition.triggers.getOrNull(occurrence.triggerIndex) ?: return@map occurrence
        val recomputed = when (trigger) {
            is WorkflowTrigger.Daily -> nextDailyFire(trigger, nowMs, zone)
            is WorkflowTrigger.Reminder -> trigger.atMs
            is WorkflowTrigger.Deadline -> trigger.atMs
            is WorkflowTrigger.Window -> maxOf(trigger.earliestMs, nowMs + 1)
            else -> return@map occurrence
        }
        if (recomputed == occurrence.scheduledForMs) occurrence
        else occurrence.copy(
            scheduledForMs = recomputed,
            windowEndMs = if (trigger is WorkflowTrigger.Window) trigger.latestMs else recomputed,
            dedupKey = dedupKey(occurrence.workflowId, occurrence.triggerIndex, trigger, recomputed),
            updatedAtMs = maxOf(occurrence.updatedAtMs, nowMs))
    }

    /**
     * No catch-up duplicate storm: when several occurrences of the same
     * workflow+trigger missed their time, only the latest is evaluated —
     * the rest are reported once as skipped-by-coalescing.
     */
    fun coalesceMissed(occurrences: List<WorkflowOccurrence>): Pair<List<WorkflowOccurrence>, List<WorkflowOccurrence>> {
        val missed = occurrences.filter { it.state == WorkflowOccurrenceState.SCHEDULED }
        val byTrigger = missed.groupBy { it.workflowId to it.triggerIndex }
        val skippedIds = mutableSetOf<String>()
        val skipped = mutableListOf<WorkflowOccurrence>()
        for ((_, group) in byTrigger) {
            if (group.size <= 1) continue
            val latest = group.maxByOrNull { it.scheduledForMs }!!
            for (occurrence in group) {
                if (occurrence.id != latest.id) {
                    skippedIds.add(occurrence.id)
                    skipped.add(occurrence)
                }
            }
        }
        return missed.filter { it.id !in skippedIds } to skipped
    }

    /** Circumstances the local planner weighs for a missed run (D33). */
    data class MissedRunCircumstances(
        /** The trigger's condition still holds (e.g. the deadline has not passed irrelevantly). */
        val triggerStillValid: Boolean,
        /** The user was recently active — a question will likely be seen. */
        val userActiveRecently: Boolean,
        /** How late the run is, in milliseconds. */
        val latenessMs: Long,
        /** Free-text context for the receipt, at most a sentence. */
        val contextNote: String = ""
    )

    /**
     * Evaluate a missed run against current circumstances (D33, T14):
     * run it if still relevant, report missed if not, ask if uncertain.
     * Confirmations still apply to anything that runs.
     */
    fun evaluateMissedRun(
        occurrence: WorkflowOccurrence,
        definition: WorkflowDefinition,
        circumstances: MissedRunCircumstances
    ): MissedRunDecision {
        require(occurrence.workflowId == definition.id)
        val name = "“${definition.name}”"
        if (!circumstances.triggerStillValid) {
            return MissedRunDecision.Irrelevant(
                "$name missed its scheduled time and is no longer relevant" +
                    (if (circumstances.contextNote.isNotBlank()) ": ${circumstances.contextNote}" else ".") +
                    " It was not run.")
        }
        // A flexible window that is still open is not really missed.
        if (circumstances.latenessMs <= 0) {
            return MissedRunDecision.Relevant("$name is still inside its scheduled window — running it now.")
        }
        val trigger = definition.triggers.getOrNull(occurrence.triggerIndex)
        val forgiving = trigger is WorkflowTrigger.Window || trigger is WorkflowTrigger.Daily
        return when {
            // Clearly stale: a one-shot reminder hours late with no one
            // around to answer a question.
            circumstances.latenessMs > 6 * 3_600_000 && !circumstances.userActiveRecently && !forgiving ->
                MissedRunDecision.Irrelevant(
                    "$name missed its time by ${describeDuration(circumstances.latenessMs)} and the moment has passed. " +
                        "It was not run; say the word and I will schedule it again.")
            // Uncertain: recent enough to matter, but the planner cannot
            // tell whether running now is still wanted.
            circumstances.latenessMs > 30 * 60_000 || !circumstances.userActiveRecently ->
                MissedRunDecision.Uncertain(
                    "$name missed its scheduled time by ${describeDuration(circumstances.latenessMs)}. " +
                        "I am not sure running it now is still what you want.",
                    "$name missed its time by ${describeDuration(circumstances.latenessMs)}. Run it now?")
            else ->
                MissedRunDecision.Relevant(
                    "$name missed its time by ${describeDuration(circumstances.latenessMs)} but is still relevant — running it now.")
        }
    }

    private fun describeDuration(durationMs: Long): String {
        val minutes = durationMs / 60_000
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes minutes"
            else -> "${minutes / 60} hours"
        }
    }
}
