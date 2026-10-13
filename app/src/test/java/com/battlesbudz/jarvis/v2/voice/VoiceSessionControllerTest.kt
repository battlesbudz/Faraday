package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionControllerTest {
    @Test fun finalGemmaCaptionReplacesOnlyItsPairedUserEntry() {
        val controller = VoiceSessionController(MemoryStore())
        val call = controller.beginCall()
        controller.appendTranscript("You", "What do you think of thinking?")
        controller.beginReply(call.id, "audio-reply")
        controller.updateReplyText(call.id, "audio-reply", "An answer", finished = true)
        controller.appendTranscript("You", "A newer request")
        assertTrue(controller.updateUserTranscriptForReply(call.id, "audio-reply", "What do you think of vaping?"))
        assertEquals(3, controller.currentTranscript().size)
        assertEquals("What do you think of vaping?", controller.currentTranscript()[0].text)
        assertEquals("A newer request", controller.currentTranscript()[2].text)
        assertFalse(controller.updateUserTranscriptForReply(call.id, "audio-reply", VoiceTranscriptResolver.UNTRANSCRIBED,
            expectedText = GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        assertEquals("What do you think of vaping?", controller.currentTranscript()[0].text)
        assertFalse(controller.updateUserTranscriptForReply("wrong-call", "audio-reply", "wrong"))
        assertFalse(controller.updateUserTranscriptForReply(call.id, "wrong-reply", "wrong"))
        controller.end()
        assertFalse(controller.updateUserTranscriptForReply(call.id, "audio-reply", "late"))
    }

    @Test fun unavailableCaptionReplacesOnlyStillPendingPairedMessage() {
        val controller = VoiceSessionController(MemoryStore())
        val call = controller.beginCall()
        controller.appendTranscript("You", GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
        controller.beginReply(call.id, "pending-audio-reply")
        assertTrue(controller.updateUserTranscriptForReply(call.id, "pending-audio-reply", VoiceTranscriptResolver.UNTRANSCRIBED,
            expectedText = GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        assertEquals(VoiceTranscriptResolver.UNTRANSCRIBED, controller.currentTranscript().first().text)
        assertFalse(controller.updateUserTranscriptForReply(call.id, "pending-audio-reply", "late failure",
            expectedText = GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
    }

    @Test fun lateModelLoadCannotReviveAnEndedOrReplacementCall() {
        val controller = VoiceSessionController(MemoryStore())
        val first = controller.beginCall()
        assertTrue(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        controller.end()
        assertFalse(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
        val second = controller.beginCall()
        assertFalse(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        assertEquals(second.id, controller.currentCallId())
    }

    @Test
    fun immediateNewCallDoesNotImportPreviousStoryOrTask() {
        val store = MemoryStore()
        var now = 1000L
        val controller = VoiceSessionController(store) { now }
        controller.beginCall()
        controller.appendTranscript("You", "Tell me a moon story")
        controller.appendTranscript("Jarvis", "The astronauts discovered a hidden laboratory.")
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.INTERRUPTED))
        controller.end()
        now += 1000
        val next = controller.beginCall()
        assertTrue(next.transcript.isEmpty())
        assertEquals(null, next.taskStatus)
        assertTrue(controller.conversationContext().isEmpty())
        assertTrue(controller.contextProvenance().contains("automaticPriorCalls=false"))
        controller.appendTranscript("You", "What did you say?")
        assertEquals(1, controller.currentTranscript().size)
        assertEquals(1, controller.conversationContext().size)
    }

    @Test
    fun oldOrDeletedCallsDoNotBecomeImplicitContext() {
        val store = MemoryStore()
        var now = 1000L
        val controller = VoiceSessionController(store) { now }
        val call = controller.beginCall()
        controller.appendTranscript("Jarvis", "Old answer")
        controller.end()
        now += 16 * 60 * 1000
        controller.beginCall()
        assertTrue(controller.conversationContext().isEmpty())
        controller.end()
        store.delete(call.id)
        controller.beginCall()
        assertTrue(controller.conversationContext().isEmpty())
    }

    @Test
    fun restartedControllerOnlyUsesSavedDialogueOnExplicitResume() {
        val store = MemoryStore()
        val first = VoiceSessionController(store) { 1000L }
        first.beginCall()
        first.appendTranscript("Jarvis", "Last complete reply")
        first.appendTranscript("Jarvis", "An unfinished draft", complete = false)
        val saved = first.end()
        val restarted = VoiceSessionController(store) { 2000L }
        restarted.beginCall()
        assertTrue(restarted.conversationContext().isEmpty())
        restarted.end()
        restarted.resumeCall(saved)
        assertEquals(listOf("Last complete reply"), restarted.conversationContext().map { it.text })
        assertTrue(restarted.contextProvenance().contains("resumedFromCallId=${saved.id}"))
    }

    private class MemoryStore : VoiceCallStore {
        val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) {
            calls.removeAll { it.id == call.id }
            calls += call
        }
        override fun delete(callId: String) { calls.removeAll { it.id == callId } }
    }

    @Test
    fun interruptedCallPreservesIncompleteTranscriptAndTaskState() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store) { 100L }
        controller.beginCall()
        controller.appendTranscript("You", "Find three options", complete = true)
        controller.appendTranscript("Jarvis", "I found two and am comparing", complete = false)
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.WAITING_FOR_USER, pendingSteps = listOf("Compare shipping")))

        val ended = controller.interrupt()

        assertEquals(VoiceTaskState.INTERRUPTED, ended.taskStatus?.state)
        assertFalse(ended.transcript.last().complete)
        assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
    }

    @Test
    fun finalCompletedTaskRemainsCompletedWhenCallEnds() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store)
        controller.beginCall()
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.COMPLETED))
        val ended = controller.end()
        assertEquals(VoiceTaskState.COMPLETED, ended.taskStatus?.state)
        assertTrue(store.calls.single().endedAtMs != null)
    }
    @Test fun typedCallInputIsNotPresentedAsSpokenHistory() {
        val controller = VoiceSessionController(MemoryStore())
        controller.beginCall()
        controller.appendTranscript("You", "typed follow-up", origin = TranscriptOrigin.TYPED)
        assertEquals(TranscriptOrigin.TYPED, controller.currentTranscript().single().origin)
    }

    @Test fun terminalReplyEvidenceUpdatesSavedEndedCallWithoutRevivingIt() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store)
        val call = controller.beginCall()
        controller.beginReply(call.id, "queued-b")
        controller.end()

        controller.updateTerminalReplyTextForCall(call.id, "queued-b", "Cancelled; unattempted: open_app.")

        assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
        assertEquals("Cancelled; unattempted: open_app.",
            store.list().single { it.id == call.id }.transcript.single { it.replyId == "queued-b" }.text)
    }

    @Test fun terminalTypedReceiptTargetsSavedEndedCallAndNeverTheReplacement() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store)
        val ended = controller.beginCall()
        controller.end()
        val replacement = controller.beginCall()

        controller.recordTerminalInputForCall(ended.id, "typed-event", "Cancelled before processing typed message: open settings")
        controller.recordTerminalInputForCall(ended.id, "typed-event", "duplicate must not be written")

        val saved = store.list().single { it.id == ended.id }
        assertEquals(listOf("Cancelled before processing typed message: open settings"),
            saved.transcript.filter { it.replyId == "terminal-typed-event" }.map { it.text })
        assertTrue(controller.currentTranscript().isEmpty())
        assertEquals(replacement.id, controller.currentCallId())
    }

}
