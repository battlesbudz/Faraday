package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue
import org.junit.Assert.*
import org.junit.Test

/**
 * M1d explicit silently-working mode (D21/D22, T05).
 *
 * Covers the controller (enter/exit, wake phrase, answer window, control
 * passthrough) and the capture-session gate (ordinary speech ignored while
 * silent, wake reopens conversation without touching tasks, controls always
 * honored, typed input bypasses the gate).
 */
class SilentWorkModeTest {

    private fun controller(now: Long = 0L): SilentWorkController {
        var t = now
        return SilentWorkController(clock = { t })
    }

    @Test fun conversationalModePassesOrdinarySpeechThrough() {
        val c = controller()
        assertFalse(c.isSilent)
        assertEquals(SilentSpeechDecision.Answer, c.classify("what's the weather today"))
    }

    @Test fun silentModeIgnoresOrdinarySpeech() {
        val c = controller()
        assertTrue(c.enterSilentWork())
        assertTrue(c.isSilent)
        assertFalse(c.enterSilentWork()) // already silent: no change
        assertEquals(SilentSpeechDecision.Ignored, c.classify("what's the weather today"))
        assertEquals(SilentSpeechDecision.Ignored, c.classify("tell me a joke"))
        assertEquals(SilentSpeechDecision.Ignored, c.classify("open the settings app"))
    }

    @Test fun wakePhraseReopensConversation() {
        val c = controller()
        c.enterSilentWork()
        assertEquals(SilentSpeechDecision.Wake, c.classify("hey jarvis"))
        assertEquals(SilentSpeechDecision.Wake, c.classify("Hey Jarvis, what's the status?"))
        assertEquals(SilentSpeechDecision.Wake, c.classify("hey jarvis."))
        // Quoted, negated or embedded mentions are not the wake phrase.
        assertEquals(SilentSpeechDecision.Ignored, c.classify("\"hey jarvis\" is the wake phrase"))
        assertEquals(SilentSpeechDecision.Ignored, c.classify("never say hey jarvis"))
        assertEquals(SilentSpeechDecision.Ignored, c.classify("I told hey jarvis to stop"))
    }

    @Test fun stopControlsAreHonoredWhileSilent() {
        val c = controller()
        c.enterSilentWork()
        assertEquals(SilentSpeechDecision.Control, c.classify("stop"))
        assertEquals(SilentSpeechDecision.Control, c.classify("stop speaking"))
        assertEquals(SilentSpeechDecision.Control, c.classify("stop your task"))
        assertEquals(SilentSpeechDecision.Control, c.classify("stop all tasks"))
        assertEquals(SilentSpeechDecision.Control, c.classify("cancel"))
    }

    @Test fun exitSilentWorkRestoresConversation() {
        val c = controller()
        c.enterSilentWork()
        assertTrue(c.exitSilentWork())
        assertFalse(c.isSilent)
        assertFalse(c.exitSilentWork()) // already conversational: no change
        assertEquals(SilentSpeechDecision.Answer, c.classify("what's the weather today"))
    }

    @Test fun requiredQuestionTemporarilyListensThenReturnsToSilence() {
        var now = 1_000L
        val c = SilentWorkController(clock = { now })
        c.enterSilentWork()
        assertTrue(c.requestAnswer("approval-1"))
        assertTrue(c.isAnswerWindowOpen())
        assertEquals("approval-1", c.pendingQuestionId())
        // The awaited answer is heard...
        assertEquals(SilentSpeechDecision.Answer, c.classify("yes, go ahead"))
        // ...and the mode returns to silence once it arrives.
        assertTrue(c.onAnswerReceived("approval-1"))
        assertFalse(c.isAnswerWindowOpen())
        assertTrue(c.isSilent)
        assertEquals(SilentSpeechDecision.Ignored, c.classify("what's the weather today"))
    }

