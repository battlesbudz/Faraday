package com.battlesbudz.jarvis.v2.actions

/** Risk and execution contract attached to an entry in [MobileToolCatalog]. */
data class OperatedActionDeclaration(
    val risk: ActionControlPolicy.ActionClass,
    val approvalClass: ActionControlPolicy.ApprovalClass,
    /** Argument names whose values identify the destination or UI target for B3 grant binding. */
    val gateTargetFields: Set<String>,
    /** Argument names carrying user supplied content for B3 grant binding. */
    val contentFields: Set<String>,
    val batchable: Boolean,
    val routable: Boolean,
    val offScreenEligible: Boolean,
    /** D-10: at most one operated task may own the device at a time. */
    val maxConcurrentTasks: Int
) {
    fun isComplete(argumentNames: Set<String>): Boolean =
        maxConcurrentTasks > 0 && routable &&
            (gateTargetFields + contentFields).all { it in argumentNames } &&
            !(offScreenEligible && risk != ActionControlPolicy.ActionClass.READ_ONLY)

    /** S10 admission seam: a second operated task is never admitted. */
    fun permitsStart(activeOperatedTasks: Int): Boolean =
        activeOperatedTasks >= 0 && activeOperatedTasks < maxConcurrentTasks

    /** Turn planner seam: this declaration permits only a single action per batch. */
    fun permitsBatch(actionCount: Int): Boolean = actionCount == 1 || (batchable && actionCount > 1)

    /** Router and background-admission seams read these declared capabilities. */
    fun mayRoute(): Boolean = routable
    fun mayRunOffScreen(): Boolean = offScreenEligible

    /** B3 binding seam: retain only declared destination/content fields from a request. */
    fun grantBinding(request: ActionRequest): Map<String, String> = buildMap {
        (gateTargetFields + contentFields).forEach { field ->
            request.arguments[field]?.let { put(field, it) }
        }
    }

    companion object {
        internal fun forCatalogTool(tool: MobileToolCatalog.Tool): OperatedActionDeclaration {
            val name = tool.name
            val approval = when {
                ActionControlPolicy.admissionBlockReason(ActionRequest(name)) != null ->
                    ActionControlPolicy.ApprovalClass.NO_AGENT_AUTHORITY
                name == "browse_submit" -> ActionControlPolicy.ApprovalClass.BOUND_ACTION
                name in exactApprovalNames -> ActionControlPolicy.ApprovalClass.EXACT_ACTION
                else -> ActionControlPolicy.ApprovalClass.TASK_READ
            }
            val risk = when {
                name == "browse_login" -> ActionControlPolicy.ActionClass.CREDENTIAL
                name == "run_in_sandbox" -> ActionControlPolicy.ActionClass.APP_MUTATION
                name in externalEffectNames -> ActionControlPolicy.ActionClass.EXTERNAL_EFFECT
                name in appMutationNames -> ActionControlPolicy.ActionClass.APP_MUTATION
                else -> ActionControlPolicy.ActionClass.READ_ONLY
            }
            val args = tool.parameters.map { it.name }.toSet()
            val targetFields = args intersect setOf("app", "url", "screen", "destination", "target", "field")
            val contentFields = args intersect setOf("action", "direction", "text", "title", "message", "command", "level", "at_ms")
            return OperatedActionDeclaration(
                risk = risk,
                approvalClass = approval,
                gateTargetFields = targetFields,
                contentFields = contentFields,
                batchable = false,
                routable = true,
                offScreenEligible = risk == ActionControlPolicy.ActionClass.READ_ONLY,
                maxConcurrentTasks = 1
            )
        }

        /** Reject missing or malformed metadata at the same seam used by catalog registration. */
        internal fun requireComplete(
            tool: MobileToolCatalog.Tool,
            declaration: OperatedActionDeclaration?
        ): OperatedActionDeclaration {
            require(declaration != null && declaration.isComplete(tool.parameters.map { it.name }.toSet())) {
                "Operated action '${tool.name}' requires a complete declaration."
            }
            return declaration
        }

        private val exactApprovalNames = setOf(
            "screen_tap", "screen_scroll", "screen_type", "browse_submit", "run_in_sandbox", "post_notification"
        )
        private val externalEffectNames = setOf("post_notification")
        private val appMutationNames = setOf(
            "open_app", "set_volume", "media_control", "open_website", "open_settings", "navigate",
            "browse_click", "browse_fill", "browse_back", "browse_forward", "browse_handoff",
            "screen_tap", "screen_scroll", "screen_type", "create_reminder"
        )
    }
}
