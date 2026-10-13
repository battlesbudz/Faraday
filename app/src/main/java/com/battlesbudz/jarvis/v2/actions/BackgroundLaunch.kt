package com.battlesbudz.jarvis.v2.actions

/**
 * Finding 3 (background-launch reporting): what Jarvis actually observed
 * when launching another app, reported distinctly in receipts and
 * diagnostics.
 *
 * Four states, never conflated:
 * - local refusal: Jarvis never asked Android to launch anything because no
 *   supported route was established;
 * - submitted request: the launch was handed to the platform, but the
 *   destination was never observed in the foreground — reported as unknown
 *   completion, never as success;
 * - platform rejection: startActivity threw (ActivityNotFound,
 *   SecurityException);
 * - observed foreground transition: the destination package was seen in the
 *   foreground after the request — the only state that counts as a verified
 *   launch. startActivity returning is not enough: a background start can be
 *   silently dropped by Android's background-activity-start restrictions.
 *
 * The two recognized routes (selected assistant, overlay exemption) are not
 * an exhaustive statement of Android's background-start exceptions, so the
 * refusal wording must never imply the user must grant overlay permission
 * when Android might permit another route, and must never present an
 * inferred diagnosis ("Android blocked the background launch") as an
 * observed platform result.
 */
enum class BackgroundLaunchRoute {
    /** Jarvis's own activity is visible: a direct startActivity. */
    DIRECT,
    /** The selected-assistant VoiceInteractionService binding launches. */
    SELECTED_ASSISTANT,
    /** A background-activity-start exemption applies (overlay grant). */
    OVERLAY_EXEMPT,
    /** No supported route: Jarvis refuses locally without asking Android. */
    NONE
}

/**
 * Pure route decision behind AndroidMobileActionExecutor's launch path.
 * JVM-testable; the executor wires the real Android checks into these
 * three booleans.
 */
fun resolveBackgroundLaunchRoute(
    visible: Boolean,
    assistantBindingAvailable: Boolean,
    overlayExempt: Boolean
): BackgroundLaunchRoute = when {
    visible -> BackgroundLaunchRoute.DIRECT
    assistantBindingAvailable -> BackgroundLaunchRoute.SELECTED_ASSISTANT
    overlayExempt -> BackgroundLaunchRoute.OVERLAY_EXEMPT
    else -> BackgroundLaunchRoute.NONE
}

/**
 * Local-refusal receipt: Jarvis could not establish a supported
 * background-launch route, so nothing was attempted. Worded as Jarvis's own
 * limitation — never as an observed Android block, and never implying the
 * user must grant overlay permission when Android might permit another
 * route.
 */
fun backgroundLaunchRefusal(label: String): ExecutionResult =
    ExecutionResult(
        false,
        "Jarvis could not establish a supported background-launch route for $label, " +
            "so the launch was not attempted."
    )

/**
 * Receipt for a launch that reached the platform. The four outcomes stay
 * distinct end to end:
 * - platform rejection -> FAILED: Android threw;
 * - observed foreground transition -> SUCCEEDED: the destination was
 *   actually seen in the foreground, the only verified launch;
 * - submitted without observation -> UNKNOWN_COMPLETION: the request was
 *   handed to Android, but opening could not be confirmed. Journals record
 *   this as unknown outcome, workflows stop without repeating it, and later
 *   steps never inherit its success.
 *
 * A submitted request is never promoted to a verified launch. Callers that
 * can observe the destination (e.g. instrumentation via UiDevice) pass
 * foregroundObserved = true; production paths that cannot observe leave it
 * false and report honestly.
 */
fun verifiedLaunchReceipt(
    label: String,
    route: BackgroundLaunchRoute,
    platformError: String?,
    foregroundObserved: Boolean = false
): ExecutionResult {
    if (platformError != null) {
        return ExecutionResult(
            ExecutionResult.Outcome.FAILED,
            "Could not open $label: $platformError"
        )
    }
    if (foregroundObserved) {
        // The destination was actually seen in the foreground after the
        // request: the only state that counts as a verified launch.
        return ExecutionResult(ExecutionResult.Outcome.SUCCEEDED, "Opened $label.")
    }
    // No platform error, but the destination was never observed. The request
    // was handed to Android; whether it opened is unknown. This must not
    // read as success anywhere: the journal, the workflow engine and the
    // user report each treat unknown completion as unconfirmed.
    return ExecutionResult(
        ExecutionResult.Outcome.UNKNOWN_COMPLETION,
        "The launch request for $label was sent, but opening could not be confirmed."
    )
}

/**
 * Selected-assistant route receipt. The assistant binding's startActivity
 * cannot observe the destination either, so it follows the same contract:
 * a handed-off request is submitted/unverified, a throw is a platform
 * rejection. JVM-pure so the contract is unit-tested; the service delegates
 * to these.
 */
fun assistantSubmittedLaunchReceipt(label: String): ExecutionResult =
    ExecutionResult(
        ExecutionResult.Outcome.UNKNOWN_COMPLETION,
        "The launch request for $label was sent through the Jarvis assistant, " +
            "but opening could not be confirmed."
    )

fun assistantRejectedLaunchReceipt(label: String, error: String): ExecutionResult =
    ExecutionResult(
        ExecutionResult.Outcome.FAILED,
        "Android rejected the assistant launch of $label: $error"
    )
