package com.battlesbudz.jarvis.v2.actions

/**
 * Finding 3 (background-launch reporting): what Jarvis actually observed
 * when launching another app, reported distinctly in receipts and
 * diagnostics.
 *
 * Four states, never conflated:
 * - local refusal: Jarvis never asked Android to launch anything because no
 *   supported route was established;
 * - submitted request: the launch was handed to the platform on a
 *   background route, but the destination was never observed in the
 *   foreground — reported as unknown completion, never as success;
 * - platform rejection: startActivity threw (ActivityNotFound,
 *   SecurityException);
 * - verified launch: screenshot+vision verification confirmed the
 *   opening after the request (ScreenshotLaunchVerifier: ~1s settle,
 *   expected package observed in the foreground, a real screenshot
 *   captured, and the existing NativeVision stack analyzed it with
 *   non-blank pixels). A clean startActivity return on any route —
 *   DIRECT included — is only a submitted request: the call performs no
 *   observation, and no return value verifies an opening. Unverified
 *   launches keep the unconfirmed receipt.
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
 * - verified launch -> SUCCEEDED: screenshot+vision verification
 *   confirmed the opening (ScreenshotLaunchVerifier returned Verified).
 *   A clean startActivity return alone never verifies a launch on any
 *   route — there is no clean-return heuristic anymore;
 * - submitted without verification -> UNKNOWN_COMPLETION: the request
 *   was handed to Android, but opening could not be confirmed. Journals
 *   record this as unknown outcome, workflows stop without repeating
 *   it, and later steps never inherit its success.
 *
 * A background-route submission is never promoted to a verified launch.
 * Callers that verified the opening through ScreenshotLaunchVerifier pass
 * its result; production background paths that could not verify leave it
 * unconfirmed and report honestly.
 */
fun verifiedLaunchReceipt(
    label: String,
    route: BackgroundLaunchRoute,
    platformError: String?,
    verification: LaunchVerification
): ExecutionResult {
    if (platformError != null) {
        return ExecutionResult(
            ExecutionResult.Outcome.FAILED,
            "Could not open $label: $platformError"
        )
    }
    return when (verification) {
        LaunchVerification.Verified -> {
            // Screenshot+vision verification confirmed the opening: the
            // only state that counts as a verified launch.
            ExecutionResult(ExecutionResult.Outcome.SUCCEEDED, "Opened $label.")
        }
        is LaunchVerification.Unconfirmed -> {
            // No platform error, but the opening was never verified. The
            // request was handed to Android; whether it opened is unknown.
            // This must not read as success anywhere: the journal, the
            // workflow engine and the user report each treat unknown
            // completion as unconfirmed. The verification reason is
            // diagnostic-only; the receipt wording stays stable.
            ExecutionResult(
                ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                "The launch request for $label was sent, but opening could not be confirmed."
            )
        }
    }
}

/**
 * Pure-function form of [verifiedLaunchReceipt] for callers that already
 * observed (or did not observe) the destination in the foreground
 * themselves, without running a verifier — e.g. receipt unit tests.
 * `foregroundObserved = true` is a caller-asserted observation, not a
 * clean-return inference: nothing in production passes true from a
 * startActivity return.
 */
fun verifiedLaunchReceipt(
    label: String,
    route: BackgroundLaunchRoute,
    platformError: String?,
    foregroundObserved: Boolean = false
): ExecutionResult = verifiedLaunchReceipt(
    label,
    route,
    platformError,
    if (foregroundObserved) LaunchVerification.Verified
    else LaunchVerification.Unconfirmed("foreground not observed")
)

/**
 * Receipt for a view intent (OpenWebsite / OpenSettings / Navigate). A
 * verified launch proves only that the handler package reached the
 * foreground with non-blank pixels — not that the requested URL, settings
 * screen, or navigation destination opened. Maps can sit on an onboarding or
 * error screen, the browser on an error page or a pre-existing tab, while
 * satisfying every leg of launch verification, so a verified handler-open
 * stays UNKNOWN_COMPLETION: the requested destination is unconfirmed, and
 * the turn stops instead of continuing a chain on a destination it did not
 * prove. Failures and unconfirmed launches pass through unchanged. There is
 * no destination-specific evidence in this codebase (no OCR, no URL check)
 * that could promote this to success.
 */
fun viewIntentReceipt(launchResult: ExecutionResult, label: String): ExecutionResult {
    if (!launchResult.succeeded) return launchResult
    return ExecutionResult(
        ExecutionResult.Outcome.UNKNOWN_COMPLETION,
        "The handler for $label opened, but the requested destination could not be confirmed."
    )
}

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