    @Test fun answerWindowExpiresBackToSilence() {
        var now = 0L
        val c = SilentWorkController(clock = { now })
        c.enterSilentWork()
        assertTrue(c.requestAnswer("approval-1", windowMs = 1_000L))
        now = 2_000L
        assertFalse(c.isAnswerWindowOpen())
        assertNull(c.pendingQuestionId())
        assertEquals(SilentSpeechDecision.Ignored, c.classify("yes, go ahead"))
        assertFalse(c.onAnswerReceived("approval-1"))
    }

    @Test fun answerWindowNeedsSilentModeAndOneWindowAtATime() {
        val c = controller()
        assertFalse(c.requestAnswer("approval-1")) // not silent
        c.enterSilentWork()
        assertTrue(c.requestAnswer("approval-1"))
        assertFalse(c.requestAnswer("approval-2")) // one window at a time
        assertFalse(c.onAnswerReceived("approval-2")) // wrong question id
        assertTrue(c.isAnswerWindowOpen())
    }

    @Test fun wakeStillWorksWhileAnswerWindowOpen() {
        val c = controller()
        c.enterSilentWork()
        c.requestAnswer("approval-1")
        assertEquals(SilentSpeechDecision.Wake, c.classify("hey jarvis"))
    }

    // Capture-session gate.

    private fun sessionWithSilentWork(silent: Boolean): Pair<ContinuousActionSession<String>, SilentWorkController> {
        val c = controller()
        if (silent) c.enterSilentWork()
        val session = ContinuousActionSession(AcceptedActionQueue<String>(), silentWork = c)
        return session to c
    }

    private fun capture(id: String, text: String) = SessionCapture(id, text)

    @Test fun sessionIgnoresOrdinarySpeechWhileSilent() {
        val (session, _) = sessionWithSilentWork(silent = true)
        val outcome = session.onCaptured(capture("u1", "tell me about roman history"), CapturedKind.Ordinary)
        assertTrue(outcome is CaptureOutcome.Duplicate)
        assertFalse(session.hasDeferredConversation())
    }

    @Test fun sessionWakeReopensConversationWithoutTouchingTasks() {
        val queue = AcceptedActionQueue<String>()
        val c = controller().also { it.enterSilentWork() }
        val session = ContinuousActionSession(queue, silentWork = c)
        assertNotNull(queue.admit("task-1", "utterance-1", "work"))
        val outcome = session.onCaptured(capture("u2", "hey jarvis"), CapturedKind.Ordinary)
        assertFalse(c.isSilent)
        assertTrue(outcome is CaptureOutcome.ConversationReady || outcome is CaptureOutcome.DeferredConversation)
        assertTrue("wake must not cancel tasks", queue.hasUnfinished())
        queue.close()
    }

    @Test fun sessionHonorsStopControlsWhileSilent() {
        val (session, _) = sessionWithSilentWork(silent = true)
        val outcome = session.onCaptured(capture("u3", "stop your task"),
            CapturedKind.Control(VoiceActionControl.CancelCurrent))
        assertTrue(outcome is CaptureOutcome.Control)
        assertEquals(VoiceActionControl.CancelCurrent, (outcome as CaptureOutcome.Control).value)
    }

    @Test fun sessionAnswersRequiredQuestionDuringWindow() {
        val (session, c) = sessionWithSilentWork(silent = true)
        assertTrue(c.requestAnswer("approval-9"))
        val outcome = session.onCaptured(capture("u4", "yes"), CapturedKind.Ordinary)
        assertFalse(outcome is CaptureOutcome.Duplicate)
    }

    @Test fun typedInputBypassesTheSilentGate() {
        val (session, c) = sessionWithSilentWork(silent = true)
        assertTrue(c.isSilent)
        val outcome = session.onTyped(capture("t1", "what's the weather today"), CapturedKind.Ordinary)
        assertFalse("deliberate typed input is not ignored", outcome is CaptureOutcome.Duplicate)
        assertTrue(c.isSilent)
    }

    @Test fun sessionWithoutControllerBehavesAsBefore() {
        val session = ContinuousActionSession(AcceptedActionQueue<String>())
        val outcome = session.onCaptured(capture("u5", "hello there"), CapturedKind.Ordinary)
        assertTrue(outcome is CaptureOutcome.ConversationReady)
    }
}
