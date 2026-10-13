package com.battlesbudz.jarvis.v2.actions

/**
 * Pre-journal credential boundary.
 *
 * [JournaledActionPipeline] persists the request before dispatch and
 * [ToolTaskStore] writes it verbatim, so a secret sitting in the request
 * arguments would land on disk. The boundary sits before that write: an
 * executor that dispatches secrets names the argument keys that must never
 * be persisted, the pipeline journals a redacted copy, and the live
 * dispatch still uses the original request. A redacted value that is ever
 * re-dispatched (restart recovery, approval replay) fails closed — the
 * remote end rejects the marker — instead of leaking the secret.
 */
object CredentialBoundary {
    /** Non-reversible marker replacing a secret argument value in the journal. */
    const val REDACTED = "••••••••"

    /** The journal-safe copy of [request] with [secretKeys] redacted. */
    fun redactForJournal(request: ActionRequest, secretKeys: Set<String>): ActionRequest {
        if (secretKeys.isEmpty()) return request
        return request.copy(arguments = request.arguments.mapValues { (key, value) ->
            if (key in secretKeys) REDACTED else value
        })
    }
}

/**
 * Implemented by executors that dispatch requests carrying secrets. The
 * returned keys name [ActionRequest.arguments] entries whose values must
 * never be persisted; [JournaledActionPipeline] redacts them pre-journal.
 * When the executor cannot prove an argument is not a secret, it must name
 * it — redaction is the conservative default.
 */
interface SecretAwareExecutor {
    fun secretArgumentKeys(request: ActionRequest): Set<String>
}
