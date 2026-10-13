package com.battlesbudz.jarvis.v2.chat

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.voice.*
import com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ConversationHistoryTest {
    @Test fun personalStatementSurvivesThirtyTurnsButEraseBoundaryStillWins() {
        val history = ConversationHistory(preferences())
        history.appendUser("I like apricots")
        repeat(30) { index ->
            history.appendUser("Question $index")
            history.updateReply(history.current.value.id, "reply-$index", "Answer $index", true)
        }
        assertTrue(history.contextAfterMemoryCutoff().any { it.text == "I like apricots" })
        assertTrue(history.markMemoryContextCutoff(durable = true))
        assertTrue(history.contextAfterMemoryCutoff().isEmpty())
        history.appendUser("Fresh topic")
        assertEquals(listOf("Fresh topic"), history.contextAfterMemoryCutoff().map { it.text })
    }

    @Test fun textVoiceTextPersistsInOneThreadWithoutDuplicateCallSnapshots() {
        val prefs = preferences()
        val history = ConversationHistory(prefs)
        history.appendUser("My dog's name is Luna")
        val id = history.current.value.id
        history.updateReply(id, "text-reply", "Hello Luna", true)
        val call = VoiceCallRecord("call", 1, conversationId = id, transcript = listOf(
            TranscriptEntry("You", "What is her name?"), TranscriptEntry("Jarvis", "Luna")))
        history.syncCall(call)
        history.syncCall(call.copy(endedAtMs = 2))
        history.appendUser("Tell me a story about her")
        val restored = ConversationHistory(prefs)
        assertEquals(id, restored.current.value.id)
        assertEquals(5, restored.current.value.messages.size)
        assertEquals(listOf(false, false, true, false, false), restored.current.value.messages.map { it.spoken })
        assertEquals("Luna", restored.context()[3].text)
    }

    @Test fun lateCallReceiptCannotOverwriteNewThreadOrTextAfterTheCall() {
        val history = ConversationHistory(preferences())
        val old = history.current.value.id
        val call = VoiceCallRecord("call", 1, conversationId = old, transcript = listOf(TranscriptEntry("You", "Hello")))
        history.syncCall(call)
        history.appendUser("Typed after call")
        history.syncCall(call.copy(transcript = call.transcript + TranscriptEntry("Jarvis", "Hello sir")))
        assertEquals("Typed after call", history.current.value.messages.last().text)
        history.newConversation()
        history.syncCall(call)
        assertTrue(history.current.value.messages.isEmpty())
        assertNotEquals(old, history.current.value.id)
    }

    @Test fun replyMetricsSurviveStreamingReplacementRestartAndCallResync() {
        val prefs = preferences()
        val history = ConversationHistory(prefs)
        val thread = history.current.value.id
        history.updateReply(thread, "text", "draft", false)
        history.updateReplyMetrics(thread, "text") { it.submitted(100).firstRawToken(220).copy(estimatedTokensPerSecond = 9.5) }
        history.updateReply(thread, "text", "final", true)
        assertEquals(2, history.current.value.messages.single().metrics?.estimatedOutputTokens)
        assertEquals(9.5, history.current.value.messages.single().metrics?.estimatedTokensPerSecond)
        val call = VoiceCallRecord("call", 1, conversationId = thread, transcript = listOf(
            TranscriptEntry("Jarvis", "call reply", replyId = "reply", metrics = ReplyMetrics().submitted(400).firstRawToken(650))))
        history.syncCall(call)
        history.appendUser("next turn")
        history.syncCall(call.copy(transcript = listOf(call.transcript.single().copy(
            metrics = call.transcript.single().metrics!!.firstActualPlayback(900)))))
        val restored = ConversationHistory(prefs).current.value.messages
        assertEquals(9.5, restored.first { it.id == "text" }.metrics?.estimatedTokensPerSecond)
        assertEquals(2, restored.first { it.id == "text" }.metrics?.estimatedOutputTokens)
        assertEquals(900L, restored.first { it.callId == "call" }.metrics?.firstReplyPlaybackAtMs)
        assertEquals("reply", restored.first { it.callId == "call" }.sourceReplyId)
        assertEquals("next turn", restored.last().text)
    }

    @Test fun memoryCutoffKeepsVisibleMessagesButExcludesThemAfterRestart() {
        val prefs = preferences()
        val history = ConversationHistory(prefs)
        history.appendUser("My wife is Ada")
        history.updateReply(history.current.value.id, "reply", "Ada is your wife", true)
        history.markMemoryContextCutoff()
        history.appendUser("What is my name?")
        assertEquals(listOf("What is my name?"), history.contextAfterMemoryCutoff().map { it.text })
        assertEquals(listOf("My wife is Ada", "Ada is your wife", "What is my name?"), ConversationHistory(prefs).context().map { it.text })
        assertEquals(listOf("What is my name?"), ConversationHistory(prefs).contextAfterMemoryCutoff().map { it.text })
    }

    @Test fun memoryCutoffExcludesLateGrowthOfAnEarlierCallSegment() {
        val history = ConversationHistory(preferences())
        val thread = history.current.value.id
        val call = VoiceCallRecord("old-call", 1, conversationId = thread, transcript = listOf(TranscriptEntry("You", "Old fact")))
        history.syncCall(call)
        history.markMemoryContextCutoff()
        history.appendUser("Fresh question")
        history.syncCall(call.copy(transcript = call.transcript + TranscriptEntry("Jarvis", "Old fact repeated", timestampMs = 2)))
        assertEquals(listOf("Fresh question"), history.contextAfterMemoryCutoff().map { it.text })
        history.syncCall(call.copy(transcript = call.transcript + TranscriptEntry("You", "New call turn", timestampMs = Long.MAX_VALUE)))
        assertEquals(setOf("Fresh question", "New call turn"), history.contextAfterMemoryCutoff().map { it.text }.toSet())
    }

    @Test fun partialAssistantDraftIsVisibleButExcludedFromModelContext() {
        val history = ConversationHistory(preferences())
        history.appendUser("Tell me a story")
        history.updateReply(history.current.value.id, "reply", "Unfinished draft", false)
        assertEquals(2, history.current.value.messages.size)
        assertEquals(1, history.context().size)
        history.syncCall(VoiceCallRecord("c", 0, conversationId = history.current.value.id,
            transcript = listOf(TranscriptEntry("Jarvis", "Never spoken", complete = false))))
        assertEquals(1, history.context().size)
    }

    @Test fun legacyCallIsOnlyImportedWhenChosenAndNeverImportedTwice() {
        val history = ConversationHistory(preferences())
        val legacy = VoiceCallRecord("old", 1, transcript = listOf(TranscriptEntry("You", "Old request")))
        history.syncCall(legacy)
        assertTrue(history.current.value.messages.isEmpty())
        history.openCall(legacy)
        history.openCall(legacy)
        assertEquals(1, history.current.value.messages.size)
        assertTrue(history.current.value.messages.single().spoken)
    }

    @Test fun linkedVoiceCallRoundTripsAndNewVoiceSegmentDoesNotCopyPriorMessages() {
        val history = ConversationHistory(preferences())
        val store = SharedPreferencesVoiceCallStore(preferences())
        val controller = VoiceSessionController(ConversationVoiceCallStore(store, history))
        val call = controller.beginCall(history.current.value.id)
        controller.appendTranscript("You", "Remember Luna")
        controller.end()
        assertEquals(history.current.value.id, store.list().single().conversationId)
        controller.resumeCall(store.list().single())
        controller.linkConversation(history.current.value.id)
        controller.appendTranscript("You", "Her name?")
        controller.end()
        assertEquals(listOf("Remember Luna", "Her name?"), history.current.value.messages.map { it.text })
        assertEquals(call.conversationId, history.current.value.id)
    }

    @Test fun deletingCallRemovesItsChatSegmentButKeepsTypedMessages() {
        val history = ConversationHistory(preferences())
        val store = ConversationVoiceCallStore(SharedPreferencesVoiceCallStore(preferences()), history)
        history.appendUser("Typed message")
        store.save(VoiceCallRecord("call", 1, conversationId = history.current.value.id,
            transcript = listOf(TranscriptEntry("You", "Spoken message"))))
        store.delete("call")
        assertEquals(listOf("Typed message"), history.current.value.messages.map { it.text })
    }

    @Test fun openingStaleCallDoesNotRollBackTheLatestSavedReply() {
        val history = ConversationHistory(preferences())
        val stale = VoiceCallRecord("call", 1, conversationId = history.current.value.id,
            transcript = listOf(TranscriptEntry("Jarvis", "Draft", complete = false)))
        history.syncCall(stale)
        history.syncCall(stale.copy(transcript = listOf(TranscriptEntry("Jarvis", "Finished reply"))))
        history.openCall(stale)
        assertEquals("Finished reply", history.context().single().text)
    }

    @Test fun attachmentsSurviveRestartAndNeverBecomeToolInstructions() {
        val prefs = preferences()
        val history = ConversationHistory(prefs)
        val attachment = ChatAttachment("file:///private/example.jpg", AttachmentKind.IMAGE)
        history.appendUser("Describe this", attachment)
        val restored = ConversationHistory(prefs)
        assertEquals(attachment, restored.current.value.messages.single().attachment)
        assertEquals("Describe this\n[image attached to this message]", restored.context().single().text)
        assertFalse(restored.context().single().text.contains("file:"))
    }


    @Test fun repeatedReceiptsSurviveCancellationReloadAndCompleteSummary() {
        val prefs = preferences()
        val history = ConversationHistory(prefs)
        val id = history.current.value.id
        val receipt = ActionReceipt("set_volume", "Media volume set to 30 percent.", true)
        history.updateReply(id, "reply", "draft", false)
        history.recordReplyAction(id, "reply", receipt)
        history.recordReplyAction(id, "reply", receipt)
        // A cancellation before a terminal reply still has exact visible and contextual executor evidence.
        val cancelled = ConversationHistory(prefs).current.value.messages.single()
        assertEquals(2, cancelled.actions.size)
        assertEquals(2, Regex("Media volume set to 30 percent.").findAll(cancelled.text).count())
        assertEquals(2, Regex("Media volume set to 30 percent.").findAll(ConversationHistory(prefs).context().single().text).count())

        val completeSummary = "Media volume set to 30 percent. Media volume set to 30 percent."
        history.updateReply(id, "reply", completeSummary, true)
        val reply = ConversationHistory(prefs).current.value.messages.single()
        assertEquals(2, reply.actions.size)
        assertEquals(2, Regex("Media volume set to 30 percent.").findAll(reply.text).count())
        assertEquals(2, Regex("Media volume set to 30 percent.").findAll(ConversationHistory(prefs).context().single().text).count())
    }

    @Test fun singleReceiptFinalSummaryIsRenderedOnce() {
        val history = ConversationHistory(preferences())
        val id = history.current.value.id
        val receipt = ActionReceipt("read_battery", "Battery is at 73 percent.", true)
        history.updateReply(id, "reply", "", false)
        history.recordReplyAction(id, "reply", receipt)
        history.updateReply(id, "reply", receipt.message, true)
        val reply = history.current.value.messages.single()
        assertEquals(1, reply.actions.size)
        assertEquals(1, Regex("Battery is at 73 percent.").findAll(reply.text).count())
        assertEquals(1, Regex("Battery is at 73 percent.").findAll(history.context().single().text).count())
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putInt" -> { values[args!![0] as String] = args[1]; editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getInt" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
