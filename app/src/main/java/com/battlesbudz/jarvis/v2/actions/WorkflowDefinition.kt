package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M2 reusable workflows: versioned step graphs with typed result bindings,
 * deterministic conditions, event/timer waits and bounded adaptive steps
 * (D31–D36, T11–T14).
 *
 * A workflow is created as a disabled draft — via conversation or by
 * capturing a successful task — shows a plain-language preview, and only
 * runs after an explicit enable. Revisions create a new version; running
 * occurrences keep the version they started with (T12).
 *
 * Deterministic conditions run in code: the model may propose the steps,
 * but it never authorizes its own proposals — every condition below is
 * evaluated by [WorkflowEngine], never by inference.
 */

/** Value types for typed result bindings between steps. */
enum class WorkflowValueType { TEXT, NUMBER, BOOLEAN }

/** A typed output reference: the named output of an earlier step. */
data class WorkflowBinding(val stepId: String, val outputName: String)

/**
 * Deterministic conditions evaluated in code. All comparisons are
 * value-typed: Equals/NotEquals/Matches apply to TEXT outputs,
 * GreaterThan/LessThan to NUMBER outputs.
 */
sealed interface WorkflowCondition {
    data class Equals(val binding: WorkflowBinding, val literal: String) : WorkflowCondition
    data class NotEquals(val binding: WorkflowBinding, val literal: String) : WorkflowCondition
    data class GreaterThan(val binding: WorkflowBinding, val number: Double) : WorkflowCondition
    data class LessThan(val binding: WorkflowBinding, val number: Double) : WorkflowCondition
    data class Matches(val binding: WorkflowBinding, val regex: String) : WorkflowCondition
}

/** Event kinds a wait step or trigger can listen for. */
enum class WorkflowEventKind { NOTIFICATION, LOCATION }

/** What a wait step pauses for. Timer/UntilTime resume on the clock; Event resumes on the event. */
sealed interface WorkflowWait {
    /** Wait this long before continuing. */
    data class Timer(val durationMs: Long) : WorkflowWait
    /** Wait until this wall-clock time before continuing. */
    data class UntilTime(val epochMs: Long) : WorkflowWait
    /** Wait for an event: a notification from an app, or entering a location radius. */
    data class Event(val kind: WorkflowEventKind, val appKey: String? = null,
        val latitude: Double? = null, val longitude: Double? = null, val radiusMeters: Double? = null) : WorkflowWait
}

/** Task-specific effort budget for a bounded adaptive step (D28, T14). */
data class EffortBudget(val maxAttempts: Int, val maxWallMs: Long, val noProgressLimit: Int)

/** One node of the versioned step graph. */
sealed interface WorkflowStep {
    val id: String

    /**
     * Run one phone action. [bindings] map argument names to typed outputs
     * of earlier steps; [outputs] declares this step's typed outputs for
     * later steps to bind. A step whose request needs an exact approval
     * (screen mutations) runs on an independent approval branch per
     * occurrence — no routine grant may waive it (D11/D23).
     */
    data class Tool(
        override val id: String,
        val request: ActionRequest,
        val bindings: Map<String, WorkflowBinding> = emptyMap(),
        val outputs: Map<String, WorkflowValueType> = emptyMap()
    ) : WorkflowStep

    /** Deterministic branch evaluated in code. */
    data class Branch(
        override val id: String,
        val condition: WorkflowCondition,
        val thenSteps: List<WorkflowStep>,
        val elseSteps: List<WorkflowStep> = emptyList()
    ) : WorkflowStep

    /** Pause the occurrence until the timer, clock time or event fires. */
    data class Wait(override val id: String, val wait: WorkflowWait) : WorkflowStep

    /**
     * Bounded adaptive step: try [candidates] in order until one succeeds
     * or [budget] is exhausted. Exhaustion asks the user instead of
     * retrying — completed steps are never re-run (T14).
     */
    data class Adaptive(
        override val id: String,
        val goal: String,
        val candidates: List<ActionRequest>,
        val budget: EffortBudget
    ) : WorkflowStep

