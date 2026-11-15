package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Route B (Option B) gating table: every exclusion, and the one eligible shape. */
class RouteBPrimingPolicyTest {
    private fun eligibleShape() = RouteBPrimingPolicy.Shape(
        directAudioTurn = true,
        sealedAudioAvailable = true,
        comparisonActive = false,
        incrementalGemmaInput = true,
        toolsEnabledForTurn = false,
        toolPolicyTransitionPending = false,
        snapshotImageAttached = false,
        speculationLanePresent = false,
        sessionAvailable = true,
        schedulerAdmitted = true
    )

    private fun verdict(shape: RouteBPrimingPolicy.Shape) = RouteBPrimingPolicy.evaluate(shape)

    @Test fun fullyEligibleShapePrimes() {
        assertEquals(RouteBPrimingPolicy.Verdict.Eligible, verdict(eligibleShape()))
    }

    @Test fun productionCatalogShapeStaysSealedForTools() {
        // The shape production actually runs today: every catalog Gemma is
        // tool-capable and generation enables tools for every direct-audio
        // turn, so priming must stay off until a tools-disabled audio
        // configuration exists. This test is the dormancy contract.
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.TOOLS_ENABLED_FOR_TURN),
            verdict(eligibleShape().copy(toolsEnabledForTurn = true))
        )
    }

    @Test fun nonAudioTurnIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.NOT_DIRECT_AUDIO),
            verdict(eligibleShape().copy(directAudioTurn = false))
        )
    }

    @Test fun comparisonTrialIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.COMPARISON_TRIAL),
            verdict(eligibleShape().copy(comparisonActive = true))
        )
    }

    @Test fun missingSealedAudioIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.NO_SEALED_AUDIO),
            verdict(eligibleShape().copy(sealedAudioAvailable = false))
        )
    }

    @Test fun nonIncrementalModelIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.MODEL_NOT_INCREMENTAL),
            verdict(eligibleShape().copy(incrementalGemmaInput = false))
        )
    }

    @Test fun pendingToolPolicyTransitionIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.TOOL_POLICY_TRANSITION_PENDING),
            verdict(eligibleShape().copy(toolPolicyTransitionPending = true))
        )
    }

    @Test fun snapshotTurnIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.SNAPSHOT_IMAGE_ATTACHED),
            verdict(eligibleShape().copy(snapshotImageAttached = true))
        )
    }

    @Test fun speculationLaneIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.SPECULATION_LANE_PRESENT),
            verdict(eligibleShape().copy(speculationLanePresent = true))
        )
    }

    @Test fun unavailableSessionIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.SESSION_UNAVAILABLE),
            verdict(eligibleShape().copy(sessionAvailable = false))
        )
    }

    @Test fun schedulerRefusalIsExcluded() {
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.SCHEDULER_NOT_ADMITTED),
            verdict(eligibleShape().copy(schedulerAdmitted = false))
        )
    }

    @Test fun earliestReasonWins() {
        // Identity exclusions precede parity, which precedes coexistence:
        // diagnostics must name the most fundamental reason a turn stayed sealed.
        val shape = eligibleShape().copy(
            directAudioTurn = false, toolsEnabledForTurn = true, speculationLanePresent = true
        )
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.NOT_DIRECT_AUDIO),
            verdict(shape)
        )
        val parityFirst = eligibleShape().copy(toolsEnabledForTurn = true, speculationLanePresent = true)
        assertEquals(
            RouteBPrimingPolicy.Verdict.Ineligible(RouteBPrimingPolicy.Reason.TOOLS_ENABLED_FOR_TURN),
            verdict(parityFirst)
        )
    }
}
