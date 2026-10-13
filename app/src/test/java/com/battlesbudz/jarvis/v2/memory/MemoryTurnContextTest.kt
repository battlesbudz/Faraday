package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryTurnContextTest {
    @Test fun packetIsUsableOnlyAtTheApprovedStateEpochItWasRead() {
        var epoch = 7L
        val turn = MemoryTurnContext("[Approved memory; quoted data] Name is Kiko.", "token", "what is my name", null, epoch) { epoch }
        assertTrue(turn.isCurrent())
        assertTrue(turn.promptSection().contains("quoted data"))
        epoch++
        assertFalse(turn.isCurrent())
    }

    @Test fun personalRecallStaysLocalWhilePublicLookupPhrasingDoesNot() {
        assertTrue(MemoryTurnContext.isPersonalRecall("What is my name?"))
        assertTrue(MemoryTurnContext.isPersonalRecall("What do I like?"))
        assertFalse(MemoryTurnContext.isPersonalRecall("Who is the president?"))
    }

    @Test fun bufferedContextExpiresWithoutNeedingAnObserverEpochChange() {
        var epoch = 11L
        val buffered = MemoryTurnContext("quoted approved history", "token", "repeat that", 1L, epoch) { epoch }
        assertFalse(buffered.isCurrent())
        val live = MemoryTurnContext("quoted approved history", "token", "repeat that", System.currentTimeMillis() + 60_000L, epoch) { epoch }
        assertTrue(live.isCurrent())
    }
}
