package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M7 external-agent access (D06, D11-D17, T24): narrowly scoped Jarvis
 * functions exposed to authorized external agents through a protected
 * provider boundary.
 *
 * Direction: this is the mirror of M3's consumer-side provider dispatch.
 * There, Jarvis calls *other* providers' functions; here, *external agents*
 * (other on-device agents/apps) call a fixed narrow set of Jarvis's own
 * functions. The boundary enforces, in admission order:
 *
 * 1. Caller authorization — the user explicitly authorized this caller
 *    (opt-in per caller). No grant means no access, and a grant never
 *    covers more than its listed functions.
 * 2. Narrow scope — the exposed set is fixed in code ([ExternalAgentFunctions.EXPOSED]).
 *    A persisted grant can never exceed it (the store validates this
 *    statically, T08-style); anything outside it is rejected at the
 *    boundary, never dispatched.
 * 3. No session-grant inheritance — external calls are admitted only under
 *    the caller's own grant plus the user's family-level
 *    denial/revocation state. Session-scoped grants (screen sessions,
 *    exact approvals) are never consulted and never inherited: the exposed
 *    set contains no session-gated or D11 action-class functions, and a
 *    defensive check rejects them even if the set were misconfigured.
 * 4. Anti-recursion — external calls are single-level. A call arriving
 *    while another external call is dispatching is rejected with
 *    [ExternalDenialCode.RECURSION_BOUNDED]; external callers get single
 *    calls, never journeys.
 * 5. D11 cannot be evaded — no D11 action-class function is exposable, so
 *    there is no external path around individual approval.
 *
 * Every admitted call is recorded in the shared task ledger with
 * [ToolAuthority.EXTERNAL_AGENT] and the authorizing grant's id, so the
 * user sees exactly what each external agent did in the same receipt
 * history as everything else. No separate permission system, no competing
 * task queue.
 */
object ExternalAgentFunctions {
    /**
     * The narrow fixed set of Jarvis functions an external agent may ever
     * call. Read-only reads, local low-risk controls, and one user-visible
     * notification channel. Deliberately absent:
     * - screen_* — screen mutations need an admitted session grant (M1c);
     *   screen_observe reads private screen content. External agents never
     *   inherit session grants.
     * - browse_submit / browse_login — D11 action classes needing exact
     *   individual approval; there is no external path around approval.
     * - open_app / open_website / open_settings / navigate — launch and
     *   navigation surface beyond the narrow use cases.
     * - create_reminder — schedules future autonomous effects; kept
     *   user/model-initiated only.
     */
    val EXPOSED: Set<String> = setOf(
        "read_battery",
        "show_schedule",
        "media_control",
        "set_volume",
        "post_notification"
    )

    private val CALLER_ID_PATTERN = Regex("[a-z0-9._-]{1,48}")

    /** Caller ids are package-style, sanitized to `[a-z0-9._-]{1,48}`. */
    fun isValidCallerId(callerId: String): Boolean = callerId.matches(CALLER_ID_PATTERN)

    fun sanitizeCallerId(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9._-]"), "_").trim('_', '.', '-')
        require(cleaned.matches(CALLER_ID_PATTERN)) { "Caller id '$raw' is not usable." }
        return cleaned
    }

    fun isExposed(function: String): Boolean = function in EXPOSED
}

/**
 * One user's authorization of one external caller. Persisted in the task
 * journal; a hand-edited journal entry claiming functions outside
 * [ExternalAgentFunctions.EXPOSED] fails closed at decode time.
 */
data class ExternalAgentGrant(
    val id: String,
    val callerId: String,
    val displayName: String,
    val allowedFunctions: Set<String>,
    val authorizedAtMs: Long,
    val enabled: Boolean = true,
    val revoked: Boolean = false
)

