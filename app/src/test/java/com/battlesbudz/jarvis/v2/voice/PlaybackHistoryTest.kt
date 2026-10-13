package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    private class Store : VoiceCallStore {
        val calls = linkedMapOf<String, VoiceCallRecord>()
        override fun list() = calls.values.toList()
        override fun save(call: VoiceCallRecord) { calls[call.id] = call }
        override fun delete(callId: String) { calls.remove(callId) }
    }
    private class Fixture {
        val store = Store(); val session = VoiceSessionController(store)
        val call = session.beginCall()
        val ledger = SpeechDeliveryLedger("reply") { session.updateDelivery(call.id, it) }
        init { session.appendTranscript("You", "Tell me something"); session.beginReply(call.id, "reply") }
        fun generated() = session.updateReplyText(call.id, "reply", "Delivered. Unspoken.", finished = true)
        fun audio() {
            ledger.append(0, "Delivered.", 100, 24000); ledger.seal(0)
            ledger.append(1, "Unspoken.", 100, 24000); ledger.seal(1)
        }
        fun last() = session.currentTranscript().last()
    }
    @Test fun generationCompletionDoesNotCompleteOrExposeQueuedSpeech() {
        val f = Fixture(); f.generated(); f.audio()
        assertFalse(f.last().complete)
        assertEquals(listOf("Tell me something"), f.session.conversationContext().map { it.text })
        f.ledger.advance(200, SpeechDeliveryState.COMPLETED)
        assertTrue(f.last().complete)
        assertEquals("Delivered. Unspoken.", f.session.conversationContext().last().text)
        assertEquals(2, f.session.currentTranscript().size)
    }
    @Test fun playbackCanFinishBeforeFinalTextCallbackWithoutDuplicatingEntry() {
        val f = Fixture(); f.audio(); f.ledger.advance(200, SpeechDeliveryState.COMPLETED)
        assertFalse(f.last().complete)
        f.generated(); assertTrue(f.last().complete)
        f.generated(); assertEquals(2, f.session.currentTranscript().size)
    }
    @Test fun interruptionExcludesUnplayedTextAndKeepsRawGenerationForInspection() {
        val f = Fixture(); f.generated(); f.audio(); f.ledger.advance(125, SpeechDeliveryState.INTERRUPTED)
        assertEquals("Delivered. Unspoken.", f.last().text)
        val context = f.session.conversationContext().last().text
        assertTrue(context.startsWith("Delivered.")); assertFalse(context.contains("Unspoken."))
        assertTrue(context.contains("partial wording is unknown")); assertFalse(f.last().complete)
    }
    @Test fun completedActionSurvivesInterruptionBeforeAnySpeechAndLateTextCannotEraseIt() {
        val f = Fixture()
        f.session.recordReplyAction(f.call.id, "reply", VoiceActionOutcome("open_app", "Opened YouTube.", true))
        f.ledger.advance(0, SpeechDeliveryState.INTERRUPTED); f.generated()
        val context = f.session.conversationContext().last().text
        assertTrue(context.contains("Tool outcome: open_app")); assertFalse(context.contains("Unspoken."))
        assertEquals(1, f.last().actions.size)
    }
    @Test fun latePlaybackTargetsOriginalSavedCallAndCannotTouchReplacementOrDeletedCall() {
        val f = Fixture(); f.generated(); f.audio(); f.session.end()
        val next = f.session.beginCall(); f.session.appendTranscript("You", "New call")
        f.ledger.advance(100, SpeechDeliveryState.INTERRUPTED)
        assertEquals(next.id, f.session.currentCallId()); assertEquals(1, f.session.currentTranscript().size)
        assertEquals("Delivered.", f.store.calls.getValue(f.call.id).transcript.last().delivery?.deliveredText)
        f.store.delete(f.call.id)
        f.session.recordReplyAction(f.call.id, "reply", VoiceActionOutcome("open_app", "Done", true))
        assertFalse(f.store.calls.containsKey(f.call.id))
    }
    @Test fun terminalDeliveryRejectsStaleAndHigherRevisionPlaybackCallbacks() {
        val f = Fixture(); f.generated(); f.audio()
        val stopped = f.ledger.advance(100, SpeechDeliveryState.INTERRUPTED)
        f.session.updateDelivery(f.call.id, stopped.copy(state = SpeechDeliveryState.COMPLETED, playedFrames = 200, revision = 999))
        assertEquals(stopped, f.last().delivery); assertFalse(f.last().complete)
    }
    @Test fun resumeUsesLatestDeliveryReceiptRatherThanTheOldScreenSnapshot() {
        val f = Fixture(); f.generated(); f.audio(); val old = f.session.end()
        f.ledger.advance(100, SpeechDeliveryState.INTERRUPTED)
        f.session.resumeCall(old)
        assertTrue(f.session.conversationContext().last().text.startsWith("Delivered."))
        assertFalse(f.session.conversationContext().last().text.contains("Unspoken."))
    }
    @Test fun deliveryAndActionMetadataRoundTripWithoutChangingOldRecords() {
        val f = Fixture(); f.generated(); f.audio(); f.ledger.advance(125, SpeechDeliveryState.INTERRUPTED)
        f.session.recordReplyAction(f.call.id, "reply", VoiceActionOutcome("read_battery", "50 percent", true))
        val record = f.session.end()
        assertEquals(record, SharedPreferencesVoiceCallStore.decode(SharedPreferencesVoiceCallStore.encode(listOf(record))).single())
        val old = JSONObject().put("id", "old").put("transcript", JSONArray().put(JSONObject()
            .put("role", "Jarvis").put("text", "Legacy reply").put("complete", true)))
        val entry = SharedPreferencesVoiceCallStore.decode(JSONArray().put(old).toString()).single().transcript.single()
        assertNull(entry.delivery); assertEquals("Legacy reply", entry.forConversation()?.text)
        assertNull(entry.copy(complete = false).forConversation())
    }
    @Test fun malformedNewDeliveryCannotFallBackToClaimingRawTextWasSpoken() {
        val delivery = readSpeechDelivery(JSONObject().put("version", 99), "reply")
        val entry = TranscriptEntry("Jarvis", "Unspoken secret", replyId = "reply", delivery = delivery)
        assertNull(entry.forConversation())
    }
    @Test fun trackedCoordinatorLeavesCompletionToPlaybackAndFinalTextOwner() = runBlocking {
        val store = Store(); val session = VoiceSessionController(store)
        VoiceTurnCoordinator(session).processTurn("Hello", replyId = "reply") { emit -> emit("Generated only.") }
        assertFalse(session.currentTranscript().last().complete)
        assertEquals(listOf("Hello"), session.conversationContext().map { it.text })
    }
}
