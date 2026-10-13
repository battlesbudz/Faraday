package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DelayedAcknowledgementTest {
    private val audio = SpeechAudio(FillerPhrases.INITIAL, 24000, shortArrayOf(1), 0)
    @Test fun neverSpeaksBeforeConfirmation() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this, delayMs = 1) { calls++ }; delay(10)
        assertEquals(0, calls); cue.close()
    }
    @Test fun fastAnswerSkipsCue() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this, delayMs = 100) { calls++ }; cue.request()
        cue.answerReady(); assertEquals(0, calls); cue.close()
    }
    @Test fun missingCacheNeverHoldsAnswer() = runBlocking {
        val cue = DelayedAcknowledgement()
        cue.start(this, delayMs = 1) { fail("No audio cached") }; cue.request(); delay(5)
        withTimeout(100) { cue.answerReady() }; cue.close()
    }
    @Test fun readyAnswerWaitsForCompleteActiveClip() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>(); var released = false
        cue.prepare(audio); cue.start(this, delayMs = 1) {
            try { started.complete(Unit); finish.await() } finally { released = true }
        }
        cue.request(); withTimeout(500) { started.await() }
        val answer = async { cue.answerReady() }; yield()
        assertFalse(answer.isCompleted); assertFalse(released)
        finish.complete(Unit); withTimeout(500) { answer.await() }
        assertTrue(released); cue.close()
    }
    @Test fun readyAnswerCancelsLongFillerAndWaitsForItsRelease() = runBlocking {
        val logs = mutableListOf<String>()
        val cue = DelayedAcknowledgement(logs::add)
        val started = CompletableDeferred<Unit>()
        var released = false
        cue.prepare(audio); cue.start(this, delayMs = 1) {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(40); released = true } }
        }
        cue.request(); withTimeout(500) { started.await() }
        withTimeout(1500) { cue.answerReady() }
        assertTrue(released)
        assertTrue(logs.any { "reason=answer_pcm_ready" in it })
        cue.close()
    }
    @Test fun slowAnswerNeverRepeatsOrAnnouncesAnotherStage() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var calls = 0
        cue.prepare(audio)
        cue.start(this, delayMs = 1) { calls++; started.complete(Unit) }
        cue.request(); withTimeout(500) { started.await() }
        cue.updateStage(DelayedAcknowledgement.Stage.SYNTHESIZING)
        cue.request(); delay(30); cue.answerReady()
        assertEquals(1, calls); cue.close()
    }
    @Test fun cacheCanBecomeReadyDuringInitialWait() = runBlocking {
        val cue = DelayedAcknowledgement(); val played = CompletableDeferred<String>()
        cue.start(this, delayMs = 1, requestPreparation = { cue.prepare(audio.copy(text = it)) }) {
            played.complete(it.text)
        }
        cue.request(); assertEquals(FillerPhrases.INITIAL, withTimeout(500) { played.await() })
        cue.answerReady(); cue.close()
    }
    @Test fun stopCancelsActiveCueAndUnblocksAnswer() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var released = false
        cue.prepare(audio); cue.start(this, delayMs = 1) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        }
        cue.request(); withTimeout(500) { started.await() }
        val answer = async { cue.answerReady() }; cue.close()
        withTimeout(500) { answer.await() }; assertTrue(released)
    }
}