enum class ExternalDenialCode {
    CALLER_UNKNOWN,
    CALLER_DISABLED,
    CALLER_REVOKED,
    FUNCTION_NOT_EXPOSED,
    FUNCTION_NOT_GRANTED,
    D11_GATED,
    INVALID_ARGUMENTS,
    RECURSION_BOUNDED,
    DENIED_PERMISSION
}

sealed interface ExternalAgentCallResult {
    /** The call was admitted and executed; the receipt is the honest outcome. */
    data class Executed(val result: ExecutionResult, val attemptId: String) : ExternalAgentCallResult
    /** Nothing was dispatched. The message is safe to hand back to the caller. */
    data class Denied(val code: ExternalDenialCode, val message: String) : ExternalAgentCallResult
}

/**
 * Executes one validated external-agent action. Implementations never see
 * caller identities or grants — admission already happened. On-device this
 * is the native action executor; in tests it is a fake.
 */
fun interface ExternalActionExecutor {
    fun execute(action: MobileAction): ExecutionResult
}

/**
 * The protected provider boundary for external agents (M7, T24).
 *
 * Constructed with the shared task ledger so grants, attempts and receipts
 * live in the same durable journal as user and routine work.
 */
class ExternalAgentAccess(
    private val ledger: ToolTaskLedger,
    private val executor: ExternalActionExecutor,
    private val now: () -> Long = System::currentTimeMillis,
    private val validator: MobileActionValidator = MobileActionValidator()
) {
    /** Anti-recursion fence: external calls are single-level per thread. */
    private val dispatchDepth = ThreadLocal.withInitial { 0 }

    /** Platform type from ThreadLocal.get(); the Elvis keeps the read null-safe and warning-clean. */
    private fun depthNow(): Int = dispatchDepth.get() ?: 0

    /**
     * Authorize (or re-authorize) an external caller. The user opts a caller
     * in explicitly; the grant covers only the listed functions, which must
     * all be in [ExternalAgentFunctions.EXPOSED]. Re-authorizing replaces
     * the caller's previous grant — narrowing is immediate, and a revoked
     * grant can never be silently widened by re-authorization with broader
     * functions (those are rejected here).
     */
    fun authorizeCaller(callerId: String, displayName: String, functions: Set<String>): ExternalAgentGrant {
        val id = ExternalAgentFunctions.sanitizeCallerId(callerId)
        require(displayName.isNotBlank() && displayName.length <= 128) {
            "A caller display name of 1 to 128 characters is required."
        }
        require(functions.isNotEmpty() && functions.all { ExternalAgentFunctions.isExposed(it) }) {
            "External grants cover only the exposed functions: ${ExternalAgentFunctions.EXPOSED.sorted().joinToString()}."
        }
        val grant = ExternalAgentGrant(UUID.randomUUID().toString(), id, displayName.trim(),
            functions.toSet(), now())
        ledger.store.updateJournal { j ->
            val superseded = j.externalAgentGrants.filter { it.callerId == id }
            j.copy(externalAgentGrants = j.externalAgentGrants - superseded.toSet() + grant)
        }
        return grant
    }

    /** Revoke a caller's authorization; in-flight calls keep their receipts. */
    fun revokeCaller(callerId: String): Boolean {
        val id = ExternalAgentFunctions.sanitizeCallerId(callerId)
        var changed = false
        ledger.store.updateJournal { j ->
            val grant = j.externalAgentGrants.find { it.callerId == id && !it.revoked } ?: return@updateJournal j
            changed = true
            j.copy(externalAgentGrants = j.externalAgentGrants.map {
                if (it.id == grant.id) it.copy(revoked = true) else it
            })
        }
        return changed
    }

    /** Temporarily disable a caller without revoking its authorization. */
    fun setCallerEnabled(callerId: String, enabled: Boolean): Boolean {
        val id = ExternalAgentFunctions.sanitizeCallerId(callerId)
        var changed = false
        ledger.store.updateJournal { j ->
            val grant = j.externalAgentGrants.find { it.callerId == id && !it.revoked } ?: return@updateJournal j
            if (grant.enabled == enabled) return@updateJournal j
            changed = true
            j.copy(externalAgentGrants = j.externalAgentGrants.map {
                if (it.id == grant.id) it.copy(enabled = enabled) else it
            })
        }
        return changed
    }

    fun grants(): List<ExternalAgentGrant> = ledger.journal().externalAgentGrants

    /**
     * Admit and execute one external-agent call. Admission order: recursion
     * fence, caller authorization, narrow-scope check, defensive D11 gate,
     * argument validation, user-level denial/revocation, ledger claim, then
     * exactly one executor invocation. A denial at any step dispatches
     * nothing and returns an honest receipt.
     */
    fun call(callerId: String, request: ActionRequest): ExternalAgentCallResult {
        // Anti-recursion: an external call may never re-enter the boundary.
        if (depthNow() > 0) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.RECURSION_BOUNDED,
                "External agent calls cannot nest: this call arrived while another external call was dispatching.")
        }
        val id = try {
            ExternalAgentFunctions.sanitizeCallerId(callerId)
        } catch (_: IllegalArgumentException) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.CALLER_UNKNOWN,
                "This caller is not authorized to use Jarvis functions.")
        }
        val grant = grants().find { it.callerId == id }
            ?: return ExternalAgentCallResult.Denied(ExternalDenialCode.CALLER_UNKNOWN,
                "This caller is not authorized to use Jarvis functions. Ask the user to authorize it first.")
        if (grant.revoked) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.CALLER_REVOKED,
                "This caller's access was revoked.")
        }
        if (!grant.enabled) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.CALLER_DISABLED,
                "This caller's access is currently disabled.")
        }
        if (!ExternalAgentFunctions.isExposed(request.name)) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.FUNCTION_NOT_EXPOSED,
                "'${request.name}' is not available to external agents.")
        }
        if (request.name !in grant.allowedFunctions) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.FUNCTION_NOT_GRANTED,
                "This caller is not authorized for '${request.name}'.")
        }
        // Defensive D11/session gate: no session-gated or approval-gated
        // function is ever dispatchable here, even if EXPOSED were
        // misconfigured. External agents never inherit session grants and
        // never bypass individual approval.
        if (request.name in SCREEN_MUTATION_TOOLS || request.name in BROWSE_APPROVAL_TOOLS) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.D11_GATED,
                "'${request.name}' needs an approval or session grant that external agents can never hold.")
        }
        val action = when (val validation = validator.validate(request)) {
            is ActionValidation.Valid -> validation.action
            is ActionValidation.Rejected -> return ExternalAgentCallResult.Denied(
                ExternalDenialCode.INVALID_ARGUMENTS,
                "${validation.reason} Nothing was dispatched.")
        }
        // The user's own denial/revocation blocks external callers too —
        // external agents get no access the user has taken away.
        val sourceAccess = ToolSourceAccess(ledger)
        val denial = sourceAccess.denial(request)
        if (denial != null) {
            return ExternalAgentCallResult.Denied(ExternalDenialCode.DENIED_PERMISSION, denial.message)
        }
        // Record intent in the shared ledger before the effect, with the
        // external authority and the authorizing grant's provenance.
        val attempt = ledger.create(request.frozen(), ToolTaskState.QUEUED, ToolAuthority.EXTERNAL_AGENT, grant.id)
        val running = ledger.transition(attempt.id, attempt.generation, ToolTaskState.RUNNING)
            ?: return ExternalAgentCallResult.Denied(ExternalDenialCode.DENIED_PERMISSION,
                "Access changed before this call could run. Nothing was dispatched.")
        val result = try {
            dispatchDepth.set(depthNow() + 1)
            executor.execute(action)
        } finally {
            dispatchDepth.set(depthNow() - 1)
        }
        ledger.finish(running, result)
        return ExternalAgentCallResult.Executed(result, attempt.id)
    }
}
