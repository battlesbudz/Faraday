package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.memory.ConversationMemory
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryOutcome
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryOs
import com.battlesbudz.jarvis.v2.memory.MemoryProposal
import com.battlesbudz.jarvis.v2.memory.MemorySource
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeMemoryCoordinatorTest {
    private class Fixture {
        var now = System.currentTimeMillis()
        var conversationId = "conversation-one"
        var cutoffSucceeds = true
        var summarySucceeds = true
        var tokenSucceeds = true
        var persistedToken: String? = null
        val writes = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val os = MemoryOs(File.createTempFile("runtime-memory", ".json").apply { delete(); deleteOnExit() }) { now }
        val memory = ConversationMemory(os)
        val coordinator = RuntimeMemoryCoordinator(
            memory = { memory }, currentConversationId = { conversationId },
            persistHistoryCutoff = { writes += "cutoff"; cutoffSucceeds },
            clearSummary = { writes += "summary"; summarySucceeds },
            readStateToken = { persistedToken },
            persistStateToken = { token ->
                writes += "token"
                if (tokenSucceeds) persistedToken = token
                tokenSucceeds
            }, recordDiagnostic = diagnostics::add)

        fun capture(id: String, text: String = "Remember that I prefer tea") = coordinator.captureFinalMemory(
            id, conversationId, null, ConversationMemorySource.TEXT, text, now)
    }

    @Test fun mutationClosesExistingContextUntilBothDurableWritesComplete() {
        val f = Fixture()
        val old = f.coordinator.freshMemoryTurnContext("favorite drink", 500)!!
        assertTrue(old.isCurrent())
        f.coordinator.beginMemoryBoundary()
        assertFalse(old.isCurrent())
        assertNull(f.coordinator.freshMemoryTurnContext("favorite drink", 500))
        f.coordinator.publishMemoryContextBoundary()
        assertEquals(listOf("cutoff", "summary"), f.writes)
        val current = f.coordinator.freshMemoryTurnContext("favorite drink", 500)!!
        assertEquals(old.observedEpoch + 1, current.observedEpoch)
        assertFalse(old.isCurrent())
        assertTrue(current.isCurrent())
        assertTrue(f.coordinator.consumeMemoryHistoryCutoff())
        assertFalse(f.coordinator.consumeMemoryHistoryCutoff())
    }

    @Test fun cutoffOrSummaryFailureLeavesBoundaryClosedAndRetainsFailureEvidence() {
        for (failCutoff in listOf(true, false)) {
            val f = Fixture()
            f.cutoffSucceeds = !failCutoff
            f.summarySucceeds = failCutoff
            f.coordinator.beginMemoryBoundary()
            f.coordinator.publishMemoryContextBoundary()
            assertNull(f.coordinator.freshMemoryTurnContext("anything", 500))
            assertEquals(if (failCutoff) listOf("cutoff") else listOf("cutoff", "summary"), f.writes)
            assertTrue(f.diagnostics.single().contains("boundary persistence failed"))
        }
    }

    @Test fun captureReceiptsAreSingleUseConversationScopedAndCannotCrossMutationBoundary() {
        val f = Fixture()
        val text = "Remember that I prefer tea"
        assertTrue(f.capture("first", text))
        f.conversationId = "conversation-two"
        assertNull(f.coordinator.takeMemoryCaptureReceipt(text))
        f.conversationId = "conversation-one"
        assertEquals(ConversationMemoryOutcome.PROPOSED, f.coordinator.takeMemoryCaptureReceipt(text)!!.outcome)
        assertNull(f.coordinator.takeMemoryCaptureReceipt(text))
        assertTrue(f.capture("second", text))
        f.coordinator.beginMemoryBoundary()
        assertNull(f.coordinator.takeMemoryCaptureReceipt(text))
        f.coordinator.publishMemoryContextBoundary()
        assertNull(f.coordinator.takeMemoryCaptureReceipt(text))
    }

    @Test fun tokenWriteFailureDoesNotPublishNewResidentTokenAndPreservesWriteOrder() {
        val f = Fixture()
        f.persistedToken = "prior-snapshot"
        f.coordinator.stateToken = "prior-snapshot"
        val fresh = f.coordinator.freshMemoryTurnContext("anything", 500)!!
        f.tokenSucceeds = false
        try {
            f.coordinator.adoptMemoryState(fresh)
            fail("Failed token persistence must prevent resident state adoption")
        } catch (expected: IllegalStateException) {
            assertEquals("memory_token_persist_failed", expected.message)
        }
        assertEquals(listOf("cutoff", "summary", "token"), f.writes)
        assertEquals("prior-snapshot", f.persistedToken)
        assertEquals("prior-snapshot", f.coordinator.stateToken)
    }

    @Test fun expiryRechecksApprovedStoreTokenEvenWhenNoMutationObserverAdvancedEpoch() {
        val f = Fixture()
        val record = f.os.propose(MemoryProposal("I prefer tea", MemorySource("manual", "expiring-tea", f.now),
            expiresAtMs = f.now + 60_000)).memory!!
        f.os.approve(record.id)
        val context = f.coordinator.freshMemoryTurnContext("tea", 500)!!
        assertTrue(context.hasApprovedMemories)
        assertTrue(f.coordinator.isMemoryTurnCurrent(context))
        // Advance only the store's clock: the context's own epoch and wall-clock deadline
        // still look valid, so this specifically exercises the approved snapshot recheck.
        f.now += 120_000
        assertTrue(context.isCurrent())
        assertFalse(f.coordinator.isMemoryTurnCurrent(context))
    }
}
