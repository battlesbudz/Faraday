package com.battlesbudz.jarvis.v2.actions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * One-shot reminder plumbing on top of the M2 workflow engine (D01, D32, D34).
 *
 * The deterministic front door ([ActionRequestText.reminderRequest]) parses
 * bounded "remind me" phrasings into a [ReminderSpec]; [buildReminderWorkflow]
 * turns the spec into a versioned [WorkflowDefinition] with a single
 * `post_notification` step and a [WorkflowTrigger.Reminder]. Scheduling the
 * reminder is a ledger write plus an alarm arm ([ReminderCoordinator]); the
 * assistant's reply is composed from the resulting receipt, so it can claim
 * "reminder set" only when the write actually succeeded.
 *
 * All time handling here is JVM-pure (java.time, system zone) so the parser
 * and the renderer are unit-testable without Android.
 */

/** A parsed one-shot reminder: what to say, and the absolute trigger time. */
data class ReminderSpec(val message: String, val atMs: Long)

/** Maximum reminder message length, enforced by the parser, validator and coordinator alike. */
const val MAX_REMINDER_MESSAGE = 256

private val REMIND_LEAD = Regex("""(?i)^remind\s+me(?:\s+to|\s+about)?\s+""")
private val AT_TIME = Regex("""(?i)(?:^|\s)(?:(tomorrow|today)\s+)?at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\s*$""")
private val IN_DURATION = Regex("""(?i)\bin\s+(\d{1,3})\s+(minutes?|hours?)\s*$""")

private data class ParsedTime(val atMs: Long, val matchStart: Int)

/**
 * Bounded natural-language forms for one-shot reminders. Returns the message
 * and absolute trigger time, or null when the clause is not a recognizable
 * reminder request. A time phrase is required: "remind me to call mom" with
 * no time stays null so the turn falls through instead of inventing a time.
 */
fun parseReminderRequest(clause: String, nowMs: Long): ReminderSpec? {
    val text = clause.trim()
    val body = REMIND_LEAD.replaceFirst(text, "")
    if (body == text) return null
    val time = parseReminderTimeAtEnd(body, nowMs) ?: return null
    val message = body.substring(0, time.matchStart).trim().trimEnd(',', ';', ':').trim()
    if (message.isEmpty() || message.length > MAX_REMINDER_MESSAGE) return null
    if ('?' in message) return null
    return ReminderSpec(message, time.atMs)
}

private fun parseReminderTimeAtEnd(body: String, nowMs: Long): ParsedTime? {
    IN_DURATION.find(body)?.let { match ->
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitMs = if (match.groupValues[2].startsWith("hour", ignoreCase = true)) 3_600_000L else 60_000L
        val durationMs = try { Math.multiplyExact(amount, unitMs) } catch (_: ArithmeticException) { return null }
        if (durationMs !in 60_000L..86_400_000L) return null
        return ParsedTime(nowMs + durationMs, match.range.first)
    }
    AT_TIME.find(body)?.let { match ->
        val atMs = resolveClockTime(
            match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4], nowMs
        ) ?: return null
        return ParsedTime(atMs, match.range.first)
    }
    return null
}

/**
 * Resolve a clock-time phrase to an absolute trigger time.
 *
 * AM/PM convention: there is no AM/PM rule in
 * docs/plans/tools-interview-decisions.md, so a bare hour with no meridiem
 * takes the plain PM reading ("tomorrow at 4" is next-day 16:00; "at 12"
 * is noon). An explicit am/pm wins; 13-23 is read as 24-hour time. A time
 * with no day that already passed today rolls to the next occurrence of
 * that clock time, and the receipt always states the exact scheduled time
 * so a misread is immediately visible and correctable.
 */
private fun resolveClockTime(
    day: String, hourText: String, minuteText: String, meridiemText: String, nowMs: Long
): Long? {
    val hour = hourText.toIntOrNull() ?: return null
    val minute = minuteText.toIntOrNull() ?: 0
    if (hour !in 1..23 || minute !in 0..59) return null
    val meridiem = meridiemText.lowercase().replace(".", "")
    val hour24 = when {
        meridiem == "am" -> hour % 12
        meridiem == "pm" -> hour % 12 + 12
        hour >= 13 -> hour
        else -> if (hour == 12) 12 else hour + 12
    }
    val zone = ZoneId.systemDefault()
    // Anchor "today" to the caller's clock, not the wall clock: nowMs is the
    // deterministic reference for both parsing and tests.
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = if (day.equals("tomorrow", ignoreCase = true)) today.plusDays(1) else today
    fun at(date: LocalDate): Long =
        ZonedDateTime.of(date, LocalTime.of(hour24, minute), zone).toInstant().toEpochMilli()
    var fireAt = at(date)
    if (fireAt <= nowMs) {
        fireAt = at(date.plusDays(1))
        if (fireAt <= nowMs) return null
    }
    return fireAt
}

/** Build the one-shot reminder definition: one notification step, one Reminder trigger. */
fun buildReminderWorkflow(message: String, atMs: Long, nowMs: Long): WorkflowDefinition {
    val clean = message.trim()
    require(clean.isNotEmpty() && clean.length <= MAX_REMINDER_MESSAGE) {
        "Reminder text must be 1-$MAX_REMINDER_MESSAGE characters."
    }
    require(atMs > nowMs) { "Reminder time must be in the future." }
    val stepId = UUID.randomUUID().toString()
    return WorkflowDefinition(
        id = UUID.randomUUID().toString(),
        name = "Reminder: ${clean.take(64)}",
        description = "One-shot reminder for ${formatReminderTime(atMs, nowMs)}.",
        steps = listOf(
            WorkflowStep.Tool(
                stepId,
                ActionRequest("post_notification", mapOf("title" to "Reminder", "text" to clean))
            )
        ),
        triggers = listOf(WorkflowTrigger.Reminder(atMs)),
        origin = WorkflowOrigin.CONVERSATION,
        createdAtMs = nowMs,
        updatedAtMs = nowMs
    )
}

/** Plain-language time for receipts: "tomorrow at 4:00 PM". */
fun formatReminderTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = LocalDate.now(zone)
    val label = when (at.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
    return "$label at ${at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}"
}

/**
 * Render unfinished occurrences for the schedule view. The empty state is
 * honest: "Nothing is scheduled right now." Never invents entries.
 */
fun renderSchedule(
    occurrences: List<WorkflowOccurrence>,
    definitions: Map<String, WorkflowDefinition>,
    nowMs: Long = System.currentTimeMillis()
): String {
    if (occurrences.isEmpty()) return "Nothing is scheduled right now."
    val shown = occurrences.take(10)
    val lines = shown.map { occurrence ->
        val name = definitions[occurrence.workflowId]?.name ?: "a routine"
        "${formatReminderTime(occurrence.scheduledForMs, nowMs)} - $name"
    }
    val more = if (occurrences.size > shown.size) "\n…and ${occurrences.size - shown.size} more." else ""
    return "Here is what is scheduled:\n" + lines.joinToString("\n") + more
}