    /**
     * M5: run an on-phone script in the isolated script runtime (D44, T20).
     * The language has no I/O of its own: the script can only call the
     * [requiredHostFunctions], and the engine executes it through an
     * injected runner that fails closed when no runtime is installed.
     * The script's text result is exposed as the "result" output for later
     * steps to bind.
     */
    data class Script(
        override val id: String,
        val source: String,
        val requiredHostFunctions: List<String> = emptyList(),
        val outputs: Map<String, WorkflowValueType> = emptyMap()
    ) : WorkflowStep
}

/** What starts a workflow occurrence. */
sealed interface WorkflowTrigger {
    /** Only from chat/voice. */
    data object Manual : WorkflowTrigger
    /** Fire at exactly this time (D34: reminders target the requested time). */
    data class Reminder(val atMs: Long) : WorkflowTrigger
    /** Fire once a day at this local time. */
    data class Daily(val hour: Int, val minute: Int) : WorkflowTrigger
    /** Fire once inside this window; the scheduler picks a concrete time. */
    data class Window(val earliestMs: Long, val latestMs: Long) : WorkflowTrigger
    /** Fire when the named app posts a notification. */
    data class OnNotification(val appKey: String) : WorkflowTrigger
    /** Fire when entering this radius. */
    data class OnLocation(val latitude: Double, val longitude: Double, val radiusMeters: Double) : WorkflowTrigger
    /**
     * D62 internal reminder: a relevant known deadline automatically
     * creates an internal reminder occurrence — no separately enabled
     * general workflow is required.
     */
    data class Deadline(val atMs: Long, val title: String) : WorkflowTrigger
}

enum class WorkflowOrigin { CONVERSATION, CAPTURED }

/**
 * A versioned, immutable workflow definition. [enabled] is false until an
 * explicit enable call; drafts never run (T12).
 */
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<WorkflowStep>,
    val triggers: List<WorkflowTrigger>,
    val origin: WorkflowOrigin,
    val version: Long = 1,
    val enabled: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Max tool dispatches for one occurrence; a second safety net under step budgets. */
    val maxStepsPerRun: Int = 16
) {
    init {
        validateWorkflowDefinition(this)
    }
}

/** The standard typed outputs the engine extracts from each tool's receipt. */
fun standardToolOutputs(request: ActionRequest): Map<String, WorkflowValueType> = buildMap {
    put("message", WorkflowValueType.TEXT)
    put("succeeded", WorkflowValueType.BOOLEAN)
    if (request.name == "read_battery") put("battery_percent", WorkflowValueType.NUMBER)
}

/** Placeholder syntax for typed bindings inside argument values: `${stepId.outputName}`. */
internal data class BindingPlaceholder(
    val range: IntRange,
    val stepId: String,
    val outputName: String,
    val text: String
)

/**
 * Finds `${<36-char step id>.<outputName>}` placeholders by manual scan.
 *
 * A regex is deliberately NOT used here: Android's ICU regex engine throws
 * PatternSyntaxException for the hyphen-in-character-class forms this
 * pattern needs, while the desktop JVM accepts them — a mismatch JVM unit
 * tests cannot catch (it crashed test53-56 on-device). The manual scan is
 * deterministic on every runtime and matches the same language.
 */
internal fun findBindingPlaceholders(value: String): List<BindingPlaceholder> {
    fun isHexOrHyphen(c: Char) = c == '-' || c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
    fun isNamePart(c: Char) = isNameStart(c) || c in '0'..'9'
    val found = mutableListOf<BindingPlaceholder>()
    var i = 0
    while (i < value.length) {
        val open = value.indexOf("\${", i)
        if (open < 0) break
        val close = value.indexOf('}', open + 2)
        if (close < 0) break
        val inner = value.substring(open + 2, close)
        val dot = inner.indexOf('.')
        if (dot > 0 && dot < inner.length - 1) {
            val id = inner.substring(0, dot)
            val name = inner.substring(dot + 1)
            if (id.length == 36 && id.all(::isHexOrHyphen) &&
                isNameStart(name[0]) && name.all(::isNamePart)
            ) {
                found += BindingPlaceholder(open..close, id, name, value.substring(open, close + 1))
            }
        }
        i = close + 1
    }
    return found
}

