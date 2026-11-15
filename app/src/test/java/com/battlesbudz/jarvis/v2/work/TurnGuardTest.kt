package com.battlesbudz.jarvis.v2.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M7 (reliability epic item 5, part 1 + 2): the turn sequence guard's own
 * semantics, and the discarded-turn signal's exactly-once contract.
 */
class TurnGuardTest {

    @Test fun claimMakesOnlyTheLatestTurnCurrent() {
        val guard = TurnGuard()
        val first = guard.claim()
        assertTrue(guard.isCurrent(first))
        val second = guard.claim()
        assertTrue(second > first)
        assertFalse("a claimed-over turn is superseded", guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
        assertEquals(second, guard.current())
    }

    @Test fun noTurnIsNeverCurrent() {
        val guard = TurnGuard()
        assertFalse(guard.isCurrent(TurnGuard.NO_TURN))
        guard.claim()
        assertFalse(guard.isCurrent(TurnGuard.NO_TURN))
    }

    @Test fun supersededTurnCannotReleaseItsReplacement() {
        val guard = TurnGuard()
        val first = guard.claim()
        val second = guard.claim()
        guard.release(first)
        assertTrue("release by a superseded turn must not clear the current owner",
            guard.isCurrent(second))
        guard.release(second)
        assertFalse(guard.isCurrent(second))
        assertEquals(TurnGuard.NO_TURN, guard.current())
    }

    @Test fun ownershipCheckAbortsOnlyTheSupersededTurn() {
        val guard = TurnGuard()
        val stale = TurnOwnership(guard, guard.claim())
        val current = TurnOwnership(guard, guard.claim())
        current.checkCurrent()
        try {
            stale.checkCurrent()
            fail("a superseded turn must abort")
        } catch (expected: TurnSupersededException) {
            assertTrue(expected.message!!.contains(stale.sequence.toString()))
        }
    }

    @Test fun discardedSignalFiresExactlyOnceAcrossTeardownPaths() {
        var emissions = 0
        val signal = DiscardedTurnSignal { emissions++ }
        signal.emit()
        signal.emit()
        signal.emit()
        assertEquals(1, emissions)
    }

    @Test fun discardedSignalSurvivesAThrowingListener() {
        var emissions = 0
        val signal = DiscardedTurnSignal { emissions++; error("listener failed") }
        signal.emit()
        signal.emit()
        assertEquals("a throwing listener still counts as the one emission", 1, emissions)
    }

    @Test fun processAdmissionExposesTheSharedGuard() {
        val sequence = ProcessConversationAdmission.turnGuard.claim()
        try {
            assertTrue(ProcessConversationAdmission.turnGuard.isCurrent(sequence))
            assertEquals(sequence,
                com.battlesbudz.jarvis.v2.conversation.ConversationWork.turnGuard.current())
        } finally {
            ProcessConversationAdmission.turnGuard.release(sequence)
        }
        assertFalse(ProcessConversationAdmission.turnGuard.isCurrent(sequence))
    }
}
