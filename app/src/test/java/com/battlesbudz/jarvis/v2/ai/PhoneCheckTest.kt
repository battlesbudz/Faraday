package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCheckTest {
    private val gib = 1024L * 1024L * 1024L

    @Test fun advertisedFigureWinsOnAndroid14AndLater() {
        assertEquals(8L * gib, advertisedRamBytes(34, 8L * gib, 7_400_000_000L))
        assertEquals(12L * gib, advertisedRamBytes(35, 12L * gib, 11L * gib))
    }

    @Test fun belowAndroid14TheReportedFigureIsSnappedUp() {
        // An "8 GB" phone reports roughly 6.9 GiB of usable RAM.
        assertEquals(8L * gib, advertisedRamBytes(33, 0L, 7_400_000_000L))
        // The advertised field is ignored before Android 14.
        assertEquals(8L * gib, advertisedRamBytes(30, 8L * gib, 7_400_000_000L))
    }

    @Test fun missingAdvertisedFigureFallsBackToTheSnap() {
        assertEquals(8L * gib, advertisedRamBytes(34, 0L, 7_400_000_000L))
        assertEquals(8L * gib, advertisedRamBytes(34, -1L, 7_400_000_000L))
    }

    @Test fun snapKeepsExactSizesAndRoundsUpBetweenSizes() {
        assertEquals(12L * gib, snapUpToMarketedRamSize(12L * gib))
        assertEquals(16L * gib, snapUpToMarketedRamSize(12L * gib + 1L))
        assertEquals(4L * gib, snapUpToMarketedRamSize(3_800_000_000L))
        // Above the largest marketed tier the reported figure stands.
        assertEquals(128L * gib, snapUpToMarketedRamSize(128L * gib))
        // An unknown figure stays unknown; guidance treats it as unavailable.
        assertEquals(0L, snapUpToMarketedRamSize(0L))
    }
}
