package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics
import org.junit.Test

class VoiceCallStoreTest {
    @Test fun actionReceiptsAndUnattemptedStepsSurviveEndAndRestart() {
        val store = MemoryStore()
        val first = VoiceSessionController(store)
        val call = first.beginCall()
        first.beginReply(call.id, "r")
        first.recordReplyAction(call.id, "r", VoiceActionOutcome("set_volume", "Media volume set to 30 percent.", true))
        first.updateTask(VoiceTaskStatus(VoiceTaskState.WAITING_FOR_USER,
            completedSteps = listOf("set_volume"), pendingSteps = listOf("read_battery")))
        first.end()

        // The executor can return after End Call. The saved-ID update must retain evidence on the
        // ended call instead of mutating a future active call.
        first.recordReplyAction(call.id, "r", VoiceActionOutcome("read_battery", "Battery is at 50 percent.", true))
        first.updateTaskForCall(call.id, VoiceTaskStatus(VoiceTaskState.FAILED,
            completedSteps = listOf("set_volume", "read_battery"), pendingSteps = listOf("open_app")))
        val saved = store.list().single()
        assertEquals(listOf("set_volume", "read_battery"), saved.transcript.single { it.replyId == "r" }.actions.map { it.name })
        assertEquals(listOf("open_app"), saved.taskStatus?.pendingSteps)

        val restarted = VoiceSessionController(store)
        val resumed = restarted.resumeCall(saved)
        assertNotEquals(saved.id, resumed.id)
        assertEquals(2, resumed.transcript.single { it.replyId == "r" }.actions.size)
        assertEquals(listOf("open_app"), resumed.taskStatus?.pendingSteps)
    }

    private class MemoryStore : VoiceCallStore {
        private val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) { calls.removeAll { it.id == call.id }; calls += call }
        override fun saveProgress(call: VoiceCallRecord) = save(call)
        override fun delete(callId: String) { calls.removeAll { it.id == callId } }
    }
    @Test fun latePlaybackMetricsRoundTripAgainstTheirOriginalReply() {
        val initial = VoiceCallRecord("call", 1, transcript = listOf(
            TranscriptEntry("Jarvis", "result", replyId = "task", metrics = ReplyMetrics().submitted(10).firstRawToken(40))))
        val restored = SharedPreferencesVoiceCallStore.decode(SharedPreferencesVoiceCallStore.encode(listOf(initial))).single()
        val late = restored.copy(transcript = restored.transcript.map { entry ->
            if (entry.replyId == "task") entry.copy(metrics = entry.metrics!!.firstActualPlayback(90)) else entry
        })
        val reloaded = SharedPreferencesVoiceCallStore.decode(SharedPreferencesVoiceCallStore.encode(listOf(late))).single()
        assertEquals(40L, reloaded.transcript.single().metrics?.firstRawTokenAtMs)
        assertEquals(90L, reloaded.transcript.single().metrics?.firstReplyPlaybackAtMs)
    }

    @Test fun typedOriginSurvivesCallPersistence() {
        val encoded = SharedPreferencesVoiceCallStore.encode(listOf(VoiceCallRecord("call", 1,
            transcript = listOf(TranscriptEntry("You", "typed", origin = TranscriptOrigin.TYPED)))))
        assertEquals(TranscriptOrigin.TYPED, SharedPreferencesVoiceCallStore.decode(encoded).single().transcript.single().origin)
    }

}
