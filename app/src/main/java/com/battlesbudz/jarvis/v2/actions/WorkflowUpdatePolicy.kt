package com.battlesbudz.jarvis.v2.actions

/**
 * M5 community workflows: update policy (D43, T19).
 *
 * Updates auto-apply ONLY for documentation-only changes under a narrow
 * deterministic rule: the canonical manifest bytes must be identical except
 * for the documentation text, the workflow's display name/description, and
 * the workflow version number. Everything else — steps, triggers, tools,
 * scopes, script runtime, setup bindings, provenance — makes it a
 * behavior change that needs user review.
 *
 * A model's assertion is never sufficient to certify code equivalence;
 * changed scripts always go through review. Running workflows stay pinned
 * to their admitted version: a pending update is stored beside the
 * admitted definition and only replaces it on explicit approval.
 */

enum class UpdateClass { DOCUMENTATION_ONLY, BEHAVIOR_CHANGE }

/**
 * Classify [new] relative to [old]. Both must target the same workflow id.
 * Pure function of the canonical manifest bytes — no model involved.
 */
fun classifyWorkflowUpdate(old: WorkflowManifest, new: WorkflowManifest): UpdateClass {
    require(old.workflow.id == new.workflow.id) { "An update must target the same workflow id." }
    return if (equalExceptDocs(manifestToNode(old), manifestToNode(new), ""))
        UpdateClass.DOCUMENTATION_ONLY
    else UpdateClass.BEHAVIOR_CHANGE
}

private fun equalExceptDocs(a: MNode, b: MNode, path: String): Boolean {
    if (a is MNode.Obj && b is MNode.Obj) {
        val skip = when (path) {
            "" -> setOf("documentation")
            "workflow" -> setOf("name", "description", "version")
            else -> emptySet()
        }
        val keys = (a.fields.keys + b.fields.keys).filter { it !in skip }.toSet()
        return keys.all { k ->
            val av = a.fields[k]
            val bv = b.fields[k]
            av != null && bv != null && equalExceptDocs(av, bv, if (path.isEmpty()) k else "$path.$k")
        }
    }
    return a == b
}

/** Human-readable change list for the review UI. Empty when nothing changed. */
fun diffWorkflowManifests(old: WorkflowManifest, new: WorkflowManifest): List<String> {
    val changes = mutableListOf<String>()
    if (old.documentation != new.documentation) changes += "documentation text changed"
    val ow = old.workflow
    val nw = new.workflow
    if (ow.name != nw.name) changes += "name: “${ow.name}” → “${nw.name}”"
    if (ow.description != nw.description) changes += "description changed"
    if (ow.version != nw.version) changes += "workflow version ${ow.version} → ${nw.version}"
    if (ow.maxStepsPerRun != nw.maxStepsPerRun)
        changes += "maxStepsPerRun: ${ow.maxStepsPerRun} → ${nw.maxStepsPerRun}"
    val oldSteps = ow.steps.associateBy { it.id }
    val newSteps = nw.steps.associateBy { it.id }
    (newSteps.keys - oldSteps.keys).forEach { id -> changes += "step added: ${describeStepKind(newSteps.getValue(id))}" }
    (oldSteps.keys - newSteps.keys).forEach { id -> changes += "step removed: ${describeStepKind(oldSteps.getValue(id))}" }
    (oldSteps.keys intersect newSteps.keys).forEach { id ->
        if (oldSteps.getValue(id).toNode() != newSteps.getValue(id).toNode())
            changes += "step changed: ${describeStepKind(newSteps.getValue(id))} (${id.take(8)}…)"
    }
    if (ow.triggers.map { it.toNode() } != nw.triggers.map { it.toNode() }) changes += "triggers changed"
    val oldTools = old.requiredTools.associateBy { it.name }
    val newTools = new.requiredTools.associateBy { it.name }
    (newTools.keys - oldTools.keys).forEach { changes += "required tool added: $it" }
    (oldTools.keys - newTools.keys).forEach { changes += "required tool removed: $it" }
    (oldTools.keys intersect newTools.keys).forEach {
        if (oldTools.getValue(it) != newTools.getValue(it)) changes += "required tool version changed: $it"
    }
    val oldScopes = old.permittedScopes.toSet()
    val newScopes = new.permittedScopes.toSet()
    ((newScopes - oldScopes).map { "scope added: $it" } +
        (oldScopes - newScopes).map { "scope removed: $it" }).forEach(changes::add)
    if (old.scriptRuntime != new.scriptRuntime) changes += "script runtime requirements changed"
    if (old.setupBindings != new.setupBindings) changes += "setup bindings changed"
    if (old.provenance != new.provenance) changes += "provenance changed"
    return changes
}

