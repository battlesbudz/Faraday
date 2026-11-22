package com.battlesbudz.jarvis.v2.actions

/** The one policy contract consumed by action admission, dispatch and conversation guards. */
object ActionControlPolicy {
    enum class ApprovalClass { TASK_READ, EXACT_ACTION, BOUND_ACTION, NO_AGENT_AUTHORITY }
    enum class ActionClass { READ_ONLY, APP_MUTATION, EXTERNAL_EFFECT, CREDENTIAL, VERIFICATION_CODE, PAYMENT }
    enum class Disposition { AGENT_ACTION, NEEDS_EXACT_APPROVAL, HAND_BACK, TAKEOVER_OR_BLOCKED }

    /** Helper permission names are supplied by A1; A7 fixes only the declaration shape. */
    data class HelperPermissionList(val permissions: List<String>) {
        init {
            require(permissions.all { it.isNotBlank() }) { "Helper permission names must be nonblank." }
            require(permissions.distinct().size == permissions.size) { "Helper permission names must be unique." }
        }
    }

    private val exactApprovalTools = setOf(
        "screen_tap", "screen_scroll", "screen_type", "browse_submit", "run_in_sandbox",
        "post_notification", "send", "send_message", "send_email", "delete", "delete_item",
        "change_setting", "purchase", "pay", "checkout"
    )
    private val paymentActions = setOf("purchase", "pay", "make_payment", "payment", "checkout", "submit_payment")
    private val passwordActions = setOf("enter_password", "password_entry", "browse_login")
    private val codeRetrievalActions = setOf("retrieve_one_time_code", "read_verification_code", "get_otp")

    fun disposition(request: ActionRequest): Disposition {
        val name = request.name.lowercase()
        if (name in paymentActions || name.contains("payment") || hasPaymentMarker(request))
            return Disposition.TAKEOVER_OR_BLOCKED
        if (name in passwordActions || request.arguments.keys.any { isPasswordField(it) } ||
            request.arguments["field_type"]?.equals("password", ignoreCase = true) == true)
            return Disposition.HAND_BACK
        // The only dispatch route for code retrieval is the exact-approval claim.
        if (name in codeRetrievalActions) return Disposition.NEEDS_EXACT_APPROVAL
        if (name in exactApprovalTools || name in BROWSE_APPROVAL_TOOLS || name in SCREEN_MUTATION_TOOLS ||
            name in SANDBOX_APPROVAL_TOOLS) return Disposition.NEEDS_EXACT_APPROVAL
        return Disposition.AGENT_ACTION
    }

    fun approvalClass(request: ActionRequest): ApprovalClass = when {
        admissionBlockReason(request) != null -> ApprovalClass.NO_AGENT_AUTHORITY
        request.name == "browse_submit" -> ApprovalClass.BOUND_ACTION
        requiresExactApproval(request) -> ApprovalClass.EXACT_ACTION
        else -> ApprovalClass.TASK_READ
    }

    fun actionClass(request: ActionRequest): ActionClass {
        val name = request.name.lowercase()
        return when {
            name in paymentActions || name.contains("payment") || hasPaymentMarker(request) -> ActionClass.PAYMENT
            name in passwordActions || request.arguments.keys.any { isPasswordField(it) } -> ActionClass.CREDENTIAL
            request.arguments["field_type"]?.equals("password", ignoreCase = true) == true -> ActionClass.CREDENTIAL
            name in codeRetrievalActions -> ActionClass.VERIFICATION_CODE
            name in setOf("send", "send_message", "send_email", "post_notification", "delete", "delete_item") ->
                ActionClass.EXTERNAL_EFFECT
            name in SCREEN_MUTATION_TOOLS || name in setOf("change_setting", "purchase", "checkout") ->
                ActionClass.APP_MUTATION
            else -> ActionClass.READ_ONLY
        }
    }

    fun requiresExactApproval(request: ActionRequest): Boolean =
        disposition(request) == Disposition.NEEDS_EXACT_APPROVAL

    /** Text observed in an operated app is evidence only and cannot grant task authority. */
    fun screenContentCanAuthorizeTask(): Boolean = false

    /** Losing the helper's privilege ends the task; no main-display fallback is permitted. */
    fun mayContinueAfterPrivilegeLoss(): Boolean = false

    fun privilegeLossMessage(): String = "The app-control helper is unavailable. The task stopped."

    fun admissionBlockReason(request: ActionRequest): String? = when (disposition(request)) {
        Disposition.HAND_BACK -> "Password entry belongs to you. Please handle this step yourself."
        Disposition.TAKEOVER_OR_BLOCKED -> "Payment steps require your own verification and authorization; I can't perform them."
        else -> null
    }

    private fun isPasswordField(key: String): Boolean = key.lowercase() in
        setOf("password", "passwd", "passcode", "payment_password", "payment_pin")

    private fun isPaymentMarker(value: String): Boolean = value.equals("payment", ignoreCase = true)
    private fun hasPaymentMarker(request: ActionRequest): Boolean = request.arguments.any { (key, value) ->
        key in setOf("operation_class", "step_class") && isPaymentMarker(value)
    }
}
