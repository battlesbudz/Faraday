package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationModelSessionTest {
    private class State : ConversationSessionState {
        override var engine: LiteRtLmEngine? = null
        override var hasContext = true
        override var characters = 0
    }
    private class Fixture {
        val state = State()
        val context = ShortTermConversationContext()
        val saved = mutableListOf<String?>()
        var persistenceFailure = false
        val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false)
        val session = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
            "unused", context, persistSummary = {
                if (persistenceFailure) error("summary_storage_failed")
                saved.add(it)
            })
    }

    @Test fun compactionKeepsVisibleHistoryAndPersistsBeforeResettingNativeAccounting() = runBlocking {
        val f = Fixture()
        f.state.characters = 9_000
        val history = listOf(ChatEntry("You", "My dog is Luna."), ChatEntry("Jarvis", "Luna is remembered in this conversation."))
        val result = f.session.prepareHistory(history, false, 1_000, 10_000)
        assertTrue(result.isEmpty())
        assertEquals(2, history.size)
        assertEquals(listOf(f.context.summaryForDiagnostics()), f.saved)
        assertTrue(f.saved.single()!!.contains("Luna"))
        assertEquals(0, f.session.characters)
        assertFalse(f.session.hasContext)
    }

    @Test fun withinBudgetRetainsHistoryAndNativeContextWithoutSummaryWrite() = runBlocking {
        val f = Fixture()
        f.state.characters = 200
        val history = listOf(ChatEntry("You", "A short follow-up."))
        assertSame(history, f.session.prepareHistory(history, false, 100, 10_000))
        assertEquals(200, f.session.characters)
        assertTrue(f.session.hasContext)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun memoryCutoffNeverSeedsRetainedVisibleDialogue() = runBlocking {
        val f = Fixture()
        val history = listOf(ChatEntry("You", "An erased historical preference."))
        assertTrue(f.session.prepareHistory(history, true, 100, 10_000).isEmpty())
        assertTrue(f.saved.isEmpty())
        assertEquals("An erased historical preference.", history.single().text)
    }

    @Test fun failedSummaryPersistencePropagatesBeforeContextReset() = runBlocking {
        val f = Fixture()
        f.state.characters = 9_000
        f.persistenceFailure = true
        try {
            f.session.prepareHistory(listOf(ChatEntry("You", "Current topic.")), false, 1_000, 10_000)
            fail("A failed summary write must remain observable to the owning job")
        } catch (expected: IllegalStateException) {
            assertEquals("summary_storage_failed", expected.message)
        }
        assertEquals(9_000, f.session.characters)
        assertTrue(f.session.hasContext)
    }
    @Test fun nativeMutationFenceRunsBeforeResetOrCloseAndFailurePreservesOwnership() = runBlocking {
        for (close in listOf(false, true)) {
            val f = Fixture(); f.state.characters = 500
            f.session.beforeNativeMutation = { error("speculative native drain failed") }
            try { if (close) f.session.close() else f.session.reset(); fail("unsafe mutation") }
            catch (expected: IllegalStateException) { assertEquals("speculative native drain failed", expected.message) }
            assertEquals(500, f.state.characters); assertTrue(f.state.hasContext)
        }
    }

    @Test fun unchangedHistoryDoesNotDrainPendingSpeculation() = runBlocking<Unit> {
        val f = Fixture()
        f.session.beforeNativeMutation = { fail("read-only unchanged context may keep running draft") }
        f.session.prepareHistory(listOf(ChatEntry("You", "A stable call turn")), false, 100, 10_000)
    }

}
