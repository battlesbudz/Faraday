package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.google.ai.edge.litertlm.NativeAudioTimingPhase
import com.google.ai.edge.litertlm.NativeAudioTimingReceipt
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceSpeculationTest {
    private val pcm = byteArrayOf(1, 0, 2, 0)
    private val complete = pcm + byteArrayOf(0, 0)
    private fun proposal() = NativePauseProposal("turn", 9, 1, 2, 300_000_000, pcm)
    private fun certificate(p: NativePauseProposal) = NativePauseCertificate(p, complete.size / 2,
        pcmHash(complete), 1, 3, 650_000_000)
    private fun result(text: String) = GenerationResult(text, 120, 12.0)
    private fun timing() = NativeAudioCaptureTiming(NativeAudioTimingReceipt(
        NativeAudioTimingPhase.SEALED, 1, 2, 1, 1, true, false, false,
        null, null, null, null, null), 310_000_000, 330_000_000, 330_000_000, 340_000_000)
    private class Driver(val answer: suspend ((String) -> Unit, (InferenceProgress) -> Unit) -> GenerationResult) : NativeSpeculationDriver {
        val began = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        var cancelled = 0
        var safe = false
        var resets = 0
        var failDrain = false
        var receipt: NativeAudioCaptureTiming? = null
        override suspend fun generate(proposal: NativePauseProposal, exactPrompt: String, onToken: (String) -> Unit,
                                      onProgress: (InferenceProgress) -> Unit): GenerationResult {
            began.complete(Unit)
            return answer(onToken, onProgress)
        }
        override suspend fun rollback() {
            resets++
            if (failDrain) error("native drain failed")
            safe = true; drained.complete(Unit)
        }
        override fun requestCancel() { cancelled++ }
        override fun safeToRelease() = safe
        override fun timing() = receipt
    }
    private fun lane(scope: CoroutineScope, driver: Driver, current: () -> Boolean = { true }, observe: (String) -> Unit = {}) =
        NativeVoiceSpeculation(scope, "call", "turn", 9, driver,
            NativeVoicePromptPreview("Exact final prompt", current), { true }, current, observe)

    @Test fun acceptedCertificatePromotesLiveStreamWithoutWaitingForFullAnswerOrChangingTtft() = runBlocking<Unit> {
        val continueAnswer = CompletableDeferred<Unit>()
        val openingHeard = CompletableDeferred<Unit>()
        val driver = Driver { token, progress ->
            progress(InferenceProgress(submittedAtMs = 300, firstRawTokenAtMs = 420))
            token("Opening."); continueAnswer.await(); token(" More."); result("Opening. More.")
        }.also { it.receipt = timing() }
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val lane = lane(this, driver, observe = events::add)
        val p = proposal(); assertTrue(lane.onProposal(p)); driver.began.await()
        val timings = mutableListOf<NativeAudioCaptureTiming>()
        val progress = mutableListOf<InferenceProgress>()
        lane.bindProgress(progress::add); lane.bindConfirmedTiming(timings::add)
        assertTrue(progress.isEmpty()); assertTrue(timings.isEmpty())
        assertTrue(lane.confirmCapture(certificate(p), WavEncoder.pcm16Mono(complete, 16000)))
        val heard = StringBuilder()
        val answer = async { lane.promote("Exact final prompt", true) {
            heard.append(it); openingHeard.complete(Unit)
        } }
        withTimeout(3000) { openingHeard.await() }
        assertFalse(answer.isCompleted)
        assertEquals("Opening.", heard.toString())
        assertEquals(0, driver.resets)
        assertEquals(300L, progress.single().submittedAtMs)
        assertEquals(420L, progress.single().firstRawTokenAtMs)
        assertEquals(650_000_000L, timings.single().endpointDecisionAtNs)
        continueAnswer.complete(Unit)
        assertEquals(120L, answer.await()!!.timeToFirstTokenMs)
        assertTrue(lane.promoted); assertEquals(1, driver.resets)
        assertEquals(1, events.count { it == "native_speculation_first_released_token" })
        assertTrue(events.contains("native_speculation_reused"))
        assertTrue(lane.closeAndDrain())
    }

    @Test fun validCertificateButPromptMismatchDoesNotAttributeDraftTimingToFallback() = runBlocking<Unit> {
        val driver = Driver { token, progress ->
            progress(InferenceProgress(300, 420)); token("Held."); result("Held.")
        }.also { it.receipt = timing() }
        val lane = lane(this, driver); val p = proposal()
        lane.onProposal(p); driver.drained.await()
        val timing = mutableListOf<NativeAudioCaptureTiming>(); val progress = mutableListOf<InferenceProgress>()
        lane.bindProgress(progress::add); lane.bindConfirmedTiming(timing::add)
        assertTrue(lane.confirmCapture(certificate(p), WavEncoder.pcm16Mono(complete, 16000)))
        assertNull(lane.promote("Different final context", true) { fail("mismatch leaked") })
        assertTrue(timing.isEmpty()); assertTrue(progress.isEmpty()); assertFalse(lane.promoted)
        assertTrue(lane.closeAndDrain())
    }

    @Test fun validCertificateWithFailedDraftDoesNotLeakReceiptOrProgress() = runBlocking<Unit> {
        val driver = Driver { token, progress ->
            progress(InferenceProgress(300, 420)); token("A".repeat(4097)); result("A".repeat(4097))
        }.also { it.receipt = timing() }
        val lane = lane(this, driver); val p = proposal(); lane.onProposal(p); driver.drained.await()
        lane.bindProgress { fail("failed draft progress attributed") }
        lane.bindConfirmedTiming { fail("failed draft receipt attributed") }
        assertTrue(lane.confirmCapture(certificate(p), WavEncoder.pcm16Mono(complete, 16000)))
        assertNull(lane.promote("Exact final prompt", true) { fail("budget output leaked") })
        assertTrue(lane.closeAndDrain())
    }

    @Test fun pendingCandidateFailureBeforeFirstTokenUsesFullRecordingFallbackWithoutDraftMetrics() = runBlocking<Unit> {
        val finish = CompletableDeferred<Unit>()
        val driver = Driver { _, progress -> progress(InferenceProgress(300, null)); finish.await(); error("send rejected") }
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val lane = lane(this, driver, observe = events::add); val p = proposal(); lane.onProposal(p); driver.began.await()
        lane.bindProgress { fail("no emitted draft must not become answer TTFT") }
        assertTrue(lane.confirmCapture(certificate(p), WavEncoder.pcm16Mono(complete, 16000)))
        val answer = async(start = CoroutineStart.UNDISPATCHED) { lane.promote("Exact final prompt", true) { fail("unexpected token") } }
        finish.complete(Unit)
        assertNull(answer.await()); assertTrue(lane.closeAndDrain())
        assertFalse(events.contains("native_speculation_first_released_token"))
        assertFalse(events.contains("native_speculation_reused"))
    }

    @Test fun wrongCompleteRecordingOrDifferentProposalNeverPromotes() = runBlocking<Unit> {
        for (wrongProposal in listOf(false, true)) {
            val driver = Driver { token, _ -> token("Held"); result("Held") }
            val lane = lane(this, driver); val p = proposal(); lane.onProposal(p); driver.drained.await()
            val cert = certificate(if (wrongProposal) proposal() else p)
            val wav = WavEncoder.pcm16Mono(if (wrongProposal) complete else complete.copyOf().also { it[0] = 4 }, 16000)
            assertFalse(lane.confirmCapture(cert, wav))
            assertNull(lane.promote("Exact final prompt", true) { fail("wrong input") })
            assertTrue(driver.cancelled > 0); assertTrue(lane.closeAndDrain())
        }
    }

    @Test fun nativeMutationAndResumeDrainBeforeAnyReplacementAndKeepFullRecordingUntouched() = runBlocking<Unit> {
        for (resume in listOf(false, true)) {
            val before = complete.copyOf()
            val driver = Driver { _, _ -> awaitCancellation() }
            val lane = lane(this, driver); lane.onProposal(proposal()); driver.began.await()
            if (resume) lane.onInvalidated(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO)
            lane.beforeNativeMutation()
            assertEquals(1, driver.resets); assertTrue(driver.safe)
            assertArrayEquals(before, complete)
            assertNull(lane.promote("Exact final prompt", true) { fail("invalidated") })
            assertTrue(lane.closeAndDrain())
        }
    }

    @Test fun errorCompletionAndFailedChildJoinCannotAuthorizePostAnswerCaptionOrRelease() = runBlocking<Unit> {
        supervisorScope {
            val driver = Driver { _, _ -> error("generation rejected") }.also { it.failDrain = true }
            val lane = lane(this, driver); lane.onProposal(proposal()); driver.began.await()
            val completed = CompletableDeferred<String>()
            val conversation = async {
                completed.complete("Could not answer")
                if (!lane.closeAndDrain()) throw SpeculativeResponseCoordinator.Quarantined(null)
            }
            assertEquals("Could not answer", completed.await())
            conversation.join() // Deliberately reproduces production Job.join's failure semantics.
            var captionNativeMutations = 0
            try {
                lane.beforeNativeMutation()
                captionNativeMutations++ // reset/tools/progress/generate are all after the fence.
                fail("quarantined encoder admitted caption")
            } catch (_: SpeculativeResponseCoordinator.Quarantined) { }
            assertEquals(0, captionNativeMutations)
            var released = 0; var quarantined = 0; var artifactRetained = true
            val lease = com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnModelLease({ true }, { released++ })
            assertTrue(lease.acquireWhenIdle(true))
            val drained = lane.closeAndDrain()
            if (drained) artifactRetained = false
            lease.finishNativeDrain(drained, engineSafeToRelease = true) { quarantined++ }
            assertTrue(artifactRetained); assertTrue(lease.owned)
            assertEquals(0, released); assertEquals(1, quarantined)
            try { conversation.await(); fail("failed child silently succeeded") }
            catch (_: SpeculativeResponseCoordinator.Quarantined) { }
        }
    }

    @Test fun consumedEncoderFailurePreventsFallbackAndOwnerRelease() = runBlocking<Unit> {
        val driver = Driver { _, _ -> awaitCancellation() }.also { it.failDrain = true }
        val lane = lane(this, driver); lane.onProposal(proposal()); driver.began.await()
        try { lane.beforeNativeMutation(); fail("expected quarantine") }
        catch (_: SpeculativeResponseCoordinator.Quarantined) { }
        assertFalse(lane.closeAndDrain())
    }
}