/** Replaces every binding placeholder in [value] with [lookup]'s result. */
internal fun substituteBindingPlaceholders(
    value: String,
    lookup: (stepId: String, outputName: String, text: String) -> String
): String {
    val placeholders = findBindingPlaceholders(value)
    if (placeholders.isEmpty()) return value
    val out = StringBuilder(value.length)
    var cursor = 0
    for (p in placeholders) {
        out.append(value, cursor, p.range.first)
        out.append(lookup(p.stepId, p.outputName, p.text))
        cursor = p.range.last + 1
    }
    out.append(value, cursor, value.length)
    return out.toString()
}

private fun String.isUuid(): Boolean = try {
    UUID.fromString(this).toString() == this
} catch (_: IllegalArgumentException) { false }

/** Structural validation for a workflow definition; throws on any violation. */
fun validateWorkflowDefinition(definition: WorkflowDefinition) {
    require(definition.id.isUuid()) { "Workflow id must be a UUID." }
    require(definition.name.isNotBlank() && definition.name.length <= 128) { "Workflow name must be 1-128 characters." }
    require(definition.description.length <= 512) { "Workflow description must be at most 512 characters." }
    require(definition.version >= 1) { "Workflow version must be at least 1." }
    require(definition.maxStepsPerRun in 1..32) { "maxStepsPerRun must be 1-32." }
    require(definition.steps.size in 1..16) { "A workflow needs 1-16 top-level steps." }
    require(definition.triggers.size in 1..8) { "A workflow needs 1-8 triggers." }
    require(definition.createdAtMs >= 0 && definition.updatedAtMs >= definition.createdAtMs)
    val stepIds = mutableSetOf<String>()
    fun checkStep(step: WorkflowStep, depth: Int, seenOutputs: MutableMap<String, Map<String, WorkflowValueType>>) {
        require(depth <= 3) { "Workflow nesting is limited to 3 levels." }
        require(step.id.isUuid() && stepIds.add(step.id)) { "Step ids must be unique UUIDs." }
        when (step) {
            is WorkflowStep.Tool -> {
                val catalog = MobileToolCatalog.find(step.request.name)
                    ?: throw IllegalArgumentException("Unknown tool ${step.request.name}.")
                require(step.request.arguments.size == catalog.parameters.size &&
                    step.request.arguments.keys == catalog.parameters.map { it.name }.toSet()) {
                    "Tool step arguments must match the catalog for ${step.request.name}."
                }
                // Only routine-eligible tools run under a routine grant;
                // screen mutations run on an independent exact-approval
                // branch. Anything else cannot run unattended in a workflow.
                require(step.request.isRoutineEligible() || step.request.name in SCREEN_MUTATION_TOOLS) {
                    "Workflow steps must be routine-eligible or screen mutations on an approval branch."
                }
                val declared = standardToolOutputs(step.request)
                require(step.outputs.all { (name, type) -> declared[name] == type }) {
                    "Step outputs must be standard tool outputs with matching types for ${step.request.name}."
                }
                // Bindings must reference earlier steps' declared outputs.
                for ((argument, binding) in step.bindings) {
                    require(argument in step.request.arguments) { "Binding target $argument is not an argument." }
                    val outputs = seenOutputs[binding.stepId]
                        ?: throw IllegalArgumentException("Binding references unknown step ${binding.stepId}.")
                    require(binding.outputName in outputs) {
                        "Binding references undeclared output ${binding.outputName}."
                    }
                }
                // Inline placeholders must reference earlier steps too.
                for (value in step.request.arguments.values) {
                    for (placeholder in findBindingPlaceholders(value)) {
                        val outputs = seenOutputs[placeholder.stepId]
                            ?: throw IllegalArgumentException(
                                "Placeholder references unknown step ${placeholder.stepId}.")
                        require(placeholder.outputName in outputs) {
                            "Placeholder references undeclared output ${placeholder.outputName}."
                        }
                    }
                }
                seenOutputs[step.id] = step.outputs.ifEmpty { declared }
            }
            is WorkflowStep.Branch -> {
                checkCondition(step.condition, seenOutputs)
                require(step.thenSteps.isNotEmpty() && step.thenSteps.size + step.elseSteps.size <= 8) {
                    "A branch needs 1-8 nested steps."
                }
                step.thenSteps.forEach { checkStep(it, depth + 1, seenOutputs.toMutableMap()) }
                step.elseSteps.forEach { checkStep(it, depth + 1, seenOutputs.toMutableMap()) }
            }
            is WorkflowStep.Wait -> checkWait(step.wait)
            is WorkflowStep.Adaptive -> {
                require(step.goal.isNotBlank() && step.goal.length <= 256) { "Adaptive goal must be 1-256 characters." }
                require(step.candidates.size in 1..5) { "An adaptive step needs 1-5 candidate actions." }
                step.candidates.forEach { candidate ->
                    require(MobileToolCatalog.find(candidate.name) != null) { "Unknown tool ${candidate.name}." }
                    require(candidate.isRoutineEligible() || candidate.name in SCREEN_MUTATION_TOOLS) {
                        "Adaptive candidates must be routine-eligible or screen mutations."
                    }
                }
                require(step.budget.maxAttempts in 1..20) { "maxAttempts must be 1-20." }
                require(step.budget.maxWallMs in 1_000..600_000) { "maxWallMs must be 1s-10min." }
                require(step.budget.noProgressLimit in 1..10) { "noProgressLimit must be 1-10." }
                seenOutputs[step.id] = mapOf("message" to WorkflowValueType.TEXT, "succeeded" to WorkflowValueType.BOOLEAN)
            }
            is WorkflowStep.Script -> {
                require(step.source.isNotBlank() && step.source.length <= 8192) {
                    "Script source must be 1-8192 characters."
                }
                require(step.requiredHostFunctions.size <= 16) {
                    "A script step declares at most 16 required host functions."
                }
                step.requiredHostFunctions.forEach { fn ->
                    require(fn.isNotBlank() && fn.length <= 64 &&
                        fn.all { c -> c.isLetterOrDigit() || c == '_' || c == '.' }) {
                        "Bad host function name “$fn”."
                    }
                }
                require(step.outputs.all { (name, type) -> name == "result" && type == WorkflowValueType.TEXT }) {
                    "Script outputs may only declare “result” as TEXT."
                }
                seenOutputs[step.id] = mapOf("result" to WorkflowValueType.TEXT)
            }
        }
    }
    val seenOutputs = mutableMapOf<String, Map<String, WorkflowValueType>>()
    definition.steps.forEach { checkStep(it, 0, seenOutputs) }
    definition.triggers.forEach { checkTrigger(it) }
}

