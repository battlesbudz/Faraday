package com.battlesbudz.jarvis.v2.actions

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the Fold 6 report (build 1002): voice "remind me to
 * go door dashing tomorrow at 4" ran with tools disabled, so the model
 * replied "I shall set a reminder" with nothing scheduled; follow-ups
 * ("where did you set that reminder?", "what schedule", "how do I see it")
 * hit plan=NotAction and the model looped "It is noted in your schedule"
 * with no schedule in existence and no way to view one.
 *
 * Every accepted reminder form must produce a Ready create_reminder plan
 * whose dispatch writes a real WorkflowLedger entry; every schedule-view
 * form must produce a Ready show_schedule plan; every rejected form must
 * stay NotAction. Ledger wiring is receipt-gated: a failed write reports
 * failure honestly and never claims the reminder was set.
 */
class ReminderPlanTest {
    private val zone = ZoneId.systemDefault()
    private fun epoch(month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(LocalDate.of(2026, month, day), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()

    /** Tuesday 2026-10-06 10:00 local. */
    private val now = epoch(10, 6, 10, 0)
    // The ledger and coordinator must agree on whether this fixture's reminder is in the future.
    private val clock: () -> Long = { now }

    private fun spec(text: String): ReminderSpec {
        val parsed = ActionRequestText.reminderRequest(text, now)
        assertNotNull("'$text' must parse as a reminder", parsed)
        return parsed!!
    }

    private fun ready(text: String): ActionTurnPlan.Ready {
        val plan = ActionTurnPlan.parse(text, nowMs = now)
        assertTrue("'$text' must parse as an action plan, was $plan", plan is ActionTurnPlan.Ready)
        return plan as ActionTurnPlan.Ready
    }

    @Test fun bugReportPhraseParsesToTomorrowAtFourPm() {
        val parsed = spec("remind me to go door dashing tomorrow at 4")
        assertEquals("go door dashing", parsed.message)
        assertEquals(epoch(10, 7, 16, 0), parsed.atMs)
    }

    @Test fun explicitMeridiemAndBareHourForms() {
        assertEquals(epoch(10, 6, 16, 0), spec("remind me to call mom at 4pm").atMs)
        assertEquals("call mom", spec("remind me to call mom at 4pm").message)
        // Bare hour takes the plain PM reading (no AM/PM convention in the
        // interview decisions; documented in the parser).
        assertEquals(epoch(10, 6, 21, 0), spec("remind me to call mom at 9").atMs)
        assertEquals(epoch(10, 6, 16, 0), spec("remind me to call mom at 16:00").atMs)
        assertEquals(epoch(10, 7, 9, 15), spec("remind me to call dad tomorrow at 9:15 am").atMs)
        assertEquals(epoch(10, 6, 18, 0), spec("remind me to water the plants today at 6 pm").atMs)
        assertEquals(epoch(10, 6, 12, 0), spec("remind me to check the oven at 12").atMs)
        assertEquals("the meeting", spec("remind me about the meeting at 4").message)
    }

    @Test fun pastClockTimeRollsToNextOccurrence() {
        // 7:30 AM today already passed at 10:00, so the reminder lands tomorrow.
        assertEquals(epoch(10, 7, 7, 30), spec("remind me to take vitamins at 7:30 am").atMs)
    }

    @Test fun relativeDurationsResolveAgainstNow() {
        assertEquals(now + 1_800_000, spec("remind me to stretch in 30 minutes").atMs)
        assertEquals(now + 7_200_000, spec("remind me to stretch in 2 hours").atMs)
    }

    @Test fun politeLeadInsStillParse() {
        val plan = ready("hey jarvis, please remind me to call mom at 4pm")
        assertEquals("create_reminder", plan.steps.single().request.name)
        val plan2 = ready("can you remind me to call mom at 4pm")
        assertEquals("create_reminder", plan2.steps.single().request.name)
    }

    @Test fun reminderPlanCarriesMessageAndAbsoluteTime() {
        val request = ready("remind me to go door dashing tomorrow at 4").steps.single().request
        assertEquals("create_reminder", request.name)
        assertEquals("go door dashing", request.arguments["message"])
        assertEquals(epoch(10, 7, 16, 0).toString(), request.arguments["at_ms"])
    }

    @Test fun timlessAndEmptyReminderFormsStayNotAction() {
        for (text in listOf(
            "remind me to call mom",
            "remind me",
            "remind me at 4",
            "call mom at 4",
            "remind me to call mom in 0 minutes",
            "remind me to stretch in 5 days"
        )) {
            assertTrue("'$text' must stay NotAction", ActionTurnPlan.parse(text, nowMs = now) is ActionTurnPlan.NotAction)
        }
    }

    @Test fun negatedAndHypotheticalReminderFormsStayNotAction() {
        for (text in listOf(
            "don't remind me to call at 4",
            "do not remind me to call at 4",
            "never remind me to call at 4",
            "how do i set a reminder",
            "For example, \"remind me to call at 4\""
        )) {
            assertTrue("'$text' must stay NotAction", ActionTurnPlan.parse(text, nowMs = now) is ActionTurnPlan.NotAction)
        }
    }

    @Test fun scheduleViewFormsParseToShowSchedule() {
        for (text in listOf(
            "show my schedule",
            "show me my schedule",
            "what's my schedule",
            "what is my schedule",
            "list my reminders",
            "show my reminders",
            "what reminders do i have",
            "do i have any reminders",
            "where did you set that reminder?",
            "what schedule",
            "how do i see it",
            "how do i see my reminders",
            "please show my schedule",
            "can you show me my schedule"
        )) {
            val request = ready(text).steps.single().request
            assertEquals("'$text' must route to show_schedule", "show_schedule", request.name)
            assertTrue("'$text' show_schedule takes no arguments", request.arguments.isEmpty())
        }
    }

    @Test fun schedulingVerbsThatAreNotViewsStayNotAction() {
        for (text in listOf(
            "schedule a meeting for tomorrow",
            "reschedule my appointment",
            "what's the schedule for the meeting",
            "cancel my reminder"
        )) {
            assertTrue("'$text' must stay NotAction", ActionTurnPlan.parse(text, nowMs = now) is ActionTurnPlan.NotAction)
        }
    }

    @Test fun parserOutputPassesStrictDecode() {
        // The parser must only emit arguments the strict catalog boundary accepts.
        val request = ready("remind me to go door dashing tomorrow at 4").steps.single().request
        val strict = MobileToolCatalog.decodeStrict(
            request.name, org.json.JSONObject(request.arguments as Map<*, *>)
        )
        assertNotNull("create_reminder args must pass strict decode", strict)
        val view = ready("show my schedule").steps.single().request
        assertNotNull("show_schedule must pass strict decode",
            MobileToolCatalog.decodeStrict(view.name, org.json.JSONObject(view.arguments as Map<*, *>)))
    }

    @Test fun reminderDefinitionIsAValidWorkflow() {
        // The one-shot reminder definition must satisfy the M2 structural
        // validation: catalog tool, exact arguments, routine-eligible step.
        val definition = buildReminderWorkflow("go door dashing", epoch(10, 7, 16, 0), now)
        validateWorkflowDefinition(definition)
        assertEquals(listOf(WorkflowTrigger.Reminder(epoch(10, 7, 16, 0))), definition.triggers)
    }

    private val scheduledOccurrences = mutableListOf<WorkflowOccurrence>()

    private fun coordinator(store: ToolTaskStore = InMemoryToolTaskStore()): ReminderCoordinator =
        ReminderCoordinator(WorkflowLedger(store, now = clock), { occurrence ->
            scheduledOccurrences += occurrence
            WorkflowAlarmScheduler.Scheduled(WorkflowScheduling.AlarmMode.INEXACT_FALLBACK, null)
        }, now = clock)

    @Test fun ledgerWriteSuccessProducesReceiptAndListsTheReminder() {
        val ledger = WorkflowLedger(InMemoryToolTaskStore(), now = clock)
        val coordinator = ReminderCoordinator(ledger, { occurrence ->
            scheduledOccurrences += occurrence
            WorkflowAlarmScheduler.Scheduled(WorkflowScheduling.AlarmMode.INEXACT_FALLBACK, null)
        }, now = clock)
        val created = coordinator.createReminder("go door dashing", epoch(10, 7, 16, 0))
        assertTrue("ledger write must succeed: ${created.message}", created.succeeded)
        assertTrue("receipt must claim the set honestly: ${created.message}",
            created.message.startsWith("Reminder set for"))
        assertTrue("receipt must name the requested time: ${created.message}",
            created.message.contains("4:00 PM"))
        assertEquals(1, scheduledOccurrences.size)
        assertEquals(epoch(10, 7, 16, 0), scheduledOccurrences.single().scheduledForMs)
        val listed = coordinator.describeSchedule()
        assertTrue("schedule must list the reminder: ${listed.message}", listed.succeeded)
        assertTrue("schedule must contain the reminder text: ${listed.message}",
            listed.message.contains("go door dashing"))
        assertFalse("schedule must not claim emptiness: ${listed.message}",
            listed.message.contains("Nothing is scheduled"))
    }

    @Test fun ledgerWriteFailureReportsHonestlyAndNeverClaimsSuccess() {
        val coordinator = coordinator(object : ToolTaskStore {
            override fun read(): List<ToolTaskAttempt> = throw ToolTaskStorageException()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> =
                throw ToolTaskStorageException()
            override fun readJournal(): ToolTaskJournal = throw ToolTaskStorageException()
            override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal =
                throw ToolTaskStorageException()
        })
        val created = coordinator.createReminder("go door dashing", epoch(10, 7, 16, 0))
        assertFalse("failed write must not succeed", created.succeeded)
        assertFalse("failed write must never claim the reminder was set: ${created.message}",
            created.message.contains("Reminder set"))
        assertTrue("failed write must say plainly it could not set it: ${created.message}",
            created.message.contains("couldn't"))
        val listed = coordinator.describeSchedule()
        assertFalse("unreadable schedule must not succeed", listed.succeeded)
    }

    @Test fun emptyScheduleRendersHonestEmptyState() {
        val listed = coordinator().describeSchedule()
        assertTrue("empty schedule read must succeed: ${listed.message}", listed.succeeded)
        assertTrue("empty schedule must say so honestly: ${listed.message}",
            listed.message.contains("Nothing is scheduled"))
    }

    @Test fun pastTimeIsRejectedBeforeAnyLedgerWrite() {
        val store = InMemoryToolTaskStore()
        val created = coordinator(store).createReminder("go door dashing", now - 1_000)
        assertFalse(created.succeeded)
        assertTrue("past time must be reported plainly: ${created.message}",
            created.message.contains("already passed"))
        assertTrue("no workflow may be written for a rejected reminder",
            WorkflowLedger(store, now = clock).list().isEmpty())
    }
}
