package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.conversation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperatedTaskSpeakerGateTest {
    private val act = FrontDoorVerdict(
        FrontDoorDecision("ACT", .9, .05, .05, true, true, false, false, false),
        cold = false,
        source = "test",
    )
    private val sentence = "Send a message to Andrea saying I'll be home by six."

    @Test fun actionIneligibleNeverInvokesOperatedStarterForActVerdict() = runBlocking {
        var starts = 0
        var blocked = 0
        val result = startOperatedTaskIfEligible(ActionTurnPlan.NotAction, act, sentence, "conversation",
            starter = { _, _ -> starts++; null }, speakerActionEligible = false, onBlocked = { blocked++ })
        assertNull(result)
        assertEquals(0, starts)
        assertEquals(1, blocked)
    }

    @Test fun actionEligibleInvokesOperatedStarterForActVerdict() = runBlocking {
        var starts = 0
        startOperatedTaskIfEligible(ActionTurnPlan.NotAction, act, sentence, "conversation",
            starter = { _, _ -> starts++; null }, speakerActionEligible = true)
        assertEquals(1, starts)
    }
}