private fun checkCondition(condition: WorkflowCondition, seenOutputs: Map<String, Map<String, WorkflowValueType>>) {
    fun bindingType(binding: WorkflowBinding): WorkflowValueType {
        val outputs = seenOutputs[binding.stepId]
            ?: throw IllegalArgumentException("Condition references unknown step ${binding.stepId}.")
        return outputs[binding.outputName]
            ?: throw IllegalArgumentException("Condition references undeclared output ${binding.outputName}.")
    }
    when (condition) {
        is WorkflowCondition.Equals -> {
            require(bindingType(condition.binding) == WorkflowValueType.TEXT) { "Equals needs a TEXT output." }
            require(condition.literal.length <= 512) { "Condition literal too long." }
        }
        is WorkflowCondition.NotEquals -> {
            require(bindingType(condition.binding) == WorkflowValueType.TEXT) { "NotEquals needs a TEXT output." }
            require(condition.literal.length <= 512) { "Condition literal too long." }
        }
        is WorkflowCondition.GreaterThan -> {
            require(bindingType(condition.binding) == WorkflowValueType.NUMBER) { "GreaterThan needs a NUMBER output." }
            require(condition.number.isFinite()) { "Condition number must be finite." }
        }
        is WorkflowCondition.LessThan -> {
            require(bindingType(condition.binding) == WorkflowValueType.NUMBER) { "LessThan needs a NUMBER output." }
            require(condition.number.isFinite()) { "Condition number must be finite." }
        }
        is WorkflowCondition.Matches -> {
            require(bindingType(condition.binding) == WorkflowValueType.TEXT) { "Matches needs a TEXT output." }
            require(condition.regex.length <= 256) { "Condition regex too long." }
            try { Regex(condition.regex) } catch (_: Exception) {
                throw IllegalArgumentException("Condition regex is invalid.")
            }
        }
    }
}

