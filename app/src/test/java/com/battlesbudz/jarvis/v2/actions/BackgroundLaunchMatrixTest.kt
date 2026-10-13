package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.actions.ExecutionResult.Outcome
import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 3 (background-launch reporting) matrix, corrected: a submitted
 * request is NEVER a verified launch. The route decision and the receipt
 * wording are JVM-pure and tested here for every combination: foreground,
 * selected assistant, granted/revoked overlay, unavailable assistant
 * binding, and denied/no-supported-route. Only an observed foreground
 * transition counts as a verified launch; a submitted request is reported
 * honestly as unconfirmed (unknown completion) so journals, workflows and
 * user reports never promote submission to success.
 */
class BackgroundLaunchMatrixTest {

    // Route matrix: resolveBackgroundLaunchRoute.

    @Test fun foregroundActivityLaunchesDirectly() {
        assertEquals(
            BackgroundLaunchRoute.DIRECT,
            resolveBackgroundLaunchRoute(visible = true, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun selectedAssistantRouteWhenBackgrounded() {
        assertEquals(
            BackgroundLaunchRoute.SELECTED_ASSISTANT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = true, overlayExempt = false)
        )
    }

    @Test fun overlayExemptRouteWhenGranted() {
        assertEquals(
            BackgroundLaunchRoute.OVERLAY_EXEMPT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = true)
        )
    }

    @Test fun revokedOverlayWithNoAssistantIsLocalRefusal() {
        assertEquals(
            BackgroundLaunchRoute.NONE,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun unavailableAssistantBindingFallsBackToOverlayOrRefusal() {
        // Binding unavailable + overlay granted -> overlay-exempt route.
        assertEquals(
            BackgroundLaunchRoute.OVERLAY_EXEMPT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = true)
        )
        // Binding unavailable + overlay revoked -> no supported route.
        assertEquals(
            BackgroundLaunchRoute.NONE,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun foregroundBeatsOtherRoutes() {
        assertEquals(
            BackgroundLaunchRoute.DIRECT,
            resolveBackgroundLaunchRoute(visible = true, assistantBindingAvailable = true, overlayExempt = true)
        )
    }

    // Receipt wording: local refusal.

    @Test fun localRefusalWordingIsHonest() {
        val receipt = backgroundLaunchRefusal("Example App")
        assertEquals(Outcome.FAILED, receipt.outcome)
        assertFalse("a local refusal is not success", receipt.succeeded)
        assertTrue(
            "must use the honest refusal wording: ${receipt.message}",
            receipt.message.contains("Jarvis could not establish a supported background-launch route")
        )
        assertFalse(
            "must never present an inferred Android block as observed: ${receipt.message}",
            receipt.message.contains("Android blocked the background launch")
        )
        assertFalse(
            "must not imply the user must grant overlay permission: ${receipt.message}",
            receipt.message.contains("Display over other apps")
        )
    }

    // Receipts: submitted/unverified vs observed vs platform rejection.
    // A submitted request is unknown completion — never success.

    @Test fun submittedLaunchWithoutObservationIsUnverified() {
        val submitted = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = false
        )
        assertEquals(
            "a no-exception submission without foreground evidence stays submitted/unverified",
            Outcome.UNKNOWN_COMPLETION, submitted.outcome
        )
        assertFalse("a submitted request is not a verified launch", submitted.succeeded)
        assertTrue(
            "must say the request was sent and opening could not be confirmed: ${submitted.message}",
            submitted.message.contains("was sent") && submitted.message.contains("could not be confirmed")
        )
    }

    @Test fun observedForegroundTransitionIsTheOnlyVerifiedLaunch() {
        val observed = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = true
        )
        assertEquals(Outcome.SUCCEEDED, observed.outcome)
        assertTrue("an observed foreground transition is a verified launch", observed.succeeded)
        assertEquals("Opened Example App.", observed.message)
    }

    @Test fun observedOpeningYieldsCompletionExactlyOnce() {
        // The receipt is a pure function of its inputs: computing it again
        // — as a delayed or duplicate observation callback would — yields
        // the identical single completion and performs no side effect, so
        // the launch is never repeated by re-computation.
        val first = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = true
        )
        val second = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = true
        )
        assertEquals(Outcome.SUCCEEDED, first.outcome)
        assertEquals(first.outcome, second.outcome)
        assertEquals(first.message, second.message)
    }

    @Test fun delayedOrDuplicateUnobservedSubmissionNeverPromotesToSuccess() {
        // A late duplicate of an unobserved submission stays unconfirmed:
        // re-computing the receipt must not promote it to success.
        val submitted = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = false
        )
        val duplicate = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null, foregroundObserved = false
        )
        assertEquals(Outcome.UNKNOWN_COMPLETION, submitted.outcome)
        assertEquals(submitted.outcome, duplicate.outcome)
        assertFalse(duplicate.succeeded)
    }

    @Test fun platformRejectionIsDistinctFromLocalRefusal() {
        val rejected = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = "Activity not found", foregroundObserved = false
        )
        assertEquals(Outcome.FAILED, rejected.outcome)
        assertFalse(rejected.succeeded)
        assertTrue(
            "a platform rejection names the platform error: ${rejected.message}",
            rejected.message.contains("Activity not found")
        )
        assertFalse(
            "a platform rejection is not a local refusal: ${rejected.message}",
            rejected.message.contains("could not establish a supported background-launch route")
        )
        val refused = backgroundLaunchRefusal("Example App")
        assertFalse(
            "a local refusal is not a platform rejection: ${refused.message}",
            refused.message.contains("Activity not found")
        )
    }

    @Test fun overlayExemptSubmittedIsUnverified() {
        val receipt = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.OVERLAY_EXEMPT,
            platformError = null, foregroundObserved = false
        )
        assertEquals(
            "a submitted overlay-exempt request is unconfirmed, not success",
            Outcome.UNKNOWN_COMPLETION, receipt.outcome
        )
        assertFalse(receipt.succeeded)
    }

    @Test fun selectedAssistantSubmissionFollowsTheSameContract() {
        val submitted = assistantSubmittedLaunchReceipt("Example App")
        assertEquals(
            "an assistant-route submission is unconfirmed, not success",
            Outcome.UNKNOWN_COMPLETION, submitted.outcome
        )
        assertFalse(submitted.succeeded)
        assertTrue(
            "must say the request was sent and opening could not be confirmed: ${submitted.message}",
            submitted.message.contains("was sent") && submitted.message.contains("could not be confirmed")
        )
        val rejected = assistantRejectedLaunchReceipt("Example App", "SecurityException: denied")
        assertEquals(Outcome.FAILED, rejected.outcome)
        assertFalse(rejected.succeeded)
        assertTrue(
            "an assistant rejection names the platform error: ${rejected.message}",
            rejected.message.contains("SecurityException: denied")
        )
        assertFalse(
            "an assistant rejection is not a local refusal: ${rejected.message}",
            rejected.message.contains("could not establish a supported background-launch route")
        )
    }
}
