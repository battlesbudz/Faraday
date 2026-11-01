package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.memory.AutoMemorySource
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Typed-during-call turns and the automatic-memory queue (D-3): a completed typed
 * turn queues a candidate in the chat blob shape; a turn that is not this call's
 * queues nothing. Only the conversation dispatch is faked; the stage, controller
 * and queue are the shipping ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedVoiceInputStageTest {
    private data class AutoQueued(val eventId: String, val source: AutoMemorySource, val text: String, val capturedAtMs: Long)
    private data class Receipt(val eventId: String, val source: ConversationMemorySource, val text: String)

    private class Fixture(answer: String) : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        private val suffix = UUID.randomUUID().toString()
        private fun preferences(name: String) = context.getSharedPreferences("$name-$suffix", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = ConversationHistory(preferences("history"))
        val controller = VoiceSessionController(SharedPreferencesVoiceCallStore(preferences("calls")))
        val state = VoiceCallState()
        val autoQueued = ConcurrentLinkedQueue<AutoQueued>()
        val receipts = ConcurrentLinkedQueue<Receipt>()
        val call = VoiceCallAccess(state, controller, VoiceCallEvents(
            post = { it() }, report = {}, serviceStatus = {}, transcript = { _, _, _ -> },
            finished = {}, startDiagnostics = {}, endCall = {}, returnToWake = {},
            stopService = {}, restartTurn = {}))
        private val memory = VoiceMemoryAccess(
            MemoryDeliveryFence(), VoiceMemoryDeliveryOwner(),
            autoCapture = { eventId, source, text, capturedAtMs -> autoQueued += AutoQueued(eventId, source, text, capturedAtMs) },
            capture = { eventId, _, _, source, text, _ -> receipts += Receipt(eventId, source, text); true },
        )
        private val conversation = VoiceConversationAccess(
            VoiceConversationDispatch { _, callbacks -> callbacks.onComplete(answer); null },
            { null }, {})
        val stage = TypedVoiceInputStage(scope, call, conversation, history, memory,
            createModelLease = { VoiceTurnModelLease({ true }, {}) },
            modelOperationActive = { false },
            diagnosticRecorder = DiagnosticRecorder(preferences("diagnostics")))

        fun beginCall(): String {
            state.armed = true
            return controller.beginCall(history.current.value.id).id
        }

        override fun close() {
            state.armed = false
            scope.cancel()
        }
    }

    @After fun resetUi() {
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.armed.value = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.sessionAlive.value = false
    }

    @Test fun completedTypedTurnQueuesAutoMemoryCandidateAndKeepsReceiptCapture() = runBlocking {
        Fixture("Noted, sir.").use { fixture ->
            val callId = fixture.beginCall()
            val input = CallFinalInput("typed-1", callId, fixture.history.current.value.id,
                "I also love ice cream.", 1_234L)
            fixture.state.inputQueue.offer(input, callId)
            fixture.state.inputQueue.claim(callId)
            fixture.stage.start(input)
            withTimeout(5_000) { while (fixture.autoQueued.isEmpty()) delay(10) }
            fixture.state.turnJob?.join()

            // The receipt capture is unchanged: verbatim typed text, TEXT source.
            assertEquals(listOf(Receipt("typed-1", ConversationMemorySource.TEXT, "I also love ice cream.")),
                fixture.receipts.toList())
            // The auto-memory candidate mirrors the chat blob: user text + answer, CHAT
            // source (typed text is verbatim), the input's own event id and timestamp.
            assertEquals(listOf(AutoQueued("typed-1", AutoMemorySource.CHAT,
                "I also love ice cream.\nNoted, sir.", 1_234L)), fixture.autoQueued.toList())
        }
    }

    @Test fun typedTurnForAnotherCallQueuesNothing() = runBlocking {
        Fixture("Noted, sir.").use { fixture ->
            fixture.beginCall()
            val foreign = CallFinalInput("typed-foreign", "not-the-current-call",
                fixture.history.current.value.id, "I also love ice cream.", 5_678L)
            fixture.stage.start(foreign)
            fixture.state.turnJob?.join()
            assertTrue(fixture.autoQueued.isEmpty())
            assertTrue(fixture.receipts.isEmpty())
        }
    }
}
