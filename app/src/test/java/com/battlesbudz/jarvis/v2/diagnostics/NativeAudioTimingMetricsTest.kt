package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAudioTimingMetricsTest {
    @Test fun additiveFinalStepIntervalsRoundTripAndOldReceiptLeavesThemUnknown() {
        val old = observed().json()
        listOf("last_step_from_admission", "first_to_last_step_elapsed", "endpoint_to_last_step", "endpoint_to_seal_commit").forEach { name ->
            old.remove("native_audio_${name}_lower_ms"); old.remove("native_audio_${name}_upper_ms")
        }
        assertNull(NativeAudioTimingMetrics.read(old)!!.endpointToSealCommitLowerMs)
        val value = observed().copy(lastStepFromAdmissionLowerMs = 900.0, lastStepFromAdmissionUpperMs = 900.1,
            firstToLastStepLowerMs = 699.9, firstToLastStepUpperMs = 700.1,
            endpointToLastStepLowerMs = -1100.1, endpointToLastStepUpperMs = -1099.9,
            endpointToSealCommitLowerMs = 20.0, endpointToSealCommitUpperMs = 20.1)
        assertEquals(value, NativeAudioTimingMetrics.read(value.json()))
        assertNull(NativeAudioTimingMetrics.read(value.json().put("native_audio_endpoint_to_last_step_upper_ms", JSONObject.NULL)))
        assertTrue(runCatching { value.copy(clockMappingAvailable = false, alignmentUncertaintyNanos = null) }.isFailure)
    }

    private fun observed() = NativeAudioTimingMetrics(
        retainedPcmSamples = 49_221, retainedPreRollSamples = 19_200,
        completedSteps = 7, validatedRows = 77, clockMappingAvailable = true,
        alignmentUncertaintyNanos = 20_000,
        firstAudioToSpeechLowerMs = 4210.01, firstAudioToSpeechUpperMs = 4210.03,
        firstStepFromAdmissionLowerMs = 200.01, firstStepFromAdmissionUpperMs = 200.03,
        endpointFromAdmissionLowerMs = 2000.01, endpointFromAdmissionUpperMs = 2000.03,
        stepsBeforeEndpointLowerBound = 1, checkedCloseDurationMs = 4.0)

    @Test fun savedReceiptRoundTripsWithoutAbsoluteClockOrContent() {
        val value = observed()
        assertEquals(value, NativeAudioTimingMetrics.read(JSONObject(value.json().toString())))
        val keys = value.json().keys().asSequence().toSet()
        assertEquals(value.observations().keys + "schema", keys)
        assertFalse(keys.any { it.contains("timestamp") || it.contains("producer") || it.contains("hash") || it.contains("text") })
        assertTrue(value.summary().contains("First audio → speech 4.21s"))
        assertTrue(value.summary().contains("±"))
    }

    @Test fun unavailableMappingAndPlaybackStayUnavailable() {
        val unknown = NativeAudioTimingMetrics(100, 100, 0, 0, false, null)
        assertEquals("First audio → speech —", unknown.summary())
        assertEquals(unknown, NativeAudioTimingMetrics.read(unknown.json()))
        assertTrue(unknown.json().isNull("native_first_audio_to_speech_lower_ms"))
        assertTrue(unknown.json().isNull("native_audio_steps_before_endpoint_exact"))
    }

    @Test fun crossingEndpointPreservesOnlyTheProvenLowerBound() {
        val value = observed()
        assertEquals(1, value.stepsBeforeEndpointLowerBound)
        assertNull(value.stepsBeforeEndpointExact)
        assertNull(value.rowsBeforeEndpointExact)
        assertEquals(7, value.completedSteps)
    }

    @Test fun nativeAdmissionCanOccurAfterEndpointWithoutBackdating() {
        val late = observed().copy(endpointFromAdmissionLowerMs = -120.03, endpointFromAdmissionUpperMs = -120.01,
            stepsBeforeEndpointLowerBound = 0, stepsBeforeEndpointExact = 0, rowsBeforeEndpointExact = 0)
        assertEquals(-120.03, NativeAudioTimingMetrics.read(late.json())!!.endpointFromAdmissionLowerMs!!, 0.0)
    }

    @Test fun malformedNumbersAndPartialIntervalsDoNotBecomeZero() {
        for (replacement in listOf("4210", JSONObject.NULL, Double.POSITIVE_INFINITY)) {
            val json = observed().json()
            if (replacement is Double && !replacement.isFinite()) {
                assertTrue(runCatching { observed().copy(firstAudioToSpeechLowerMs = replacement) }.isFailure)
            } else {
                json.put("native_first_audio_to_speech_lower_ms", replacement)
                assertNull(NativeAudioTimingMetrics.read(json))
            }
        }
        assertNull(NativeAudioTimingMetrics.read(observed().json().put("native_audio_completed_steps", 1.5)))
        assertNull(NativeAudioTimingMetrics.read(observed().json().put("native_audio_alignment_uncertainty_ns", "20000")))
        assertNull(NativeAudioTimingMetrics.read(observed().json().put("schema", 1.2)))
    }

    @Test fun countsAndExactnessMustBeInternallyConsistent() {
        assertTrue(runCatching { observed().copy(retainedPreRollSamples = 19_201) }.isFailure)
        assertTrue(runCatching { observed().copy(stepsBeforeEndpointLowerBound = 8) }.isFailure)
        assertTrue(runCatching { observed().copy(stepsBeforeEndpointExact = 7) }.isFailure)
        assertTrue(runCatching { observed().copy(clockMappingAvailable = false, alignmentUncertaintyNanos = null) }.isFailure)
        assertTrue(runCatching { observed().copy(firstAudioToSpeechLowerMs = -1.0) }.isFailure)
    }

    @Test fun oldRepliesReadWithoutSyntheticNativeMeasurements() {
        val old = ReplyMetrics.read(JSONObject("{}"))!!
        assertNull(old.nativeAudioTiming)
        val complete = ReplyMetrics().speechEnded(100).firstActualPlayback(700).copy(nativeAudioTiming = observed())
        assertEquals(complete, ReplyMetrics.read(JSONObject(complete.json().toString())))
        assertTrue(complete.summary().contains("TTF-SW 0.60s"))
        val malformed = complete.json().put("nativeAudioTiming", JSONObject("{\"schema\":99}"))
        assertNull(ReplyMetrics.read(malformed)!!.nativeAudioTiming)
        assertEquals(700L, ReplyMetrics.read(malformed)!!.firstReplyPlaybackAtMs)
    }
}
