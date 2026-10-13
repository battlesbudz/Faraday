package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptionPublicationOwnershipTest {
    private class Store : VoiceCallStore {
        val calls = linkedMapOf<String, VoiceCallRecord>()
        override fun list() = calls.values.toList()
        override fun save(call: VoiceCallRecord) { calls[call.id] = call }
        override fun delete(callId: String) { calls.remove(callId) }
    }
    private class Fixture {
        val store = Store()
        val controller = VoiceSessionController(store)
        val queue = CallInputQueue()
        val call = controller.beginCall("conversation")
        val boundary = queue.captionBoundary(call.id)
        init {
            controller.appendTranscript("You", GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
            controller.beginReply(call.id, "reply")
        }
        val ticket = requireNotNull(controller.captionTicket(call.id, "reply", GemmaAudioInputPolicy.PENDING_TRANSCRIPT))
        fun publish(text: String, farewell: Boolean = false, effect: () -> Unit = {}) =
            queue.publishWithoutNextInput(boundary) {
                controller.publishCaption(ticket, text, { true }, farewell, effect)
                    .also { if (it && farewell) queue.end(call.id) }
            }
    }

    @Test fun quietRawAudioDoesNotRevokeLateCaptionOrFarewell() {
        val f = Fixture()
        var memories = 0
        assertTrue(f.publish("Goodbye Jarvis", farewell = true) { memories++ })
        assertEquals("Goodbye Jarvis", f.controller.currentTranscript().first().text)
        assertEquals(1, memories)
        assertFalse(f.publish("duplicate") { memories++ })
        assertEquals(1, memories)
        assertFalse(f.controller.revokeCaptionForInput(f.call.id))
        assertEquals(CallInputAdmission.Ended,
            f.queue.offer(CallFinalInput("later", f.call.id, "conversation", "next", 1), f.call.id))
        assertFalse(Thread.holdsLock(f.queue))
        assertFalse(Thread.holdsLock(f.controller))
        f.controller.end() // Runtime resource cleanup occurs outside publication locks.
        val replacement = f.controller.beginCall("replacement")
        assertFalse(f.publish("Goodbye", farewell = true) { error("stale farewell") })
        assertEquals(replacement.id, f.controller.currentCallId())
    }

    @Test fun verifiedSpeechRevokesBeforeNativeCancellationAndNeverRestores() {
        val f = Fixture()
        assertTrue(f.controller.revokeCaptionForInput(f.call.id))
        // Native cancellation/drain is still parked. A later rejected candidate does not restore authority.
        assertFalse(f.publish("Goodbye", farewell = true) { error("caption overtook acoustic input") })
        assertFalse(f.publish(VoiceTranscriptResolver.UNTRANSCRIBED))
        assertEquals(GemmaAudioInputPolicy.PENDING_TRANSCRIPT, f.controller.currentTranscript().first().text)
    }

    @Test fun offeredThenImmediatelyClaimedAndPromotedTypedInputStillRevokes() = runBlocking {
        val f = Fixture()
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.queue.awaitInputAfter(f.boundary) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.queue.awaitInputAfter(f.boundary) }
        val typed = CallFinalInput("typed", f.call.id, "conversation", "new request", 1)
        assertEquals(CallInputAdmission.Queued, f.queue.offer(typed, f.call.id))
        assertEquals(typed, f.queue.claim(f.call.id))
        assertTrue(f.queue.promote(typed) {})
        assertFalse(f.queue.hasPending(f.call.id))
        withTimeout(1000) { first.await(); second.await() }
        assertFalse(f.publish("late words") { error("consumed typed input lost authority") })
    }

    @Test fun optionalRevisionObserverDoesNotConsumeDispatcherWake() = runBlocking {
        val f = Fixture()
        val dispatch = async(start = CoroutineStart.UNDISPATCHED) { f.queue.awaitAvailable(f.call.id) }
        val optional = async(start = CoroutineStart.UNDISPATCHED) { f.queue.awaitInputAfter(f.boundary) }
        val typed = CallFinalInput("typed", f.call.id, "conversation", "next", 1)
        f.queue.offer(typed, f.call.id)
        withTimeout(1000) { dispatch.await(); optional.await() }
        assertEquals(typed, f.queue.claim(f.call.id))
    }

    @Test fun deliveredAnswerPersistsWhenOptionalCaptionIsRevoked() {
        val f = Fixture()
        f.controller.updateReplyText(f.call.id, "reply", "The delivered answer.", finished = true)
        f.controller.updateDelivery(f.call.id, SpeechDelivery("reply", SpeechDeliveryState.COMPLETED,
            playedFrames = 16_000, spans = listOf(DeliveredSpeechSpan(0, "The delivered answer.", 0, 16_000, 16_000, true)), revision = 2))
        assertTrue(f.controller.revokeCaptionForInput(f.call.id))
        assertFalse(f.publish("late caption"))
        val answer = f.controller.currentTranscript().last()
        assertTrue(answer.generationComplete)
        assertTrue(answer.complete)
        assertEquals("The delivered answer.", answer.text)
        assertEquals("The delivered answer.", answer.delivery?.deliveredText)
    }

    @Test fun transcriptReplacementAndNewReplyRejectOldTicket() {
        val f = Fixture()
        f.controller.appendTranscript("You", "new input")
        f.controller.beginReply(f.call.id, "new-reply")
        assertFalse(f.publish("Goodbye", farewell = true))
        assertEquals("new input", f.controller.currentTranscript()[2].text)
    }

    @Test fun conversationRelinkRejectsOldTicket() {
        val f = Fixture()
        f.controller.linkConversation("replacement-conversation")
        assertFalse(f.publish("old caption"))
        assertTrue(f.controller.currentTranscript().isEmpty())
    }

    @Test fun answerMetricsDoNotRevokeTheCaptionBackstop() {
        val f = Fixture()
        f.controller.updateReplyText(f.call.id, "reply", "answer", finished = true)
        f.controller.updateReplyMetrics(f.call.id, "reply") { it }
        assertTrue(f.publish("final user words"))
    }

    @Test fun snapshotFarewellLosesToAlreadyAdmittedTypedRevision() {
        val f = Fixture()
        val owner = FinalWhisperFarewellSnapshot.Owner(f.call.id, "capture", requireNotNull(f.controller.currentInputRevision(f.call.id)))
        val snapshot = FinalWhisperFarewellSnapshot.capture(owner, owner, true, "goodbye", "finalized",
            "worker_busy_or_unsupported", null, false)
        val typed = CallFinalInput("typed", f.call.id, "conversation", "continue", 1)
        f.queue.offer(typed, f.call.id); f.queue.claim(f.call.id); f.queue.promote(typed) {}
        assertFalse(f.queue.publishWithoutNextInput(f.boundary) {
            val text = snapshot.farewellIfCurrent(owner) ?: return@publishWithoutNextInput false
            f.controller.finishInputIfCurrent(f.call.id, owner.inputRevision, text)
        })
        assertEquals(f.call.id, f.controller.currentCallId())
    }

    @Test fun onceClaimedCaptionDoesNotReplayAfterEffectFailure() {
        val f = Fixture()
        var attempts = 0
        try { f.publish("final words") { attempts++; error("storage failure") }; fail() }
        catch (_: IllegalStateException) {}
        assertFalse(f.publish("retry") { attempts++ })
        assertEquals(1, attempts)
    }
}
