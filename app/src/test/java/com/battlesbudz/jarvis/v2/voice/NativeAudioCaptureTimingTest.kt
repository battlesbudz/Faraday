package com.battlesbudz.jarvis.v2.voice

import com.google.ai.edge.litertlm.NativeAudioTimingPhase
import com.google.ai.edge.litertlm.NativeAudioTimingReceipt
import com.google.ai.edge.litertlm.NativeNanoInterval
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar fixtures test clock/provenance math, never native graph work. */
class NativeAudioCaptureTimingTest {
    @Test fun finalReceiptElapsedBoundsPreserveSignedEndpointAndConservativeAlignment() {
        val metrics = timing().metrics()!!
        assertEquals(2999.9998, metrics.lastStepFromAdmissionLowerMs!!, 0.0000001)
        assertEquals(3000.0002, metrics.lastStepFromAdmissionUpperMs!!, 0.0000001)
        assertEquals(2499.9998, metrics.firstToLastStepLowerMs!!, 0.0000001)
        assertEquals(2500.0002, metrics.firstToLastStepUpperMs!!, 0.0000001)
        assertEquals(-500.0001, metrics.endpointToLastStepLowerMs!!, 0.0000001)
        assertEquals(-499.9999, metrics.endpointToLastStepUpperMs!!, 0.0000001)
        assertEquals(499.9999, metrics.endpointToSealCommitLowerMs!!, 0.0000001)
        assertEquals(500.0001, metrics.endpointToSealCommitUpperMs!!, 0.0000001)
    }
    @Test fun unmappedOrMissingEndpointDoesNotManufactureElapsedBounds() {
        val unmapped = timing(mapping = false).metrics()!!
        assertNull(unmapped.lastStepFromAdmissionLowerMs); assertNull(unmapped.firstToLastStepLowerMs)
        assertNull(unmapped.endpointToLastStepLowerMs); assertNull(unmapped.endpointToSealCommitLowerMs)
        val missing = timing().copy(endpointDecisionAtNs = null).metrics()!!
        assertNotNull(missing.lastStepFromAdmissionLowerMs); assertNotNull(missing.firstToLastStepLowerMs)
        assertNull(missing.endpointToLastStepLowerMs); assertNull(missing.endpointToSealCommitLowerMs)
    }

    private fun interval(ms: Long) = NativeNanoInterval(ms * 1_000_000 - 100, ms * 1_000_000 + 100)
    private fun timing(first: Long = 1500, last: Long = 4000, endpoint: Long = 4500,
        mapping: Boolean = true, closed: Long? = 5400) = NativeAudioCaptureTiming(
        receipt = NativeAudioTimingReceipt(
            phase = NativeAudioTimingPhase.SEALED, producerInstance = 1,
            acceptedPcmSamples = 49_221, completedSteps = 7, validatedAudioRows = 77,
            nativeTimingValid = true, verifiedClockContract = mapping, mappingAvailable = mapping,
            firstAccepted = interval(1000).takeIf { mapping }, firstStep = interval(first).takeIf { mapping },
            lastStep = interval(last).takeIf { mapping }, sealCommit = interval(5000).takeIf { mapping },
            alignmentUncertaintyNanos = 100L.takeIf { mapping }),
        sealCalledAtNs = 4600L * 1_000_000, sealReturnedAtNs = 5200L * 1_000_000,
        checkedCloseCalledAtNs = 5200L * 1_000_000,
        checkedCloseAtNs = closed?.times(1_000_000), endpointDecisionAtNs = endpoint * 1_000_000,
        retainedPreRollSampleCount = 19_200)

    @Test fun firstAudioStartsAtNativeAdmissionAndDoesNotBackdatePreroll() {
        val metrics = timing().metrics(8000L * 1_000_000)!!
        assertEquals(6999.9999, metrics.firstAudioToSpeechLowerMs!!, 0.0000001)
        assertEquals(7000.0001, metrics.firstAudioToSpeechUpperMs!!, 0.0000001)
        assertEquals(19_200, metrics.retainedPreRollSamples)
        assertEquals(7, metrics.stepsBeforeEndpointExact)
        assertEquals(77, metrics.rowsBeforeEndpointExact)
        assertEquals(200.0, metrics.checkedCloseDurationMs!!, 0.0)
    }

    @Test fun lastStepAfterEndpointAllowsOnlyAFirstStepLowerBound() {
        val metrics = timing(last = 4800).metrics()!!
        assertEquals(1, metrics.stepsBeforeEndpointLowerBound)
        assertNull(metrics.stepsBeforeEndpointExact)
        assertNull(metrics.rowsBeforeEndpointExact)
        assertEquals(7, metrics.completedSteps)
    }

    @Test fun firstStepOnlyDuringSealCannotProvePreEndpointWork() {
        val metrics = timing(first = 4700, last = 4800).metrics()!!
        assertEquals(0, metrics.stepsBeforeEndpointLowerBound)
        assertEquals(0, metrics.stepsBeforeEndpointExact)
        assertEquals(0, metrics.rowsBeforeEndpointExact)
    }

    @Test fun overlappingCompletionIntervalDoesNotClaimPositiveWork() {
        val metrics = timing(first = 4500, last = 4800).metrics()!!
        assertEquals(0, metrics.stepsBeforeEndpointLowerBound)
        assertNull(metrics.stepsBeforeEndpointExact)
    }

    @Test fun delayedNativeAdmissionKeepsNegativeEndpointOffset() {
        val metrics = timing(endpoint = 800).metrics()!!
        assertTrue(metrics.endpointFromAdmissionUpperMs!! < 0)
        assertEquals(0, metrics.stepsBeforeEndpointExact)
    }

    @Test fun noCheckedCloseOrBackwardCloseHasNoPublishableMetrics() {
        assertNull(timing(closed = null).metrics())
        assertNull(timing(closed = 5199).metrics())
    }

    @Test fun unavailableMappingDoesNotManufactureDurationOrOverlap() {
        val metrics = timing(mapping = false).metrics(8000L * 1_000_000)!!
        assertFalse(metrics.clockMappingAvailable)
        assertNull(metrics.firstAudioToSpeechLowerMs)
        assertNull(metrics.stepsBeforeEndpointLowerBound)
        assertEquals(7, metrics.completedSteps)
    }

    @Test fun noPlaybackLeavesLatencyUnavailableWhilePreservingValidatedCounts() {
        val metrics = timing().metrics()!!
        assertNull(metrics.firstAudioToSpeechLowerMs)
        assertNull(metrics.firstAudioToSpeechUpperMs)
        assertEquals(77, metrics.validatedRows)
    }
}