private fun checkWait(wait: WorkflowWait) {
    when (wait) {
        is WorkflowWait.Timer -> require(wait.durationMs in 1_000..604_800_000) { "Timer wait must be 1s-7d." }
        is WorkflowWait.UntilTime -> require(wait.epochMs > 0) { "UntilTime must be a positive epoch." }
        is WorkflowWait.Event -> when (wait.kind) {
            WorkflowEventKind.NOTIFICATION -> {
                require(!wait.appKey.isNullOrBlank() && wait.appKey.length <= 128) {
                    "Notification waits need an app key."
                }
            }
            WorkflowEventKind.LOCATION -> {
                require(wait.latitude != null && wait.longitude != null && wait.radiusMeters != null) {
                    "Location waits need coordinates and a radius."
                }
                require(wait.latitude in -90.0..90.0 && wait.longitude in -180.0..180.0) {
                    "Location coordinates out of range."
                }
                require(wait.radiusMeters in 10.0..5000.0) { "Location radius must be 10m-5km." }
            }
        }
    }
}

private fun checkTrigger(trigger: WorkflowTrigger) {
    when (trigger) {
        is WorkflowTrigger.Manual -> Unit
        is WorkflowTrigger.Reminder -> require(trigger.atMs > 0) { "Reminder needs a positive time." }
        is WorkflowTrigger.Daily -> require(trigger.hour in 0..23 && trigger.minute in 0..59) {
            "Daily trigger needs a valid local time."
        }
        is WorkflowTrigger.Window -> {
            require(trigger.earliestMs > 0 && trigger.latestMs > trigger.earliestMs) {
                "Window needs earliest < latest."
            }
            require(trigger.latestMs - trigger.earliestMs <= 86_400_000) { "Window must be at most 24h." }
        }
        is WorkflowTrigger.OnNotification -> require(trigger.appKey.isNotBlank() && trigger.appKey.length <= 128) {
            "Notification triggers need an app key."
        }
        is WorkflowTrigger.OnLocation -> {
            require(trigger.latitude in -90.0..90.0 && trigger.longitude in -180.0..180.0) {
                "Location trigger coordinates out of range."
            }
            require(trigger.radiusMeters in 10.0..5000.0) { "Location radius must be 10m-5km." }
        }
        is WorkflowTrigger.Deadline -> {
            require(trigger.atMs > 0) { "Deadline needs a positive time." }
            require(trigger.title.isNotBlank() && trigger.title.length <= 256) { "Deadline needs a title." }
        }
    }
}

/**
 * Plain-language preview shown before enabling (D31, T12): steps, triggers
 * and permissions in ordinary sentences — no tool names or ids.
 */
