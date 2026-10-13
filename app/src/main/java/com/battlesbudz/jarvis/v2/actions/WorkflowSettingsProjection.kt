package com.battlesbudz.jarvis.v2.actions

/**
 * M2 settings surface (D36): saved workflows with enable/disable state and
 * connected tools, projected from the durable journal. Chat remains the
 * operating surface — this projection is read-only data for Settings; every
 * mutation goes through [WorkflowLedger] with its explicit enable/disable
 * calls.
 */
data class WorkflowSettingsRow(
    val id: String,
    val name: String,
    val summary: String,
    val enabled: Boolean,
    val version: Long,
    val triggersSummary: String,
    val nextRunSummary: String?
)

data class ConnectedToolRow(
    val family: String,
    val description: String,
    /** granted, denied, revoked, or not yet asked. */
    val state: String
)

data class WorkflowSettingsProjection(
    val workflows: List<WorkflowSettingsRow>,
    val tools: List<ConnectedToolRow>,
    /** M3: ecosystem providers with honest availability states (D36, T16, T17). */
    val providers: List<ProviderSettingsRow> = emptyList()
) {
    companion object {
        fun from(journal: ToolTaskJournal, nowMs: Long): WorkflowSettingsProjection =
            from(journal, nowMs, emptyList())

        fun from(
            journal: ToolTaskJournal,
            nowMs: Long,
            providers: List<ProviderSettingsRow>
        ): WorkflowSettingsProjection {
            val seen = hashSetOf<String>()
            val current = journal.workflows.sortedByDescending { it.version }.filter { seen.add(it.id) }
            val rows = current.map { definition ->
                val next = journal.occurrences
                    .filter { it.workflowId == definition.id && it.state == WorkflowOccurrenceState.SCHEDULED }
                    .minByOrNull { it.scheduledForMs }
                WorkflowSettingsRow(
                    id = definition.id,
                    name = definition.name,
                    summary = definition.description.ifBlank { "Saved routine" },
                    enabled = definition.enabled,
                    version = definition.version,
                    triggersSummary = definition.triggers.joinToString("; ") { triggerSummary(it) },
                    nextRunSummary = when {
                        !definition.enabled -> "Disabled"
                        next == null -> if (definition.triggers.any { it is WorkflowTrigger.Manual }) "Runs when you ask" else "No upcoming run"
                        else -> "Next run ${java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
                            .format(java.util.Date(next.scheduledForMs))}"
                    })
            }
            val families = listOf("phone", "media", "web", "settings", "map", "screen")
            val toolRows = families.map { family ->
                val record = journal.sourceAccess.find { it.family == family }
                ConnectedToolRow(family, ToolSourcePolicy.describeFamily(family),
                    record?.state?.name?.lowercase() ?: "not yet asked")
            }
            return WorkflowSettingsProjection(rows, toolRows, providers)
        }

        private fun triggerSummary(trigger: WorkflowTrigger): String = when (trigger) {
            is WorkflowTrigger.Manual -> "Manual"
            is WorkflowTrigger.Reminder -> "Reminder"
            is WorkflowTrigger.Daily -> "Daily ${"%02d:%02d".format(trigger.hour, trigger.minute)}"
            is WorkflowTrigger.Window -> "Flexible window"
            is WorkflowTrigger.OnNotification -> "On ${trigger.appKey} notification"
            is WorkflowTrigger.OnLocation -> "On location"
            is WorkflowTrigger.Deadline -> "Deadline: ${trigger.title}"
        }
    }
}