private fun describeStepKind(step: WorkflowStep): String = when (step) {
    is WorkflowStep.Tool -> "tool “${step.request.name}”"
    is WorkflowStep.Branch -> "branch"
    is WorkflowStep.Wait -> "wait"
    is WorkflowStep.Adaptive -> "adaptive (“${step.goal.take(40)}”)"
    is WorkflowStep.Script -> "on-phone script"
}

/**
 * A workflow the user admitted, with its admitted manifest. Occurrences
 * always run from [definition] — the admitted version. A behavior-change
 * update waits in [pendingUpdate] until approved; it never replaces the
 * running version by itself.
 */
data class AdmittedWorkflow(
    val definition: WorkflowDefinition,
    val manifest: WorkflowManifest,
    val pendingUpdate: WorkflowManifest? = null
)

sealed interface UpdateDecision {
    /** Documentation-only: applied immediately, still pinned to the new admitted version. */
    data class AutoApplied(val admitted: AdmittedWorkflow) : UpdateDecision
    /** Behavior change: stored as pending; the admitted version keeps running. */
    data class NeedsReview(val admitted: AdmittedWorkflow, val changes: List<String>) : UpdateDecision
    data class Rejected(val reason: String) : UpdateDecision
}

fun evaluateWorkflowUpdate(admitted: AdmittedWorkflow, newManifest: WorkflowManifest): UpdateDecision {
    if (newManifest.workflow.id != admitted.definition.id)
        return UpdateDecision.Rejected("Update targets a different workflow.")
    if (newManifest.manifestVersion > WORKFLOW_MANIFEST_VERSION)
        return UpdateDecision.Rejected(
            "Update needs a newer app version (manifest v${newManifest.manifestVersion}).")
    if (newManifest.workflow.version <= admitted.manifest.workflow.version)
        return UpdateDecision.Rejected(
            "Update version ${newManifest.workflow.version} is not newer than admitted v${admitted.manifest.workflow.version}.")
    return when (classifyWorkflowUpdate(admitted.manifest, newManifest)) {
        UpdateClass.DOCUMENTATION_ONLY -> {
            // Cosmetic only: keep the enabled state, adopt the new text.
            val def = newManifest.workflow.copy(enabled = admitted.definition.enabled)
            UpdateDecision.AutoApplied(admitted.copy(definition = def, manifest = newManifest, pendingUpdate = null))
        }
        UpdateClass.BEHAVIOR_CHANGE -> UpdateDecision.NeedsReview(
            admitted.copy(pendingUpdate = newManifest),
            diffWorkflowManifests(admitted.manifest, newManifest)
        )
    }
}

/** Approve the pending update: the new version becomes the admitted (pinned) one. */
fun approvePendingWorkflowUpdate(admitted: AdmittedWorkflow): AdmittedWorkflow {
    val pending = admitted.pendingUpdate ?: throw IllegalArgumentException("No pending update to approve.")
    val def = pending.workflow.copy(enabled = admitted.definition.enabled)
    return admitted.copy(definition = def, manifest = pending, pendingUpdate = null)
}

/** Reject the pending update: the admitted version keeps running, pending is dropped. */
fun rejectPendingWorkflowUpdate(admitted: AdmittedWorkflow): AdmittedWorkflow =
    admitted.copy(pendingUpdate = null)