fun WorkflowDefinition.previewText(): String {
    val lines = mutableListOf("“${name}” — ${description.ifBlank { "a saved routine" }}")
    lines += "Steps:"
    fun describeStep(step: WorkflowStep, indent: String, number: IntArray) {
        val prefix = "$indent${number[0]++}. "
        when (step) {
            is WorkflowStep.Tool -> {
                val what = when (step.request.name) {
                    "read_battery" -> "Check the battery level"
                    "set_volume" -> "Set the media volume to ${step.request.arguments["level"]}%"
                    "open_app" -> "Open ${step.request.arguments["app"]}"
                    "media_control" -> when (step.request.arguments["action"]) {
                        "play" -> "Start media playback"
                        "pause" -> "Pause media playback"
                        "toggle" -> "Toggle media playback"
                        "next" -> "Skip to the next track"
                        "previous" -> "Go back to the previous track"
                        else -> "Control media playback"
                    }
                    "screen_tap" -> "Tap a screen element (asks you first)"
                    "screen_scroll" -> "Scroll the screen (asks you first)"
                    "screen_type" -> "Type on the screen (asks you first)"
                    "create_reminder" -> "Set a reminder for ${step.request.arguments["message"]}"
                    "show_schedule" -> "Show the schedule"
                    "post_notification" -> "Show a notification: ${step.request.arguments["title"]}"
                    else -> step.request.name
                }
                lines += prefix + what
            }
            is WorkflowStep.Branch -> {
                lines += prefix + "If ${describeCondition(step.condition)}, then:"
                val sub = intArrayOf(1)
                step.thenSteps.forEach { describeStep(it, "$indent   ", sub) }
                if (step.elseSteps.isNotEmpty()) {
                    lines += "$indent   Otherwise:"
                    val subElse = intArrayOf(1)
                    step.elseSteps.forEach { describeStep(it, "$indent   ", subElse) }
                }
            }
            is WorkflowStep.Wait -> lines += prefix + when (val wait = step.wait) {
                is WorkflowWait.Timer -> "Wait ${describeDuration(wait.durationMs)}"
                is WorkflowWait.UntilTime -> "Wait until the scheduled time"
                is WorkflowWait.Event -> when (wait.kind) {
                    WorkflowEventKind.NOTIFICATION -> "Wait for a notification from ${wait.appKey}"
                    WorkflowEventKind.LOCATION -> "Wait until arriving near the saved location"
                }
            }
            is WorkflowStep.Adaptive -> lines += prefix +
                "Try to: ${step.goal} (up to ${step.budget.maxAttempts} tries, then asks you)"
            is WorkflowStep.Script -> lines += prefix + "Run an on-phone script"
        }
    }
    val number = intArrayOf(1)
    steps.forEach { describeStep(it, "", number) }
    lines += "Runs: " + triggers.joinToString("; ") { describeTrigger(it) }
    val families = steps.flatMap { collectToolSteps(it) }.map { ToolSourcePolicy.familyOf(it.request.name) }.toSet()
    lines += "Permissions: " + families.joinToString(", ") { ToolSourcePolicy.describeFamily(it) } +
        ". Screen actions always ask you first, every time."
    lines += if (enabled) "This routine is enabled." else "This routine is a draft — say “enable it” to turn it on."
    return lines.joinToString("\n")
}

private fun collectToolSteps(step: WorkflowStep): List<WorkflowStep.Tool> = when (step) {
    is WorkflowStep.Tool -> listOf(step)
    is WorkflowStep.Branch -> step.thenSteps.flatMap(::collectToolSteps) + step.elseSteps.flatMap(::collectToolSteps)
    is WorkflowStep.Adaptive -> step.candidates.map {
        WorkflowStep.Tool(UUID.randomUUID().toString(), it)
    }
    is WorkflowStep.Wait -> emptyList()
    is WorkflowStep.Script -> emptyList()
}

private fun describeCondition(condition: WorkflowCondition): String {
    fun ref(binding: WorkflowBinding) = "the “${binding.outputName}” from step ${binding.stepId.take(8)}"
    return when (condition) {
        is WorkflowCondition.Equals -> "${ref(condition.binding)} is “${condition.literal}”"
        is WorkflowCondition.NotEquals -> "${ref(condition.binding)} is not “${condition.literal}”"
        is WorkflowCondition.GreaterThan -> "${ref(condition.binding)} is more than ${condition.number}"
        is WorkflowCondition.LessThan -> "${ref(condition.binding)} is less than ${condition.number}"
        is WorkflowCondition.Matches -> "${ref(condition.binding)} matches “${condition.regex}”"
    }
}

private fun describeTrigger(trigger: WorkflowTrigger): String = when (trigger) {
    is WorkflowTrigger.Manual -> "when you ask in chat"
    is WorkflowTrigger.Reminder -> "as a reminder at the requested time"
    is WorkflowTrigger.Daily -> "every day at ${"%02d:%02d".format(trigger.hour, trigger.minute)}"
    is WorkflowTrigger.Window -> "once inside its scheduled window"
    is WorkflowTrigger.OnNotification -> "when ${trigger.appKey} posts a notification"
    is WorkflowTrigger.OnLocation -> "when you arrive near the saved location"
    is WorkflowTrigger.Deadline -> "as an automatic reminder: ${trigger.title}"
}

private fun describeDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    return when {
        seconds < 60 -> "$seconds seconds"
        seconds < 3600 -> "${seconds / 60} minutes"
        seconds < 86400 -> "${seconds / 3600} hours"
        else -> "${seconds / 86400} days"
    }
}
