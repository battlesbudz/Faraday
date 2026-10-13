package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechDeliveryLedgerTest {
    @Test fun queuedTextIsNotSpokenAndPartialWordsAreUnknown() {
        val ledger = SpeechDeliveryLedger("turn")
        ledger.append(0, "First sentence.", 100, 24000); ledger.seal(0)
        ledger.append(1, "Unspoken sentence.", 100, 24000); ledger.seal(1)
        assertEquals("", ledger.advance(0).deliveredText)
        assertEquals(0, ledger.advance(50).partialSpanIndex)
        val stopped = ledger.advance(125, SpeechDeliveryState.INTERRUPTED)
        assertEquals("First sentence.", stopped.deliveredText)
        assertEquals(1, stopped.partialSpanIndex)
        assertEquals(stopped, ledger.advance(200, SpeechDeliveryState.COMPLETED))
    }
    @Test fun producerMustSealStreamingSentenceBeforeItCountsAsDelivered() {
        val ledger = SpeechDeliveryLedger("turn")
        ledger.append(0, "A complete sentence.", 100, 24000)
        assertEquals("", ledger.advance(100).deliveredText)
        ledger.append(0, "", 100, 24000); ledger.seal(0)
        assertEquals("", ledger.advance(150).deliveredText)
        assertEquals("A complete sentence.", ledger.advance(200).deliveredText)
    }
    @Test fun stopBeforeFirstPcmRejectsLateProducerAndPlaybackEvents() {
        val ledger = SpeechDeliveryLedger("turn")
        val stopped = ledger.advance(0, SpeechDeliveryState.INTERRUPTED)
        ledger.append(0, "Late output", 100, 24000); ledger.seal(0)
        assertEquals(stopped, ledger.advance(100, SpeechDeliveryState.COMPLETED))
        assertTrue(stopped.spans.isEmpty())
    }
    @Test fun routeFailureKeepsOnlyCompletedSegments() {
        val ledger = SpeechDeliveryLedger("turn")
        ledger.append(0, "Delivered.", 100, 24000); ledger.seal(0)
        ledger.append(1, "Lost.", 100, 24000); ledger.seal(1)
        val failed = ledger.advance(100, SpeechDeliveryState.FAILED)
        assertEquals("Delivered.", failed.deliveredText)
        assertNull(failed.partialSpanIndex)
        assertEquals(SpeechDeliveryState.FAILED, failed.state)
    }
    @Test fun callbacksAreBoundedBySegmentTransitionsAndHeadNeverMovesBackward() {
        val changes = mutableListOf<SpeechDelivery>()
        val ledger = SpeechDeliveryLedger("turn", changes::add)
        ledger.append(0, "Hello.", 100, 24000); ledger.seal(0)
        for (head in 0L..100L) ledger.advance(head)
        assertEquals(3, changes.size)
        assertEquals(100L, ledger.advance(0).playedFrames)
        ledger.advance(100, SpeechDeliveryState.COMPLETED)
        assertEquals(4, changes.size)
    }
}
