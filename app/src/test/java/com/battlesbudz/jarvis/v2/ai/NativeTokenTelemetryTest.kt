package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class NativeTokenTelemetryTest {
    @Test fun oneTokenStillHasWholeDecodeDurationButNotPostFirstTokenThroughput() {
        val value = NativeTokenTelemetry.checked(4, 1, .25, 20.0, 5.0, .3)!!
        assertEquals(5.0, value.decodeTokensPerSecond!!, 0.0)
        assertEquals(200.0, value.derivedLastDecodeLoopMs!!, 0.0)
        assertNull(NativeTokenTelemetry.checked(4, 1, Double.MAX_VALUE, 20.0, 5.0, .3)!!.sdkFirstPrefillDecodeTtftProxyMs)
    }

    @Test fun phaseDurationsUseMatchingSdkCountsAndRatesAndRejectOverflow() {
        val s = NativeTokenTelemetry.checked(40, 12, .25, 100.0, 24.0, .3)!!
        assertEquals(400.0, s.derivedLastPrefillExecutorMs!!, 0.0)
        assertEquals(500.0, s.derivedLastDecodeLoopMs!!, 0.0)
        val invalid = NativeTokenTelemetry.checked(40, 12, .25, Double.MIN_VALUE, Double.POSITIVE_INFINITY, .3)!!
        assertNull(invalid.derivedLastPrefillExecutorMs); assertNull(invalid.derivedLastDecodeLoopMs)
        val absent = NativeTokenTelemetry.checked(0, 0, .25, 100.0, 24.0, .3)!!
        assertNull(absent.derivedLastPrefillExecutorMs); assertNull(absent.derivedLastDecodeLoopMs)
    }

    @Test fun nativeCountsAndRatesKeepTheirOwnSdkClock() {
        val value = NativeTokenTelemetry.checked(41, 12, .25, 120.0, 18.0, .3)!!
        assertEquals(41, value.inputTokens)
        assertEquals(12, value.outputTokens)
        assertEquals(250.0, value.sdkFirstPrefillDecodeTtftProxyMs!!, 0.001)
        assertEquals(18.0, value.decodeTokensPerSecond!!, 0.001)
    }
    @Test fun missingOrInvalidNativeValuesNeverBecomeEstimates() {
        assertNull(NativeTokenTelemetry.checked(-1, 12, .25, 120.0, 18.0, .3))
        val empty = NativeTokenTelemetry.checked(0, 0, 0.0, Double.NaN, 0.0, .1)!!
        assertNull(empty.sdkFirstPrefillDecodeTtftProxyMs)
        assertNull(empty.prefillTokensPerSecond)
        assertNull(empty.decodeTokensPerSecond)
        assertNull(NativeTokenTelemetry.checked(4, 2, .1, 5.0, 4.0, Double.NaN))
    }
}
